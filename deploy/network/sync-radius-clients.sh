#!/usr/bin/env bash
# SQL clients are loaded when FreeRADIUS starts; reload only after NAS changes.
set -euo pipefail
umask 077
cd /opt/ftth-network
exec 9>.release.lock
flock -w 5 9 || exit 0
checksum=$(docker compose exec -T radius-db psql -U radius -d radius -At -c \
  "SELECT md5(COALESCE(string_agg(row_to_json(nas)::text, '|' ORDER BY id), '')) FROM nas")
[[ "$checksum" =~ ^[a-f0-9]{32}$ ]]
[[ ! -f .radius-clients.sha || $(cat .radius-clients.sha) != "$checksum" ]] || exit 0
docker compose restart radius
for attempt in {1..10}; do
  if printf 'Message-Authenticator = 0x00\n' | docker compose exec -T radius radclient -t 2 -r 1 127.0.0.1 status radclient-loopback-only | grep -q 'Received Access-Accept'; then
    printf '%s\n' "$checksum" > .radius-clients.sha
    echo 'PASS: FreeRADIUS loaded the current NAS registry'
    exit 0
  fi
  sleep 1
done
exit 1
