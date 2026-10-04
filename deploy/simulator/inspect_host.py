#!/usr/bin/env python3
"""Read deployment prerequisites without exporting credentials or changing services."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def run(*args):
    result = subprocess.run(args, text=True, capture_output=True, timeout=45)
    return result.stdout.strip() if result.returncode == 0 else None


def inspect():
    context = run("docker", "context", "show")
    info = json.loads(run("docker", "info", "--format", "{{json .}}") or "{}")
    containers = []
    ids = (run("docker", "ps", "-aq") or "").split()
    if ids:
        for row in json.loads(run("docker", "inspect", *ids) or "[]"):
            labels = row["Config"].get("Labels") or {}
            containers.append({"name": row["Name"].lstrip("/"), "id": row["Id"],
                "image": row["Image"], "project": labels.get("com.docker.compose.project"),
                "running": row["State"]["Running"], "startedAt": row["State"]["StartedAt"],
                "ports": row["HostConfig"].get("PortBindings")})
    networks = []
    ids = (run("docker", "network", "ls", "-q") or "").split()
    if ids:
        for row in json.loads(run("docker", "network", "inspect", *ids) or "[]"):
            networks.append({"name": row["Name"], "internal": row["Internal"],
                             "ipam": row.get("IPAM", {}).get("Config")})
    roots = [Path.home() / "ftth-simulator", Path("/opt/ftth-simulator")]
    routes = []
    for path in [Path("/etc/cloudflared/config.yml"), Path.home() / ".cloudflared/config.yml"]:
        try:
            # Only route names and HTTP origins; never emit tunnel credentials/tokens.
            for line in path.read_text().splitlines():
                match = re.match(r"\s*-?\s*(hostname|service):\s*([A-Za-z0-9.:/-]+)\s*$", line)
                if match:
                    routes.append({"file": str(path), "key": match[1], "value": match[2]})
        except OSError:
            pass
    disk = shutil.disk_usage(Path.home())
    memory = {}
    for line in Path('/proc/meminfo').read_text().splitlines():
        key, value = line.split(':', 1)
        if key in ('MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree'):
            memory[key] = int(value.split()[0]) * 1024
    desktop = {}
    for path in [Path.home() / '.docker/desktop/settings-store.json', Path.home() / '.docker/desktop/settings.json']:
        try:
            values = json.loads(path.read_text())
            desktop.update({key: value for key, value in values.items()
                            if re.search(r'memory|cpus|swap', key, re.I) and isinstance(value, (int, float, bool))})
        except (OSError, ValueError):
            pass
    # systemd may embed a token in ExecStart. Inspect only its presence locally.
    remote_tunnel = '--token' in (run('systemctl', 'show', 'cloudflared', '-p', 'ExecStart', '--value') or '')
    return {"user": run("id", "-un"), "home": str(Path.home()), "architecture": run("uname", "-m"),
        "hostMemory": memory, "desktopResourceSettings": desktop,
        "containerMemory": [json.loads(line) for line in (run('docker', 'stats', '--no-stream', '--format', '{{json .}}') or '').splitlines()],
        "dockerContext": context, "dockerMemoryBytes": info.get("MemTotal"), "dockerCPUs": info.get("NCPU"),
        "composeVersion": run("docker", "compose", "version", "--short"), "diskFreeBytes": disk.free,
        "passwordlessSudo": subprocess.run(["sudo", "-n", "true"], capture_output=True).returncode == 0,
        "productionDirectory": str(Path("/opt/ftth").resolve()), "containers": containers, "networks": networks,
        "simulatorLocations": [{"path": str(p), "exists": p.exists(), "writable": os.access(p, os.W_OK),
            "privateEnvExists": (p / "private/simulator.env").is_file()} for p in roots],
        "cloudflaredService": run("systemctl", "show", "cloudflared", "-p", "ActiveState", "-p", "FragmentPath"),
        "cloudflaredRoutes": routes, "cloudflaredRemoteManaged": remote_tunnel}


if __name__ == "__main__":
    print(json.dumps(inspect(), indent=2))
