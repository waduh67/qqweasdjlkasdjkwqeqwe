#!/usr/bin/env bash
set +x
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/test-environment.sh"
umask 077
[[ $# == 0 ]] || refuse 'legacy-browser.sh takes no arguments; uses a new owned database each run'
reject_overrides
load_environment
exec 9>"$RUNTIME/warehouse.lock"
flock -w 30 9 || refuse 'warehouse QA is busy'
docker_local
check_environment
OWNED_PIDS=' '
source "$ROOT/scripts/warehouse/qa-processes.sh"
shared_function_fingerprint() {
    local database
    for database in warehouse_test warehouse_e2e; do
        sql_app "$database" <<'SQL'
SELECT md5(coalesce(string_agg(pg_get_functiondef(p.oid), E'\n' ORDER BY p.proname,p.oid),''))
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname='public' AND p.proname LIKE 'warehouse_%' AND p.prokind IN ('f','p');
SQL
    done
}
SHARED_FUNCTIONS_BEFORE=$(shared_function_fingerprint)
[[ ! -e "$RUNTIME/warehouse-backend.pid" && ! -e "$RUNTIME/warehouse-web.pid" ]] || refuse 'owned or stale browser processes exist; run qa.sh stop'
node -e 'const net=require("node:net"); for(const port of [17880,14188]) { const s=net.createServer(); s.once("error",()=>process.exit(64)); s.listen(port,"127.0.0.1",()=>s.close()); }'

LEGACY_HEAD=abaecd9e129cdfe12058d777fad3da2a9320c3c2
LEGACY_BASE="$RUNTIME/warehouse-legacy-ui-base"
if [[ ! -e "$LEGACY_BASE" ]]; then
    GIT_MASTER=1 git -C "$ROOT" worktree add --detach "$LEGACY_BASE" "$LEGACY_HEAD"
fi
[[ ! -L "$LEGACY_BASE" && $(GIT_MASTER=1 git -C "$LEGACY_BASE" rev-parse HEAD) == "$LEGACY_HEAD" ]] || refuse 'historical application identity mismatch'
[[ -z $(GIT_MASTER=1 git -C "$LEGACY_BASE" status --porcelain) ]] || refuse 'historical application source changed'
# All migrations already present in V172 must remain byte-identical.
python3 - "$LEGACY_BASE" "$ROOT" <<'PY'
from pathlib import Path
import sys
old,new=(Path(p)/'server/src/main/resources/db/migration' for p in sys.argv[1:])
for p in old.glob('*.sql'):
    assert p.read_bytes()==(new/p.name).read_bytes(), f'Applied historical migration changed: {p.name}'
PY
(cd "$LEGACY_BASE" && timeout --kill-after=30s 1800s ./gradlew :server:bootJar --no-daemon --no-parallel -Dorg.gradle.workers.max=2 -Pkotlin.compiler.execution.strategy=in-process)
if [[ ! -d "$LEGACY_BASE/web/node_modules" ]]; then npm --prefix "$LEGACY_BASE/web" ci; fi
npm --prefix "$LEGACY_BASE/web" run build
(cd "$ROOT" && timeout --kill-after=30s 1800s ./gradlew :server:bootJar --no-parallel)
npm --prefix "$ROOT/web" run typecheck:warehouse-e2e
npm --prefix "$ROOT/web" run build
read -r CURRENT_JAR < "$ROOT/server/build/warehouse/boot-jar-path.txt"
[[ -f "$CURRENT_JAR" && "$CURRENT_JAR" == "$ROOT/server/build/libs/"*.jar && "$CURRENT_JAR" != *-plain.jar ]] || refuse 'invalid current bootJar metadata'
LEGACY_JARS=()
for candidate in "$LEGACY_BASE/server/build/libs/"*.jar; do
    [[ ! -f "$candidate" || "$candidate" == *-plain.jar ]] || LEGACY_JARS+=("$candidate")
done
[[ ${#LEGACY_JARS[@]} == 1 ]] || refuse 'ambiguous historical bootJar'

LEGACY_RUN_ID=$(openssl rand -hex 16)
export WAREHOUSE_LEGACY_RUN_DIR="$RUNTIME/warehouse-legacy-$LEGACY_RUN_ID"
export WAREHOUSE_LEGACY_SCHEMA=public
export WAREHOUSE_E2E_DATABASE="warehouse_fixture_$LEGACY_RUN_ID"
export WAREHOUSE_QA=true
mkdir -m 700 "$WAREHOUSE_LEGACY_RUN_DIR"
printf '%s\n' "$WAREHOUSE_LEGACY_RUN_DIR" > "$RUNTIME/warehouse-legacy-latest.txt"
bash "$ROOT/scripts/warehouse/database-fixture.sh" create "$WAREHOUSE_E2E_DATABASE"
export SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$WH_PG_PORT/$WAREHOUSE_E2E_DATABASE"
export SPRING_DATASOURCE_USERNAME="$WH_APP_USER" SPRING_DATASOURCE_PASSWORD="$WH_APP_PASSWORD"
export SPRING_FLYWAY_URL="$SPRING_DATASOURCE_URL" SPRING_FLYWAY_USER="$WH_OWNER_USER" SPRING_FLYWAY_PASSWORD="$WH_OWNER_PASSWORD"
export SPRING_FLYWAY_SCHEMAS="$WAREHOUSE_LEGACY_SCHEMA" SPRING_FLYWAY_DEFAULT_SCHEMA="$WAREHOUSE_LEGACY_SCHEMA"
export FTTH_S3_ENDPOINT="http://127.0.0.1:$WH_S3_PORT" FTTH_S3_ACCESS_KEY=warehouse_storage FTTH_S3_SECRET_KEY="$WH_MINIO_PASSWORD" FTTH_S3_BUCKET=warehouse-e2e
export WAREHOUSE_QA=true WAREHOUSE_ENVIRONMENT_MARKER="$WH_MARKER"
export SPRING_PROFILES_ACTIVE=warehouse-e2e SPRING_CONFIG_ADDITIONAL_LOCATION="file:$ROOT/server/src/main/resources/application-warehouse-e2e.yml"
export FTTH_SCHEDULING_ENABLED=false FTTH_MONITORING_SERVER_POLL_ENABLED=false FTTH_RADIUS_ENABLED=false
export FTTH_PROVISIONING_AUTO_APPLY_ENABLED=false FTTH_BILLING_PLATFORM_ENABLED=false FTTH_SEED_DEMO=false
export MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS=always
export FTTH_CORS_ORIGINS=http://127.0.0.1:14188 SERVER_ADDRESS=127.0.0.1 SERVER_PORT=17880
export WAREHOUSE_E2E_BACKEND_URL=http://127.0.0.1:17880 WAREHOUSE_E2E_WEB_URL=http://127.0.0.1:14188
legacy_cleanup() {
    local status=$?
    trap - EXIT INT TERM
    stop_owned || status=64
    if [[ $(shared_function_fingerprint) != "$SHARED_FUNCTIONS_BEFORE" ]]; then
        printf 'FAIL: legacy fixture changed shared QA function definitions\n' >&2
        status=64
    fi
    printf "Legacy database and private artifacts retained: %s\n" "$WAREHOUSE_LEGACY_RUN_DIR"
    exit "$status"
}
trap legacy_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

schema_identity() {
    local actual
    actual=$(sql_app "$WAREHOUSE_E2E_DATABASE" <<SQL
SET search_path TO $WAREHOUSE_LEGACY_SCHEMA,public;
SELECT current_database()||'|'||current_user||'|'||current_schema()||'|'||rolsuper||'|'||rolbypassrls
FROM pg_roles WHERE rolname=current_user;
SQL
    )
    [[ "$actual" == $'SET\n'"$WAREHOUSE_E2E_DATABASE|warehouse_app|$WAREHOUSE_LEGACY_SCHEMA|false|false" ]] || refuse 'unsafe legacy schema application identity'
}
await_legacy() {
    local url=$1 attempt
    for ((attempt=0; attempt<120; attempt++)); do
        if curl --noproxy '*' -fsS --max-time 2 -H 'Accept: application/json' "$url" >"$WAREHOUSE_LEGACY_RUN_DIR/before-readiness.json" 2>/dev/null &&
            jq -e 'type=="object" and .status=="UP" and .components.db.status=="UP"' "$WAREHOUSE_LEGACY_RUN_DIR/before-readiness.json" >/dev/null; then
            schema_identity
            [[ $(sql_app "$WAREHOUSE_E2E_DATABASE" <<<"SELECT version FROM $WAREHOUSE_LEGACY_SCHEMA.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1;") == 172 ]] || refuse 'historical schema is not V172'
            return 0
        fi
        sleep 1
    done
    refuse 'historical application JSON readiness timed out'
}
preview_web() {
    start_owned web node --input-type=module -e 'import {preview} from "vite"; await preview({root:process.argv[1],preview:{host:"127.0.0.1",port:14188,strictPort:true,proxy:{"/api":{target:process.env.WAREHOUSE_E2E_BACKEND_URL},"/actuator":{target:process.env.WAREHOUSE_E2E_BACKEND_URL}}}})' "$1/web"
}
browser_phase() {
    export WAREHOUSE_LEGACY_PHASE=$1
    (cd "$ROOT/web" && timeout --kill-after=15s 900s npx playwright test --config playwright.warehouse-legacy.config.ts --project warehouse-desktop --project warehouse-mobile --trace on)
    local report="$WAREHOUSE_LEGACY_RUN_DIR/$1-report.json" project
    jq -e '.stats.expected==2 and .stats.unexpected==0 and .stats.skipped==0 and .stats.flaky==0' "$report" >/dev/null || refuse 'legacy browser phase did not pass exactly two tests'
    for project in warehouse-desktop warehouse-mobile; do
        jq -e --arg project "$project" '[..|objects|select(.projectName?==$project and .status?=="expected")]|length==1' "$report" >/dev/null || refuse 'missing legacy browser project'
    done
    cp "$RUNTIME/warehouse-backend.log" "$WAREHOUSE_LEGACY_RUN_DIR/$1-backend.log"
}
snapshot() {
    # shellcheck disable=SC2016
    compose exec -T postgres sh -c 'PGPASSWORD="$WH_OWNER_PASSWORD" exec psql -X -h 127.0.0.1 -U warehouse_owner -d "$1" -v ON_ERROR_STOP=1 -At' sh "$WAREHOUSE_E2E_DATABASE" <<SQL > "$WAREHOUSE_LEGACY_RUN_DIR/$1-database.json"
SELECT json_build_object(
 'migrations',(SELECT json_agg(json_build_object('version',version,'checksum',checksum) ORDER BY installed_rank) FROM $WAREHOUSE_LEGACY_SCHEMA.flyway_schema_history WHERE success),
 'customers',(SELECT json_agg(json_build_object('id',id,'name',name,'address',address) ORDER BY id) FROM $WAREHOUSE_LEGACY_SCHEMA.customer),
 'onus',(SELECT json_agg(json_build_object('id',id,'customerId',customer_id,'serial',serial_number) ORDER BY id) FROM $WAREHOUSE_LEGACY_SCHEMA.onu));
SQL
}

start_owned backend java -jar "${LEGACY_JARS[0]}"
await_legacy "$WAREHOUSE_E2E_BACKEND_URL/actuator/health"
preview_web "$LEGACY_BASE"
await_legacy "$WAREHOUSE_E2E_WEB_URL/actuator/health"
browser_phase before
# Owner reads all tenant rows only for this new, isolated database's preservation audit.
snapshot before
stop_owned
start_owned backend java -jar "$CURRENT_JAR"
await_json "$WAREHOUSE_E2E_BACKEND_URL/actuator/health"
schema_identity
preview_web "$ROOT"
await_json "$WAREHOUSE_E2E_WEB_URL/actuator/health"
browser_phase after
snapshot after
stop_owned
start_owned backend java -jar "$CURRENT_JAR"
await_json "$WAREHOUSE_E2E_BACKEND_URL/actuator/health"
preview_web "$ROOT"
await_json "$WAREHOUSE_E2E_WEB_URL/actuator/health"
browser_phase restart
snapshot restart
# Verify the documented operator probe against the same upgraded database and
# catalog identities created by the browser; no SQL business-data writes.
mapfile -t preflight_fixtures < <(python3 - "$WAREHOUSE_LEGACY_RUN_DIR" <<'PY'
from pathlib import Path
import json, re, sys
root = Path(sys.argv[1])
for project in ('warehouse-desktop', 'warehouse-mobile'):
    fixture = json.loads((root / f'{project}-fixture.json').read_text())
    customer, sku = fixture['customer']['id'], fixture['catalogSku']['id']
    assert all(re.fullmatch(r'[a-f0-9-]{36}', value) for value in (customer, sku))
    assert fixture['catalogSku']['code'] == 'POST_UPGRADE_CABLE' and fixture['catalogSku']['unit'] == 'MM'
    print(customer + '|' + sku)
PY
)
[[ ${#preflight_fixtures[@]} == 2 ]] || refuse 'Both upgraded browser catalog fixtures are required'
legacy_preflight_probe() {
    compose exec -T postgres sh -c 'review_database=$1; shift; PGPASSWORD="$WH_APP_PASSWORD" exec psql -X -At -h 127.0.0.1 -U warehouse_app -d "$review_database" -v ON_ERROR_STOP=1 "$@"' sh \
        "$WAREHOUSE_E2E_DATABASE" -v "tenant_id=$preflight_tenant" -v "sku_id=$preflight_sku" \
        -v "expected_version=$1" -v "expected_unit=$2" -v "expected_cutover=$3" < "$ROOT/scripts/warehouse/preflight.sql"
}
preflight_index=0
for pair in "${preflight_fixtures[@]}"; do
    IFS='|' read -r preflight_customer preflight_sku <<< "$pair"
    preflight_tenant=$(compose exec -T postgres sh -c 'review_database=$1; shift; PGPASSWORD="$WH_OWNER_PASSWORD" exec psql -X -At -h 127.0.0.1 -U warehouse_owner -d "$review_database" -v ON_ERROR_STOP=1 "$@"' sh \
        "$WAREHOUSE_E2E_DATABASE" -v "customer_id=$preflight_customer" -v "sku_id=$preflight_sku" <<'SQL'
SELECT sku.tenant_id FROM inventory_sku sku JOIN customer customer ON customer.tenant_id=sku.tenant_id
WHERE sku.id=:'sku_id'::uuid AND customer.id=:'customer_id'::uuid AND sku.code='POST_UPGRADE_CABLE' AND sku.base_unit='MM';
SQL
)
    [[ "$preflight_tenant" =~ ^[a-f0-9-]{36}$ ]] || refuse 'Upgraded browser SKU/customer tenant identity mismatch'
    legacy_preflight_probe 178.8 MM ENFORCED > "$WAREHOUSE_LEGACY_RUN_DIR/preflight-$preflight_index-positive.txt"
    for gate in migration unit cutover; do
        case "$gate" in
            migration) version=170; unit=MM; cutover=ENFORCED; message='migration mismatch' ;;
            unit) version=178.8; unit=EA; cutover=ENFORCED; message='SKU unit mismatch' ;;
            cutover) version=178.8; unit=MM; cutover=LEGACY; message='cutover mismatch' ;;
        esac
        if legacy_preflight_probe "$version" "$unit" "$cutover" > "$WAREHOUSE_LEGACY_RUN_DIR/preflight-$preflight_index-$gate.txt" 2>&1; then
            refuse "Wrong $gate passed upgraded-database preflight"
        fi
        grep -q "$message" "$WAREHOUSE_LEGACY_RUN_DIR/preflight-$preflight_index-$gate.txt" || refuse 'Preflight rejected for an unexpected reason'
    done
    preflight_index=$((preflight_index + 1))
done
python3 - "$WAREHOUSE_LEGACY_RUN_DIR" "$ROOT/scripts/warehouse/preflight.sql" <<'PY'
from pathlib import Path
import hashlib, json, sys
root = Path(sys.argv[1])
probes = []
for index in range(2):
    report = root / f'preflight-{index}-positive.txt'
    rows = [json.loads(line) for line in report.read_text().splitlines() if line.startswith('{')]
    assert len(rows) == 1
    row = rows[0]
    assert row['applicationRole'] == 'warehouse_app' and row['schema'] == 'public'
    assert row['version'] == '178.8' and row['cutover'] == 'ENFORCED'
    assert row['customers'] == row['onus'] == 1 and row['positions'] == 0 and row['verifiedQuantityByUnit'] == {}
    probes.append(row)
(root / 'preflight-verification.json').write_text(json.dumps({'status': 'PASSED', 'schemaBefore': '172',
    'schemaAfter': '178.8', 'readOnly': True, 'positiveProbes': 2, 'negativeProbes': 6,
    'negativeReasons': ['migration mismatch', 'SKU unit mismatch', 'cutover mismatch'], 'rows': probes,
    'preflightSqlSha256': hashlib.sha256(Path(sys.argv[2]).read_bytes()).hexdigest(),
    'reports': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in root.glob('preflight-*.txt')}}, indent=2) + '\n')
print('PASS: upgraded database preflight, two positive and six negative probes, zero invented stock')
PY

[[ $(shared_function_fingerprint) == "$SHARED_FUNCTIONS_BEFORE" ]] || refuse 'legacy fixture changed shared QA function definitions'
python3 - "$WAREHOUSE_LEGACY_RUN_DIR" "$LEGACY_HEAD" "$CURRENT_JAR" "${LEGACY_JARS[0]}" <<'PY'
from pathlib import Path
import hashlib,json,sys,subprocess
run=Path(sys.argv[1]); before=json.loads((run/'before-database.json').read_text())
for phase in ['after','restart']:
    current=json.loads((run/f'{phase}-database.json').read_text())
    assert current['customers']==before['customers'] and current['onus']==before['onus']
    versions={m['version']:m['checksum'] for m in current['migrations']}
    assert all(versions[m['version']]==m['checksum'] for m in before['migrations'])
assert len(before['customers'])==2 and len(before['onus'])==2
(run/'verification.json').write_text(json.dumps({'legacyHead':sys.argv[2], 'currentHead':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
    'jars':{kind:hashlib.sha256(Path(p).read_bytes()).hexdigest() for kind,p in zip(['current','legacy'],sys.argv[3:])},
    'projects':['warehouse-desktop','warehouse-mobile'],'phases':['before','after','restart'],'tests':6,
    'upgradedCatalogSkus':2,'readOnlyPreflight':json.loads((run/'preflight-verification.json').read_text()),
    'preservedCustomers':2,'preservedOnus':2,'historicalChecksumsUnchanged':True,'databaseRetained':True,'separateDatabase':True,'sharedFunctionDefinitionsUnchanged':True},indent=2)+'\n')
print('PASS: historical UI, independent cutover, restart, preserved identities and applied migration checksums (6 real browser tests)')
PY
