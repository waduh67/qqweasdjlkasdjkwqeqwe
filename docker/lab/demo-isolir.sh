#!/usr/bin/env bash
# Demo isolir -> pulih di lab. Jujur: tanpa BRAS hidup di UDP 3799, DISCONNECT
# tidak menjatuhkan sesi (action -> FAILED), tapi state-machine access berubah nyata.
# Jalankan SETELAH docker/lab/wire-lab.sh (butuh /tmp/lab_access_id).
set -euo pipefail
BASE=${BASE:-http://localhost:8000}
ACC=$(cat /tmp/lab_access_id)

TOKEN=$(curl -sS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"tenantSlug":"demo","email":"admin@demo.ftth","password":"admin12345"}' | jq -r .accessToken)
auth=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

line() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

line "SESI SEBELUM (accessId=$ACC)"
curl -sS "${auth[@]}" "$BASE/api/bng/access/$ACC/session" | jq '{online,framedIp,nasName,nasIp,uptimeSeconds}'

line "ISOLIR -> POST /isolate"
curl -sS "${auth[@]}" -X POST "$BASE/api/bng/access/$ACC/isolate" | jq '{username,status}'

line "Tunggu 6s collector klaim DISCONNECT..."
sleep 6

line "bng_action terbaru (via postgres, bypass RLS)"
docker exec ftth-lab-postgres-1 psql -U postgres -d ftth -tA \
  -c "select action||' '||status||' '||coalesce(detail,'-') from bng_action order by requested_at desc limit 3;" || true

line "SESI SESUDAH ISOLIR (masih online: seeded radacct tak berubah + tak ada BRAS)"
curl -sS "${auth[@]}" "$BASE/api/bng/access/$ACC/session" | jq '{online,framedIp,uptimeSeconds}'

line "PULIH -> POST /restore"
curl -sS "${auth[@]}" -X POST "$BASE/api/bng/access/$ACC/restore" | jq '{username,status}'

printf '\n\033[1mSelesai.\033[0m Status access ACTIVE lagi.\n'
