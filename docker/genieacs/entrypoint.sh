#!/bin/sh
# Entrypoint bersama keempat proses GenieACS (cwmp/nbi/fs/ui) — semuanya dari image yang
# sama, hanya beda `command:`.
#
# Satu-satunya tugas tambahannya: menyelaraskan auth CWMP dari environment sebelum proses
# cwmp naik (lihat acs-bootstrap.js). Sengaja HANYA untuk `genieacs-cwmp`:
#  - setelan ini memang milik jalur cwmp; nbi/fs tak ada urusannya, dan
#  - hanya ada satu container cwmp, jadi tak ada dua proses yang berebut menulis config
#    yang sama saat stack naik serentak.
set -e

case "$1" in
  genieacs-cwmp)
    node /usr/local/bin/acs-bootstrap.js
    ;;
esac

exec "$@"
