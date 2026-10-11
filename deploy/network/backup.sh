#!/usr/bin/env bash
set -euo pipefail
umask 077
cd /opt/ftth-network
mkdir -p backups
exec 8>backups/.backup.lock
flock -w 30 8
stamp=$(date -u +%Y%m%dT%H%M%SZ)
temporary=$(mktemp -d "$PWD/backups/.pending-XXXXXXXX")
trap 'rm -rf -- "$temporary"' EXIT
docker compose exec -T radius-db pg_dump -U radius -d radius -Fc > "$temporary/radius.dump"
docker compose exec -T mongo mongodump --quiet --db genieacs --archive --gzip > "$temporary/genieacs.archive.gz"
tar -czf "$temporary/config.tar.gz" .env docker-compose.yml radius nginx-acs.conf.template
tar -hczf "$temporary/host-config.tar.gz" -C / \
  etc/wireguard/ftth-link.conf etc/wireguard/ftth-link.key etc/openvpn/server \
  etc/nginx/sites-available/ftth-acs.conf \
  etc/letsencrypt/live/acs.ftth.karuhundeveloper.com
archive="$PWD/backups/network-$stamp.tar.gz"
tar -czf "$archive" -C "$temporary" .
gzip -t "$archive"
if [[ -f /root/.ssh/ftth-network-backup && -f /etc/ftth-network-backup.known_hosts ]]; then
  ssh -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=yes \
    -o UserKnownHostsFile=/etc/ftth-network-backup.known_hosts \
    -i /root/.ssh/ftth-network-backup -o IdentitiesOnly=yes \
    hidupjokowi@10.254.67.2 < "$archive"
  echo 'PASS: network backup copied to the mini PC'
fi
find backups -maxdepth 1 -type f -name 'network-*.tar.gz' -mtime +14 -delete
echo "PASS: network backup $archive"
