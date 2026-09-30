#!/usr/bin/env bash
# Sourced only after qa.sh has validated and locked the owned environment.
[[ "${MODE:-}" == replenishment && "${WAREHOUSE_QA:-}" == true ]] || exit 64
source "$ROOT/scripts/warehouse/packaged-http.sh"
PACKAGED_TEMP_FILES=("$RUNTIME/task29-live.json")
prepare_packaged_http :server:test --tests '*WarehouseReplenishmentITLiveSeed'
[[ -f "$RUNTIME/task29-live.json" ]] || refuse 'live fixture did not produce a manifest'

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

for phase in before-restart after-restart; do
    run_packaged_phase "$phase" replenishment-http.py "$RUNTIME/task29-live.json" "$PROOF/state.json"
done
physical_snapshot > "$PROOF/physical-after.json"
cmp "$PROOF/physical-before.json" "$PROOF/physical-after.json" || refuse 'planning/replay changed physical posting counts'
printf 'PASS: packaged HTTP, restart replay, stale receipt rejection, and unchanged physical posting counts\n'
