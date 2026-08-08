#!/usr/bin/env bash
# ============================================================================
# Seed lab peniru-protokol: daftarkan OLT + BRAS + pelanggan + CPE ke SIMULATOR
# lewat API app (sebagai admin tenant demo). Setelah ini app langsung "melihat"
# perangkat palsu seolah nyata:
#   • OLT HSGQ  → server polling SNMP ke 172.30.0.10:1161 (IP statis simulator)
#   • BRAS      → server tembak DAE ke 172.30.0.10:3799 (isolir/Reset Login/CoA)
#   • akun PPPoE budi@isp.net → simulator memunculkan sesi radacct hidup
#   • ONU serial C0FD84050205 → ONLINE di OLT sekaligus tertaut ke CPE/ONT genieacs-sim
#
# Dijalankan oleh `make lab` (setelah stack sehat) atau manual:
#   BASE=http://localhost:8080 bash docker/lab/seed-lab.sh
#
# Idempoten: aman diulang — resource yang sudah ada dipakai ulang, bukan digandakan.
# HANYA untuk lab. Simulator & seed ini TAK PERNAH di-deploy ke produksi.
# ============================================================================
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
# Perintah compose untuk satu langkah yang tak bisa lewat API (lihat set_reachability).
COMPOSE=${COMPOSE:-docker compose -f docker-compose.lab.yml}

# Koordinat simulator di jaringan lab (lihat docker-compose.lab.yml).
SIM_IP=172.30.0.10
# Simulator menyalakan >1 agen OLT (armada) di IP yang sama, port beda. Daftarkan semuanya.
# Selaras dengan daftar `ftth.sim.olt.instances` di simulator/src/main/resources/application.yml.
SIM_OLT_PORTS="1161 1162 1163 1164 1165"
DAE_SECRET=testing123
# Serial ONU Budi. Sengaja memakai serial yang DIUMUMKAN simulator OLT (ONU #5 / PON 2 /
# OLT-LAB-5) DAN disetel ke genieacs-sim lewat SIM_SERIAL — satu ONU yang sekaligus ONLINE
# di OLT dan punya CPE. Harus sama persis dengan SIM_SERIAL di docker-compose.lab.yml.
ONU_SERIAL=C0FD84050205

say()  { printf '\n\033[1m▶ %s\033[0m\n' "$1"; }
info() { printf '  %s\n' "$1"; }
die()  { printf '\033[1;31m✗ %s\033[0m\n' "$1" >&2; exit 1; }

command -v jq >/dev/null   || die "butuh 'jq' (pasang: sudo pacman -S jq / apt install jq)"
command -v curl >/dev/null || die "butuh 'curl'"

TOKEN=""
HTTP_STATUS=""
RESP=""
BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT

# api METHOD PATH [JSON] → set HTTP_STATUS + RESP (body).
api() {
  local method=$1 path=$2 body=${3:-}
  local args=(-sS -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json')
  [ -n "$TOKEN" ] && args+=(-H "Authorization: Bearer $TOKEN")
  [ -n "$body" ] && args+=(-d "$body")
  HTTP_STATUS=$(curl "${args[@]}") || die "curl gagal menghubungi $BASE$path (stack sudah jalan?)"
  RESP=$(cat "$BODY_FILE")
}

# ensure LABEL POST_PATH JSON FIND_PATH FIND_JQ → echo id.
# POST dulu; bila 409 (sudah ada) GET FIND_PATH lalu ambil id via FIND_JQ.
ensure() {
  local label=$1 postpath=$2 json=$3 findpath=$4 findjq=$5
  api POST "$postpath" "$json"
  case $HTTP_STATUS in
    2*) jq -re '.id' <<<"$RESP" || die "$label: respons sukses tanpa .id → $RESP" ;;
    409) api GET "$findpath" ''
         jq -re "$findjq" <<<"$RESP" ||
           die "$label sudah ada tapi tak ketemu saat lookup ($findpath) → $RESP" ;;
    *)  die "$label GAGAL (HTTP $HTTP_STATUS) → $RESP" ;;
  esac
}

# set_reachability NAS_ID → flip reachability BRAS ke DIRECT lewat SQL.
# reachability belum diekspos API (form self-service = pekerjaan lanjutan, lihat V37),
# padahal jalur DAE server-side HANYA melayani NAS non-COLLECTOR. Ini satu-satunya langkah
# lab yang menyentuh DB app langsung; best-effort — bila gagal, isolir tertunda PENDING
# tapi bagian lain lab (monitoring/sesi/CPE) tetap hidup.
set_reachability() {
  local nas_id=$1
  if $COMPOSE exec -T postgres psql -U postgres -d ftth -qtAc \
       "UPDATE nas SET reachability='DIRECT' WHERE id='$nas_id';" >/dev/null 2>&1; then
    info "reachability=DIRECT (server layani DAE langsung ke simulator)"
  else
    printf '  \033[1;33m⚠ gagal set reachability=DIRECT via psql — isolir/Reset Login akan tertunda PENDING.\033[0m\n'
    printf "    Perbaiki manual: %s exec -T postgres psql -U postgres -d ftth -c \"UPDATE nas SET reachability='DIRECT' WHERE id='%s';\"\n" "$COMPOSE" "$nas_id"
  fi
}

# ---------------------------------------------------------------------------

say "Login admin@demo.ftth"
api POST /api/auth/login '{"tenantSlug":"demo","email":"admin@demo.ftth","password":"admin12345"}'
[[ $HTTP_STATUS == 2* ]] || die "login gagal (HTTP $HTTP_STATUS) → $RESP"
TOKEN=$(jq -re '.accessToken' <<<"$RESP") || die "respons login tanpa accessToken → $RESP"
info "token ok"

