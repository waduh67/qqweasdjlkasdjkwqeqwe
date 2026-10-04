#!/usr/bin/env python3
"""Configure the runner with existing, host-verified deployment credentials."""
import os
from pathlib import Path
import re

host, user = os.environ["VPS_HOST"], os.environ["VPS_USER"]
if not re.fullmatch(r"[A-Za-z0-9.-]+", host) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]*", user):
    raise ValueError("Invalid deployment endpoint")
if not os.environ["VPS_KNOWN_HOSTS"].strip():
    raise ValueError("Verified host keys are required")
os.umask(0o077)
root = Path.home() / ".ssh"
root.mkdir(exist_ok=True, mode=0o700)
for name, value in {"id_deploy": os.environ["VPS_SSH_KEY"], "known_hosts": os.environ["VPS_KNOWN_HOSTS"]}.items():
    (root / name).write_text(value.rstrip() + "\n")
    (root / name).chmod(0o600)
(root / "config").write_text(f"""Host ftth-target
    HostName {host}
    User {user}
    IdentityFile ~/.ssh/id_deploy
    IdentitiesOnly yes
    BatchMode yes
    StrictHostKeyChecking yes
    UserKnownHostsFile ~/.ssh/known_hosts
    ProxyCommand cloudflared access ssh --hostname %h
""")
