#!/usr/bin/env python3
"""Install verified simulator images and optionally recover an owned, fresh project."""
import argparse
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import time

import manage

MIN_DOCKER_MEMORY = 7 * 1024 ** 3
PROJECT = "ftth-sim"
IMAGE_SERVICES = {"server", "web", "simulator", "minio", "genieacs-cwmp", "genieacs-nbi", "genieacs-sim"}


def atomic(path, value):
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_bytes(value if isinstance(value, bytes) else value.encode())
    temp.chmod(0o600)
    temp.replace(path)


def save(path, value):
    atomic(path, json.dumps(value, indent=2) + "\n")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_manifest(manifest):
    if not re.fullmatch(r"[a-f0-9]{40}", manifest["commit"]):
        raise ValueError("Invalid release commit")
    if not re.fullmatch(r"[a-f0-9]{40}", manifest["applicationCommit"]):
        raise ValueError("Invalid application commit")
    if set(manifest["images"]) != IMAGE_SERVICES:
        raise ValueError("Unexpected image services")
    for ref in manifest["images"].values():
        if not re.fullmatch(r"ghcr\.io/[a-z0-9_-]+/ftth-[a-z0-9-]+@sha256:[a-f0-9]{64}", ref):
            raise ValueError("An immutable FTTH image reference is required")


def extract_backup(archive, destination):
    """Reject links and path traversal before writing any archive entry."""
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        for member in members:
            path = Path(member.name)
            if path.is_absolute() or ".." in path.parts or not (member.isfile() or member.isdir()):
                raise ValueError("Unsafe backup member")
            if member.size > 2 * 1024 ** 3:
                raise ValueError("Unexpected backup member size")
        roots = {Path(m.name).parts[0] for m in members}
        if len(roots) != 1:
            raise ValueError("One backup root is required")
        for member in members:
            target = destination / member.name
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True, mode=0o700)
            else:
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                with source.extractfile(member) as data, target.open("wb") as output:
                    shutil.copyfileobj(data, output)
                target.chmod(0o600)
    root = destination / roots.pop()
    required = {"ftth.dump", "radius.dump", "genieacs.archive.gz", "minio.tar.gz", "simulator.env", "complete.json"}
    if not all((root / name).is_file() for name in required):
        raise ValueError("Incomplete simulator backup")
    if json.loads((root / "complete.json").read_text())["project"] != PROJECT:
        raise ValueError("Backup belongs to another project")
    return root


def check_config(config):
    if config.get("name") != PROJECT or not config["networks"]["default"].get("internal"):
        raise ValueError("Simulator must use its own internal network")
    for name, volume in config.get("volumes", {}).items():
        if volume.get("external") or volume.get("name") != PROJECT + "_" + name:
            raise ValueError("Simulator volumes must belong to its own project")
    for name, service in config["services"].items():
        if set(service.get("networks", {})) != {"default"}:
            raise ValueError("An unexpected external network is configured")
        ports = service.get("ports", [])
        if name == "caddy":
            if len(ports) != 1 or ports[0].get("host_ip") != "127.0.0.1" or str(ports[0]["published"]) != "18080":
                raise ValueError("Gateway must listen only on loopback port 18080")
        elif ports:
            raise ValueError("Device services must not publish ports")
        for volume in service.get("volumes", []):
            if volume["type"] == "volume" and volume["source"] not in config["volumes"]:
                raise ValueError("Unexpected simulator data volume")
            if volume["type"] == "bind" and (not volume.get("read_only") or not Path(volume["source"]).resolve().is_relative_to(manage.ROOT)):
                raise ValueError("Only read-only release files may be bind-mounted")


