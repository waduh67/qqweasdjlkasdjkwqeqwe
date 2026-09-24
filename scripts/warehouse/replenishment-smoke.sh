#!/usr/bin/env bash
# Sourced only after qa.sh has validated and locked the owned environment.
[[ "${MODE:-}" == replenishment && "${WAREHOUSE_QA:-}" == true ]] || exit 64
command -v python3 >/dev/null || refuse 'python3 is required for packaged HTTP verification'
[[ ! -e "$RUNTIME/warehouse-backend.pid" && ! -e "$RUNTIME/warehouse-web.pid" ]] || refuse 'owned or stale E2E processes exist; run qa.sh stop first'
python3 - <<'PY'
import socket
with socket.socket() as server:
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(('127.0.0.1', 17880))
    server.listen(1)
PY
PROOF="$RUNTIME/replenishment-http"
mkdir -p "$PROOF"
cleanup_replenishment() {
    local exit_status=$?
    trap - EXIT INT TERM
    stop_owned || exit_status=64
    rm -f -- "$RUNTIME/task29-live.json" "$PROOF/state.json"
    exit "$exit_status"
}
trap cleanup_replenishment EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
rm -f -- "$RUNTIME/task29-live.json" "$PROOF/state.json"
gradle :server:clean :server:bootJar :server:test --tests '*WarehouseReplenishmentITLiveSeed' --no-parallel
read -r jar < "$ROOT/server/build/warehouse/boot-jar-path.txt"
[[ -f "$jar" && "$jar" == "$ROOT/server/build/libs/"*.jar && "$jar" != *-plain.jar ]] || refuse 'invalid bootJar output metadata'
[[ -f "$RUNTIME/task29-live.json" ]] || refuse 'live fixture did not produce a manifest'
sha256sum "$jar" > "$PROOF/boot-jar.sha256"

physical_snapshot() {
    local tenant
    tenant=$(jq -er '.tenant' "$RUNTIME/task29-live.json")
    [[ "$tenant" =~ ^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$ ]] || refuse 'invalid live fixture tenant'
    sql_app warehouse_test <<SQL | sed -n '/^\[/p'
BEGIN READ ONLY;
SET LOCAL app.tenant_id = '$tenant';
SELECT json_build_array(
    (SELECT count(*) FROM inventory_movement),
    (SELECT count(*) FROM inventory_movement_leg),
    (SELECT count(*) FROM inventory_balance_projection),
    (SELECT count(*) FROM inventory_reservation),
    (SELECT count(*) FROM inventory_customer_material_fact),
    (SELECT count(*) FROM inventory_outbox),
    (SELECT count(*) FROM inventory_operation),
    (SELECT count(*) FROM inventory_document),
    (SELECT count(*) FROM inventory_document_line),
    (SELECT count(*) FROM inventory_usage_snapshot),
    (SELECT count(*) FROM inventory_fulfillment_effect))::text;
COMMIT;
SQL
}
physical_snapshot > "$PROOF/physical-before.json"
jq -e --slurpfile before "$PROOF/physical-before.json" '(.physicalCounts | fromjson) == $before[0]' "$RUNTIME/task29-live.json" >/dev/null || refuse 'fixture physical counts changed before HTTP verification'

export SERVER_ADDRESS=127.0.0.1 SERVER_PORT=17880 FTTH_SEED_DEMO=false
export FTTH_JWT_SECRET="$WH_APP_PASSWORD" FTTH_ENCRYPTION_SECRET="$WH_OWNER_PASSWORD"
# SMTP is not part of this isolated warehouse workflow. Keep its readiness
# group explicit so a missing database or unhealthy disk still fails the run.
export MANAGEMENT_ENDPOINT_HEALTH_GROUP_WAREHOUSE_INCLUDE=db,diskSpace,ping
export MANAGEMENT_ENDPOINT_HEALTH_GROUP_WAREHOUSE_SHOW_DETAILS=always
export MANAGEMENT_ENDPOINT_HEALTH_GROUP_WAREHOUSE_SHOW_COMPONENTS=always
for phase in before-restart after-restart; do
    start_owned backend java -Xmx768m -jar "$jar"
    python3 "$ROOT/scripts/warehouse/replenishment-http.py" "$phase" "$RUNTIME/task29-live.json" "$PROOF/state.json" | tee "$PROOF/$phase.txt"
    stop_owned
    cp "$RUNTIME/warehouse-backend.log" "$PROOF/$phase-server.log"
done
physical_snapshot > "$PROOF/physical-after.json"
cmp "$PROOF/physical-before.json" "$PROOF/physical-after.json" || refuse 'planning/replay changed physical posting counts'
printf 'PASS: packaged HTTP, restart replay, stale receipt rejection, and unchanged physical posting counts\n'
