#!/usr/bin/env bash
# Auto-wire lab BNG di stack docker-compose.lab.yml: buat plan, customer,
# subscription, collector, NAS FreeRADIUS, dan akun PPPoE budi@isp.net — semua
# lewat API sebagai admin tenant demo. Menyasar lab di http://localhost:8080.
#
#   bash docker/lab/wire-lab.sh
#
# Menyimpan apiKey & accessId ke /tmp/lab_* untuk dipakai start collector + demo isolir.
set -euo pipefail
BASE=${BASE:-http://localhost:8000}

say() { printf '\n\033[1m▶ %s\033[0m\n' "$1"; }
api() {
  local method=$1 path=$2 body=${3:-}
  local args=(-sS -X "$method" "$BASE$path" -H 'Content-Type: application/json')
  [ -n "${TOKEN:-}" ] && args+=(-H "Authorization: Bearer $TOKEN")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}"
}
field() { local f=$1 body=$2; local v; v=$(jq -re "$f" <<<"$body" 2>/dev/null) || { echo "GAGAL — respons:"; echo "$body" | jq . 2>/dev/null || echo "$body"; exit 1; }; echo "$v"; }

say "Login admin@demo.ftth"
LOGIN=$(api POST /api/auth/login '{"tenantSlug":"demo","email":"admin@demo.ftth","password":"admin12345"}')
TOKEN=$(field '.accessToken' "$LOGIN")
echo "token ok"

say "Buat paket (rate profile) 50/10"
PLAN=$(api POST /api/bng/plans '{"name":"Paket Lab 50/10","description":"lab","downMbps":50,"upMbps":10,"radiusProfileName":null}')
RATE_ID=$(field '.id' "$PLAN"); echo "rateProfileId=$RATE_ID"

say "Buat pelanggan"
CUST=$(api POST /api/customers '{"code":"LAB-001","name":"Budi Lab","phone":null,"email":null,"address":"Jl. Lab No.1","location":{"longitude":106.8272,"latitude":-6.1751},"areaId":null}')
CUST_ID=$(field '.id' "$CUST"); echo "customerId=$CUST_ID"

say "Buat langganan + aktifkan"
SUB=$(api POST "/api/customers/$CUST_ID/subscriptions" '{"packageName":"Paket Lab 50/10","bandwidthMbps":50,"monthlyFee":150000}')
SUB_ID=$(field '.id' "$SUB"); echo "subscriptionId=$SUB_ID"
ACT=$(api POST "/api/customers/subscriptions/$SUB_ID/activate" '')
echo "status langganan: $(jq -r '.status // "?"' <<<"$ACT")"

say "Buat collector (interval 30s) — ambil API key"
COL=$(api POST /api/monitoring/collectors '{"name":"Lab Collector","pollIntervalSeconds":30,"status":"ACTIVE"}')
COL_ID=$(field '.collector.id' "$COL")
API_KEY=$(field '.apiKey' "$COL")
echo "collectorId=$COL_ID"; echo "apiKey=$API_KEY"

say "Daftarkan NAS FreeRADIUS (tertaut collector)"
NAS=$(api POST /api/bng/nas "{\"name\":\"FreeRADIUS Lab\",\"vendor\":\"FREERADIUS\",\"address\":\"127.0.0.1\",\"nasIdentifier\":null,\"coaSecret\":\"testing123\",\"collectorId\":\"$COL_ID\",\"enabled\":true,\"apiUsername\":\"radius\",\"apiSecret\":\"radius\",\"apiPort\":null,\"apiUseTls\":false,\"apiDatabase\":\"jdbc:postgresql://radius-db:5432/radius\"}")
NAS_ID=$(field '.id' "$NAS"); echo "nasId=$NAS_ID"

say "Provision akun PPPoE budi@isp.net di NAS itu"
ACC=$(api POST /api/bng/access "{\"subscriptionId\":\"$SUB_ID\",\"username\":\"budi@isp.net\",\"secret\":\"rahasia123\",\"rateProfileId\":\"$RATE_ID\",\"nasId\":\"$NAS_ID\"}")
ACC_ID=$(field '.id' "$ACC"); echo "accessId=$ACC_ID"

printf '%s' "$API_KEY" > /tmp/lab_api_key
printf '%s' "$ACC_ID"  > /tmp/lab_access_id
printf '%s' "$TOKEN"   > /tmp/lab_token
say "SELESAI wiring. apiKey & accessId disimpan di /tmp/lab_*"
echo "Start collector:  FTTH_COLLECTOR_KEY=\$(cat /tmp/lab_api_key) docker compose -f docker-compose.lab.yml --profile collector up -d collector"
