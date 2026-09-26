#!/usr/bin/env bash
# Real owner policy and database time, restricted to the owned browser database.
set +x
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/test-environment.sh"
umask 077
[[ ${WAREHOUSE_QA:-} == true && ${SPRING_DATASOURCE_URL:-} == jdbc:postgresql://127.0.0.1:25432/warehouse_e2e ]] || refuse 'draft clock fixture requires the owned browser QA process'
[[ $# -ge 2 ]] || refuse 'expected policy EMAIL SECONDS or await DOCUMENT_ID'
load_environment
docker_local
check_environment >/dev/null
owner() {
    { printf 'SET ROLE warehouse_owner;\n'; cat; } |
        compose exec -T postgres psql -X -U warehouse_admin -d warehouse_e2e -v ON_ERROR_STOP=1 -At
}
case "$1" in
    policy)
        [[ $# == 3 && "$2" =~ ^admin-[a-f0-9]{8}@example\.test$ && "$3" =~ ^([1-9]|[1-5][0-9]|60)$ ]] || refuse 'expected a generated browser admin and lifetime1..60seconds'
        owner >/dev/null <<SQL
BEGIN;
DO \$\$ BEGIN
    IF (SELECT count(*) FROM app_user WHERE email='$2')<>1 THEN RAISE EXCEPTION 'Expected unique generated browser actor'; END IF;
END \$\$;
SELECT set_config('app.tenant_id',(SELECT tenant_id::text FROM app_user WHERE email='$2'),true);
INSERT INTO inventory_draft_policy(tenant_id,version,ttl_seconds)
SELECT actor.tenant_id,coalesce((SELECT max(version) FROM inventory_draft_policy WHERE tenant_id=actor.tenant_id),0)+1,$3
FROM app_user actor WHERE email='$2';
COMMIT;
SQL
        ;;
    await)
        [[ $# == 2 && "$2" =~ ^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$ ]] || refuse 'expected generated document ID'
        owner >/dev/null <<SQL
SET statement_timeout='25s';
DO \$\$ DECLARE due timestamptz; BEGIN
    SELECT deadline INTO STRICT due FROM inventory_document_draft_activity WHERE document_id='$2' ORDER BY source_revision DESC LIMIT 1;
    IF due>clock_timestamp()+interval '20 seconds' THEN RAISE EXCEPTION 'Expected short browser draft policy'; END IF;
    WHILE clock_timestamp()<due LOOP PERFORM pg_sleep(0.025); END LOOP;
END \$\$;
SQL
        ;;
    *) refuse 'unknown draft clock fixture action' ;;
esac
printf 'PASS: owned draft clock fixture %s\n' "$1"