say "Paket 50/10 (catalog)"
PLAN_ID=$(ensure "Paket" /api/catalog/plans \
  '{"name":"Paket Lab 50/10","description":"lab","price":150000,"downMbps":50,"upMbps":10}' \
  /api/catalog/plans '.[] | select(.name=="Paket Lab 50/10") | .id')
info "planId=$PLAN_ID"

say "Site POP Lab"
SITE_ID=$(ensure "Site" /api/sites \
  '{"code":"SITE-LAB","name":"POP Lab","address":"Jl. Lab No.1","location":{"longitude":106.8272,"latitude":-6.1751}}' \
  '/api/sites?query=SITE-LAB' '.content[] | select(.code=="SITE-LAB") | .id')
info "siteId=$SITE_ID"

# reg_olt CODE NAME PORT → daftarkan satu OLT (idempoten) lalu echo id.
reg_olt() {
  local code=$1 name=$2 port=$3
  ensure "$code" /api/olts \
    "{\"siteId\":\"$SITE_ID\",\"code\":\"$code\",\"name\":\"$name\",\"vendor\":\"HSGQ\",\"managementIp\":\"$SIM_IP\",\"snmpCommunity\":\"public\",\"snmpPort\":$port,\"snmpEnabled\":true,\"snmpVersion\":\"V2C\"}" \
    "/api/olts?query=$code" ".content[] | select(.code==\"$code\") | .id"
}

# Armada OLT dari SATU simulator (IP sama, port beda) — bukti OLT emulated bisa banyak.
n=0
for port in $SIM_OLT_PORTS; do
  n=$((n + 1))
  say "OLT HSGQ palsu #$n → SNMP $SIM_IP:$port"
  info "oltId #$n = $(reg_olt "OLT-LAB-$n" "OLT Lab $n (simulator)" "$port")"
done

say "Pelanggan Budi Lab"
CUST_ID=$(ensure "Pelanggan" /api/customers \
  '{"code":"LAB-001","name":"Budi Lab","address":"Jl. Lab No.1","location":{"longitude":106.8272,"latitude":-6.1751}}' \
  '/api/customers?query=LAB-001' '.content[] | select(.code=="LAB-001") | .id')
info "customerId=$CUST_ID"

say "Langganan + aktifkan"
# Langganan tak punya kunci alami → pakai ulang yang sudah ada bila re-run.
api GET "/api/customers/$CUST_ID/subscriptions" ''
SUB_ID=$(jq -re '.[0].id // empty' <<<"$RESP" || true)
if [ -z "$SUB_ID" ]; then
  api POST "/api/customers/$CUST_ID/subscriptions" "{\"planId\":\"$PLAN_ID\"}"
  [[ $HTTP_STATUS == 2* ]] || die "buat langganan gagal (HTTP $HTTP_STATUS) → $RESP"
  SUB_ID=$(jq -re '.id' <<<"$RESP")
fi
info "subscriptionId=$SUB_ID"
api POST "/api/customers/subscriptions/$SUB_ID/activate" ''
info "status langganan: $(jq -r '.status // "?"' <<<"$RESP")"

say "ONU serial $ONU_SERIAL (untuk tautan CPE)"
api GET "/api/customers/$CUST_ID/onus" ''
ONU_ID=$(jq -re ".[] | select(.serialNumber==\"$ONU_SERIAL\") | .id" <<<"$RESP" | head -n1 || true)
if [ -z "$ONU_ID" ]; then
  api POST "/api/customers/$CUST_ID/onus" "{\"serialNumber\":\"$ONU_SERIAL\",\"model\":\"SIM-ONT\"}"
  [[ $HTTP_STATUS == 2* ]] || die "buat ONU gagal (HTTP $HTTP_STATUS) → $RESP"
  ONU_ID=$(jq -re '.id' <<<"$RESP")
fi
info "onuId=$ONU_ID  (CPE tertaut otomatis pada siklus sinkron berikutnya, ~30 dtk)"

say "BRAS → DAE $SIM_IP:$DAE_SECRET@3799"
NAS_ID=$(ensure "BRAS" /api/bng/nas \
  "{\"name\":\"BRAS Lab (simulator)\",\"vendor\":\"MIKROTIK\",\"address\":\"$SIM_IP\",\"nasIdentifier\":null,\"coaSecret\":\"$DAE_SECRET\",\"collectorId\":null,\"enabled\":true,\"apiUsername\":null,\"apiSecret\":null,\"apiPort\":null,\"apiUseTls\":false}" \
  /api/bng/nas '.[] | select(.name=="BRAS Lab (simulator)") | .id')
info "nasId=$NAS_ID"
set_reachability "$NAS_ID"

say "Provision akun PPPoE budi@isp.net di BRAS itu"
api GET "/api/bng/access?customerId=$CUST_ID" ''
ACC_ID=$(jq -re '.[0].id // empty' <<<"$RESP" || true)
if [ -z "$ACC_ID" ]; then
  api POST /api/bng/access \
    "{\"subscriptionId\":\"$SUB_ID\",\"username\":\"budi@isp.net\",\"secret\":\"rahasia123\",\"planId\":\"$PLAN_ID\",\"nasId\":\"$NAS_ID\"}"
  [[ $HTTP_STATUS == 2* ]] || die "provision akun gagal (HTTP $HTTP_STATUS) → $RESP"
  ACC_ID=$(jq -re '.id' <<<"$RESP")
fi
info "accessId=$ACC_ID"

say "SELESAI seeding lab"
info "Sesi PPPoE + trafik muncul dalam ~1 menit (provision radcheck → simulator dial → poll radacct)."
info "Coba isolir/Reset Login di detail pelanggan → server tembak DAE ke simulator (SUCCESS)."
