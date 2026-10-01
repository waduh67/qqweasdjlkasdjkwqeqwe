#!/usr/bin/env python3
"""Install and operate the isolated hosted simulator; never target the production project."""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
SECRET_KEYS = (
    "POSTGRES_PASSWORD", "DB_PASSWORD", "RADIUS_PASSWORD", "S3_ACCESS_KEY", "S3_SECRET_KEY",
    "JWT_SECRET", "ENCRYPTION_SECRET", "WEBHOOK_SECRET", "PLATFORM_PASSWORD",
    "ADMIN_PASSWORD", "DAE_SECRET", "SNMP_COMMUNITY",
)


def initialize(path, domain, email, edge_network=None):
    if not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*\.[a-z]{2,}", domain):
        raise ValueError("Use a DNS hostname without a scheme or path")
    if not re.fullmatch(r"[^\s=@]+@[^\s=@]+\.[^\s=@]+", email):
        raise ValueError("Use a valid administrator email")
    values = {
        "FTTH_SIM_DOMAIN": domain,
        "FTTH_SIM_HTTP_PORT": "18080",
        "FTTH_SIM_TENANT": "simulator",
        "FTTH_SIM_ADMIN_EMAIL": email,
        "FTTH_SIM_PLATFORM_EMAIL": f"platform@{domain}",
    }
    if edge_network:
        if not re.fullmatch(r"[a-zA-Z0-9_.-]+", edge_network):
            raise ValueError("Invalid Docker network name")
        values["FTTH_SIM_EDGE_NETWORK"] = edge_network
    values.update({f"FTTH_SIM_{key}": secrets.token_hex(24) for key in SECRET_KEYS})
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # O_EXCL prevents an accidental init from rotating keys over an existing database.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write("# Private simulator credentials. Keep this file with database backups.\n")
        stream.writelines(f"{key}={value}\n" for key, value in values.items())


def read_settings(path):
    if path.stat().st_mode & 0o077:
        raise ValueError("Credentials must be private: chmod 600 the simulator env file")
    values = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        if not key.startswith("FTTH_SIM_") or "\x00" in value:
            raise ValueError("Unexpected simulator setting")
        values[key] = value
    port = int(values["FTTH_SIM_HTTP_PORT"])
    if not 1024 <= port <= 65535:
        raise ValueError("Invalid simulator HTTP port")
    for key in SECRET_KEYS:
        if len(values[f"FTTH_SIM_{key}"]) < 24:
            raise ValueError(f"Simulator {key} must have at least 24 characters")
    return values


def compose_args(path, values):
    args = ["docker", "compose", "--parallel", "1", "--project-directory", str(ROOT), "--env-file", str(path),
            "--project-name", "ftth-sim", "-f", str(ROOT / "docker-compose.simulator.yml")]
    if values.get("FTTH_SIM_EDGE_NETWORK"):
        args += ["-f", str(ROOT / "deploy/simulator/compose.edge.yml")]
    return args


def wait_ready(base, timeout=240):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base + "/v3/api-docs", timeout=5) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(3)
    raise RuntimeError("Simulator API is not ready; inspect `manage.py logs` before seeding")


def seed(base, values, compose, env):
    wait_ready(base)
    os.environ.update(env)
    spec = importlib.util.spec_from_file_location("sim_seed", ROOT / "docker/lab/seed-lab.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    api = module.Api(base)
    status, result = api.call("POST", "/api/auth/login", {
        "tenantSlug": "platform", "email": values["FTTH_SIM_PLATFORM_EMAIL"],
        "password": values["FTTH_SIM_PLATFORM_PASSWORD"],
    })
    if status != 200 or "accessToken" not in result:
        raise RuntimeError(f"Platform login failed (HTTP {status})")
    api.token = result["accessToken"]
    status, result = api.call("POST", "/api/platform/tenants", {
        "slug": values["FTTH_SIM_TENANT"], "name": "Simulator FTTH",
        "adminEmail": values["FTTH_SIM_ADMIN_EMAIL"], "adminName": "Admin Simulator",
        "adminPassword": values["FTTH_SIM_ADMIN_PASSWORD"], "monthlyFee": 0,
    })
    if status not in (200, 201):
        raise RuntimeError(f"Simulator tenant onboarding failed (HTTP {status})")
    print("Simulator tenant ready", flush=True)
    seed_env = {**env, "BASE": base, "COMPOSE": shlex.join(compose)}
    for script in ("seed-lab.py", "seed-demo-network.py"):
        subprocess.run([sys.executable, str(ROOT / "docker/lab" / script)], env=seed_env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env.simulator")
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init", help="Generate private credentials once")
    init.add_argument("--domain", required=True)
    init.add_argument("--email", required=True)
    init.add_argument("--edge-network")
    commands.add_parser("build")
    commands.add_parser("up", help="Start services without replacing data")
    commands.add_parser("seed", help="Create/reuse the simulator tenant and fixtures")
    commands.add_parser("status")
    commands.add_parser("logs")
    commands.add_parser("stop", help="Stop only this project; volumes are retained")
    args = parser.parse_args()
    path = args.env_file.resolve()
    if args.command == "init":
        initialize(path, args.domain, args.email, args.edge_network)
        print(f"Private credentials saved to {path}")
        return
    values = read_settings(path)
    # Inherited shell overrides must not redirect any simulator service or seed credentials.
    env = {key: value for key, value in os.environ.items() if not key.startswith("FTTH_")}
    env.update(values)
    compose = compose_args(path, values)
    base = f"http://127.0.0.1:{values['FTTH_SIM_HTTP_PORT']}"

    def run(*parts):
        subprocess.run(compose + list(parts), cwd=ROOT, env=env, check=True)

    run("config", "--quiet")
    if args.command == "build":
        run("build")
    elif args.command == "up":
        run("up", "-d", "--no-build", "--wait", "--wait-timeout", "150",
            "postgres", "radius-db", "minio", "genieacs-mongo", "genieacs-cwmp", "genieacs-nbi")
        run("up", "-d", "--no-build", "server", "web", "simulator", "genieacs-sim")
        run("up", "-d", "--no-build", "caddy")
        wait_ready(base)
        print(f"Simulator API ready; public address: https://{values['FTTH_SIM_DOMAIN']}")
    elif args.command == "seed":
        seed(base, values, compose, env)
    elif args.command == "status":
        run("ps")
    elif args.command == "logs":
        run("logs", "--tail=80")
    elif args.command == "stop":
        run("stop")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
