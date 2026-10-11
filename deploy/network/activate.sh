#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ $(id -u) == 0 && $# == 2 && $1 =~ ^[a-f0-9]{40}$ ]]
commit=$1
stage=$2
[[ "$stage" == "/home/ubuntu/ftth-network-releases/$commit" && -d "$stage" && ! -L "$stage" ]]
cd /opt/ftth-network
exec 9>.release.lock
flock -w 120 9
[[ -f .env && -f docker-compose.yml ]]
release="$PWD/releases/$commit"
mkdir -p "$release"
if [[ ! -f "$release/env-before" ]]; then
  cp -a .env "$release/env-before"
  cp -a docker-compose.yml "$release/compose-before.yml"
  cp -a radius "$release/radius-before"
  cp -a /etc/nginx/sites-available/ftth-acs.conf "$release/nginx-before.conf"
fi
image="ftth-network-genieacs:$commit"
docker load -i "$stage/genieacs.tar.gz" > "$release/image-load.log"
[[ $(docker image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$image") == "$commit" ]]
restore() {
  status=$?
  trap - ERR
  cp "$release/env-before" .env
  cp "$release/compose-before.yml" docker-compose.yml
  cp -a "$release/radius-before/." radius/
  cp "$release/nginx-before.conf" /etc/nginx/sites-available/ftth-acs.conf
  docker compose up -d --wait --wait-timeout 150 || true
  nginx -t && systemctl reload nginx || true
  echo 'Network activation failed; previous configuration restored' >&2
  exit "$status"
}
trap restore ERR
python3 - "$image" <<'PY'
from pathlib import Path
import sys
path=Path('.env')
lines=[line for line in path.read_text().splitlines() if not line.startswith('GENIEACS_IMAGE=')]
path.write_text('\n'.join(lines)+"\nGENIEACS_IMAGE='"+sys.argv[1]+"'\n")
PY
install -m 600 "$stage/docker-compose.yml" docker-compose.yml
cp -a "$stage/radius/." radius/
install -m 600 "$stage/nginx-acs.conf.template" nginx-acs.conf.template
install -m 700 "$stage/backup.sh" backup.sh
install -m 700 "$stage/sync-radius-clients.sh" sync-radius-clients.sh
install -m 700 "$stage/probe-cwmp.py" probe-cwmp.py
install -m 700 "$stage/vpn-firewall.sh" vpn-firewall.sh
docker compose config --quiet
docker compose up -d --wait --wait-timeout 150
for attempt in {1..30}; do
  if curl --fail --silent --max-time 3 http://10.254.67.1:7557/devices?limit=1 > /dev/null; then break; fi
  sleep 2
done
curl --fail --silent --max-time 5 http://10.254.67.1:7557/devices?limit=1 > /dev/null
printf 'Message-Authenticator = 0x00\n' | docker compose exec -T radius radclient -t 3 -r 1 127.0.0.1 status radclient-loopback-only > "$release/radius-health.log"
grep -q 'Received Access-Accept' "$release/radius-health.log"
python3 - <<'PY'
from pathlib import Path
Path('/etc/nginx/sites-available/ftth-acs.conf').write_text(Path('nginx-acs.conf.template').read_text().replace('{{ACS_DEVICE_HOST}}','acs.ftth.karuhundeveloper.com'))
PY
nginx -t
systemctl reload nginx
python3 probe-cwmp.py --env-file .env --url https://acs.ftth.karuhundeveloper.com/ --nbi http://10.254.67.1:7557
python3 probe-cwmp.py --env-file .env --url http://127.0.0.1:7547/ --nbi http://10.254.67.1:7557
cat > /etc/systemd/system/ftth-network.service <<'UNIT'
[Unit]
Description=Start FTTH network containers after the private interface is available
Requires=docker.service wg-quick@ftth-link.service
After=docker.service wg-quick@ftth-link.service
[Service]
Type=oneshot
RemainAfterExit=true
WorkingDirectory=/opt/ftth-network
ExecStart=/usr/bin/docker compose up -d --wait --wait-timeout 150
TimeoutStartSec=180
[Install]
WantedBy=multi-user.target
UNIT
mkdir -p /etc/systemd/system/openvpn-server@server.service.d
cat > /etc/systemd/system/openvpn-server@server.service.d/ftth-network.conf <<'UNIT'
[Unit]
After=docker.service wg-quick@ftth-link.service
Wants=wg-quick@ftth-link.service
[Service]
ExecStartPre=/opt/ftth-network/vpn-firewall.sh
UNIT
/opt/ftth-network/vpn-firewall.sh
cat > /etc/systemd/system/ftth-network-backup.service <<'UNIT'
[Unit]
Description=Back up FTTH network services and copy to the mini PC
After=docker.service wg-quick@ftth-link.service
[Service]
Type=oneshot
ExecStart=/opt/ftth-network/backup.sh
UNIT
cat > /etc/systemd/system/ftth-network-backup.timer <<'UNIT'
[Unit]
Description=Daily FTTH network backup
[Timer]
OnCalendar=*-*-* 03:15:00 UTC
Persistent=true
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now ftth-network.service
systemctl enable --now ftth-network-backup.timer
cat > /etc/systemd/system/ftth-radius-clients.service <<'UNIT'
[Unit]
Description=Load changed FTTH NAS clients into FreeRADIUS
After=docker.service
[Service]
Type=oneshot
ExecStart=/opt/ftth-network/sync-radius-clients.sh
UNIT
cat > /etc/systemd/system/ftth-radius-clients.timer <<'UNIT'
[Unit]
Description=Detect FTTH NAS registration changes
[Timer]
OnBootSec=60s
OnUnitActiveSec=30s
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now ftth-radius-clients.timer
trap - ERR
printf '{"commit":"%s","image":"%s","status":"PASS"}\n' "$commit" "$image" > "$release/activation.json"
ln -sfn "$release" current
echo "PASS: FTTH network release $commit"