class Installer:
    def __init__(self, base, manifest, domain, backup):
        self.base, self.manifest, self.domain, self.archive = base, manifest, domain, backup
        self.private = base / "private"
        self.credentials = self.private / "simulator.env"
        self.state_path = self.private / "install-state.json"
        self.state = {}
        self.env = {key: value for key, value in os.environ.items() if not key.startswith("FTTH_")}
        self.log = None

    def run(self, args, stdin=None, output=False, destination=None):
        result = subprocess.run(args, env=self.env, stdin=stdin, text=output,
                                stdout=subprocess.PIPE if output else destination if destination is not None else self.log,
                                stderr=self.log, timeout=1200)
        if result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}); private installation log contains details")
        return result.stdout.strip() if output else None

    def snapshot(self):
        ids = self.run(["docker", "ps", "-aq"], output=True).split()
        rows = json.loads(self.run(["docker", "inspect", *ids], output=True)) if ids else []
        return {row["Name"]: {"id": row["Id"], "image": row["Image"], "startedAt": row["State"]["StartedAt"],
                             "running": row["State"]["Running"]}
                for row in rows if (row["Config"].get("Labels") or {}).get("com.docker.compose.project") != PROJECT}

    def preflight(self):
        memory = int(self.run(["docker", "info", "--format", "{{.MemTotal}}"], output=True))
        if memory < MIN_DOCKER_MEMORY:
            raise RuntimeError("Docker needs 8 GB allocated before adding the simulator")
        if shutil.disk_usage(self.base).free < 12 * 1024 ** 3:
            raise RuntimeError("At least 12 GB free disk space is required")
        ids = self.run(["docker", "network", "ls", "-q"], output=True).split()
        for network in json.loads(self.run(["docker", "network", "inspect", *ids], output=True)):
            if network["Name"] == "ftth-sim_default":
                continue
            for entry in network.get("IPAM", {}).get("Config") or []:
                subnet = ipaddress.ip_network(entry.get("Subnet", "0.0.0.0/32"))
                if subnet.overlaps(ipaddress.ip_network("172.30.0.0/24")):
                    raise RuntimeError("Simulator subnet overlaps an existing Docker network")

    def record(self, **updates):
        self.state.update(updates)
        save(self.state_path, self.state)

    def prepare_credentials(self):
        if self.state_path.exists():
            self.state = json.loads(self.state_path.read_text())
        existing = self.run(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=ftth-sim"], output=True)
        volumes = self.run(["docker", "volume", "ls", "-q", "--filter", "label=com.docker.compose.project=ftth-sim"], output=True)
        if (existing or volumes) and not self.credentials.exists():
            raise RuntimeError("Existing simulator resources have no credentials; restore the original private directory")
        if self.archive:
            sha = digest(self.archive)
            if self.state.get("backupSha256") not in (None, sha):
                raise ValueError("Recovery archive changed during installation")
        if not self.credentials.exists():
            if self.archive:
                root = extract_backup(self.archive, self.private / "recovery")
                self.record(backupSha256=sha, backupRoot=str(root), restored=[], phase="RESTORING")
                atomic(self.credentials, (root / "simulator.env").read_bytes())
                if (root / "seed-state").is_dir():
                    shutil.copytree(root / "seed-state", self.private / "seed-state", dirs_exist_ok=True)
            else:
                manage.initialize(self.credentials, self.domain, "admin@karuhundeveloper.com")
                self.record(phase="NEW")
        values = manage.read_settings(self.credentials)
        if values["FTTH_SIM_TENANT"] != "simulator":
            raise ValueError("Unexpected tenant")
        # The migrated host uses a Cloudflare tunnel to loopback, without an edge Docker network.
        values.pop("FTTH_SIM_EDGE_NETWORK", None)
        values.update(FTTH_SIM_DOMAIN=self.domain, FTTH_SIM_HTTP_PORT="18080")
        atomic(self.credentials, "".join(f"{key}={value}\n" for key, value in values.items()))
        self.env.update(values)
        return values

    def restore(self, compose):
        if self.state.get("phase") != "RESTORING":
            return
        # A retry may overwrite only this installer's incomplete, unused recovery.
        application = self.run(compose + ["ps", "--all", "-q", "server"], output=True)
        if application:
            raise RuntimeError("Refusing to restore after a simulator application container has been created")
        root = Path(self.state["backupRoot"])
        for name in ("ftth", "radius", "genieacs", "minio"):
            if name in self.state["restored"]:
                continue
            print("Restoring simulator " + name, flush=True)
            if name in ("ftth", "radius"):
                service, user, owner = ("postgres", "postgres", "warehouse_owner") if name == "ftth" else ("radius-db", "radius", "radius")
                prefix = compose + ["exec", "-T", service]
                self.run(prefix + ["dropdb", "-U", user, "--if-exists", name])
                self.run(prefix + ["createdb", "-U", user, "-O", owner, name])
                if name == "ftth":
                    self.run(prefix + ["psql", "-U", user, "-d", name, "-v", "ON_ERROR_STOP=1", "-c",
                        "REVOKE ALL ON DATABASE ftth FROM PUBLIC; GRANT CONNECT ON DATABASE ftth TO warehouse_app; "
                        "CREATE EXTENSION postgis; CREATE EXTENSION timescaledb; SELECT timescaledb_pre_restore();"])
                if name == "ftth":
                    # pg_restore clears search_path. Existing immutable SQL helpers
                    # used by CHECKs/generated columns call other public helpers
                    # without qualification. Restore this trusted app dump with
                    # pg_catalog first and public available, without changing functions.
                    sql = self.private / "restore-ftth.sql"
                    adjusted = self.private / "restore-ftth-ready.sql"
                    with (root / "ftth.dump").open("rb") as data, sql.open("wb") as output:
                        self.run(prefix + ["pg_restore", "--file=-"], stdin=data, destination=output)
                    changed = 0
                    with sql.open() as source, adjusted.open("w") as output:
                        for line in source:
                            if line.strip() == "SELECT pg_catalog.set_config('search_path', '', false);":
                                line = "SELECT pg_catalog.set_config('search_path', 'pg_catalog, public', false);\n"
                                changed += 1
                            output.write(line)
                    if changed != 1:
                        raise ValueError("Unexpected pg_restore SQL preamble")
                    with adjusted.open("rb") as data:
                        self.run(prefix + ["psql", "-X", "-U", user, "-d", name, "-v", "ON_ERROR_STOP=1"], stdin=data)
                    sql.unlink()
                    adjusted.unlink()
                else:
                    with (root / (name + ".dump")).open("rb") as data:
                        self.run(prefix + ["pg_restore", "-U", user, "-d", name, "--exit-on-error"], stdin=data)
                if name == "ftth":
                    self.run(prefix + ["psql", "-U", user, "-d", name, "-v", "ON_ERROR_STOP=1", "-c", "SELECT timescaledb_post_restore(); ANALYZE;"])
            elif name == "genieacs":
                with (root / "genieacs.archive.gz").open("rb") as data:
                    self.run(compose + ["exec", "-T", "genieacs-mongo", "mongorestore", "--quiet", "--drop", "--gzip", "--archive"], stdin=data)
            else:
                with tarfile.open(root / "minio.tar.gz", "r:gz") as data:
                    if any(Path(m.name).is_absolute() or ".." in Path(m.name).parts or not (m.isfile() or m.isdir()) for m in data.getmembers()):
                        raise ValueError("Unsafe object storage backup")
                with (root / "minio.tar.gz").open("rb") as data:
                    self.run(compose + ["run", "--rm", "-T", "--no-deps", "--entrypoint", "tar", "minio", "-xzf", "-", "-C", "/data"], stdin=data)
            self.record(restored=self.state["restored"] + [name])
        self.record(phase="RESTORED")

    def install(self):
        check_manifest(self.manifest)
        if not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*\.[a-z]{2,}", self.domain):
            raise ValueError("Invalid public hostname")
        self.preflight()
        before = self.snapshot()
        save(self.private / "containers-before.json", before)
        values = self.prepare_credentials()
        if self.state.get("phase") == "COMPLETE":
            print("Backing up the installed simulator before update", flush=True)
            self.run(["python3", str(manage.ROOT / "deploy/simulator/manage.py"), "--env-file", str(self.credentials),
                      "backup", "--output", str(self.private / "backups" / ("before-" + str(time.time_ns())))])
        source_compose = manage.compose_args(self.credentials, values, use_runtime=False)
        config = json.loads(self.run(source_compose + ["config", "--format", "json"], output=True))
        check_config(config)
        for name, service in config["services"].items():
            service.pop("build", None)
            service["image"] = self.manifest["images"].get(name, service["image"])
        for reference in sorted({s["image"] for s in config["services"].values()}):
            self.run(["docker", "pull", reference])
        for name in ("server", "web", "simulator"):
            image = json.loads(self.run(["docker", "image", "inspect", config["services"][name]["image"]], output=True))[0]
            revision = image["Config"].get("Labels", {}).get("org.opencontainers.image.revision")
            expected = self.manifest["commit"] if name == "simulator" else self.manifest["applicationCommit"]
            if revision != expected:
                raise ValueError("Image revision does not match the verified release")
        # Rendered configuration contains credentials. It stays mode 600 on the server.
        save(self.private / "compose.json", config)
        compose = manage.compose_args(self.credentials, values)
        self.run(compose + ["config", "--quiet"])
        self.run(compose + ["up", "-d", "--no-build", "--wait", "--wait-timeout", "150", "postgres", "radius-db", "genieacs-mongo"])
        self.restore(compose)
        self.run(["python3", str(manage.ROOT / "deploy/simulator/manage.py"), "--env-file", str(self.credentials), "up"])
        if self.state.get("phase") == "NEW":
            self.run(["python3", str(manage.ROOT / "deploy/simulator/manage.py"), "--env-file", str(self.credentials), "seed"])
        print("Verifying SNMP, virtual NAS and TR-069 controls", flush=True)
        self.run(["python3", str(manage.ROOT / "deploy/simulator/verify.py"), "--env-file", str(self.credentials),
                  "--exercise-controls", "--output", str(self.private / "protocol-verification.json")])
        after = self.snapshot()
        if before != after:
            raise RuntimeError("An existing container changed during simulator deployment; review the private receipt")
        receipt = {"status": "PASS", "commit": self.manifest["commit"], "applicationCommit": self.manifest["applicationCommit"],
                   "images": self.manifest["images"], "existingContainersUnchanged": len(before),
                   "loopbackUrl": "http://127.0.0.1:18080", "publicUrl": "https://" + self.domain,
                   "protocols": json.loads((self.private / "protocol-verification.json").read_text())}
        save(self.private / "release.json", receipt)
        self.record(phase="COMPLETE", commit=self.manifest["commit"])
        current = self.base / "current"
        if current.exists() and not current.is_symlink():
            raise RuntimeError("The current release pointer is not a symlink")
        next_link = self.base / ".current-next"
        next_link.unlink(missing_ok=True)
        next_link.symlink_to(manage.ROOT, target_is_directory=True)
        next_link.replace(current)
        backup_path = self.private / "backups" / (time.strftime("%Y%m%d-%H%M%S") + "-" + self.manifest["commit"][:8])
        self.run(["python3", str(manage.ROOT / "deploy/simulator/manage.py"), "--env-file", str(self.credentials),
                  "backup", "--output", str(backup_path)])
        print("Simulator installation and controls verified; public routing is checked separately", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--domain", required=True)
    parser.add_argument("--backup", type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    base = Path.home() / "ftth-simulator"
    private = base / "private"
    private.mkdir(parents=True, exist_ok=True, mode=0o700)
    manifest = json.loads(args.manifest.read_text())
    installer = Installer(base, manifest, args.domain, args.backup)
    with (private / "install.lock").open("a") as lock, (private / "install.log").open("a") as log:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        installer.log = log
        try:
            installer.install()
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
            save(private / "failure.json", {"commit": manifest.get("commit"), "type": type(error).__name__, "message": str(error)})
            raise SystemExit(str(error))


if __name__ == "__main__":
    main()
