#!/usr/bin/env bash
# A forced-command SSH key may upload an archive, but cannot execute commands.
set -euo pipefail
umask 077
directory=/opt/ftth/backups/tencent
mkdir -p "$directory"
temporary=$(mktemp "$directory/.incoming-XXXXXXXX")
trap 'rm -f -- "$temporary"' EXIT
cat > "$temporary"
gzip -t "$temporary"
mv -- "$temporary" "$directory/network-$(date -u +%Y%m%dT%H%M%SZ)-$$.tar.gz"
find "$directory" -maxdepth 1 -type f -name 'network-*.tar.gz' -mtime +30 -delete
