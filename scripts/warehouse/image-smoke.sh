#!/usr/bin/env bash
set +x
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/test-environment.sh"
umask 077
[[ $# == 3 && "$1" =~ ^sha256:[a-f0-9]{64}$ && "$2" =~ ^sha256:[a-f0-9]{64}$ && "$3" =~ ^[a-f0-9]{40}$ ]] || refuse 'usage: image-smoke.sh SERVER_IMAGE_ID WEB_IMAGE_ID FULL_COMMIT'
reject_overrides
load_environment
exec 9>"$RUNTIME/warehouse.lock"
flock -w 30 9 || refuse 'warehouse QA is busy'
docker_local
check_environment
SERVER_IMAGE=$1 WEB_IMAGE=$2 IMAGE_COMMIT=$3
[[ $(GIT_MASTER=1 git -C "$ROOT" rev-parse HEAD) == "$IMAGE_COMMIT" ]] || refuse 'image source commit differs from checkout'
for built_image in "$SERVER_IMAGE" "$WEB_IMAGE"; do
    [[ $("${DOCKER[@]}" image inspect -f '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$built_image") == "$IMAGE_COMMIT" ]] || refuse 'missing or different image source revision'
done
[[ ! -e "$RUNTIME/warehouse-backend.pid" && ! -e "$RUNTIME/warehouse-web.pid" ]] || refuse 'existing owned browser processes; stop them first'
python3 - <<'PY'
import socket
for port in [17880, 14188]:
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', port))
PY
SMOKE_ID=$(openssl rand -hex 16)
SMOKE_RUN="$RUNTIME/warehouse-image-$SMOKE_ID"
SMOKE_PROJECT="$WH_PROJECT-image-$SMOKE_ID"
mkdir -m 700 "$SMOKE_RUN"
printf '%s\n' "$SMOKE_RUN" > "$RUNTIME/warehouse-image-latest.txt"
export WAREHOUSE_QA=true WAREHOUSE_E2E_DATABASE="warehouse_fixture_$SMOKE_ID" PYTHONDONTWRITEBYTECODE=1
bash "$ROOT/scripts/warehouse/database-fixture.sh" create "$WAREHOUSE_E2E_DATABASE"
cat > "$SMOKE_RUN/application.env" <<ENV
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/$WAREHOUSE_E2E_DATABASE
SPRING_DATASOURCE_USERNAME=$WH_APP_USER
SPRING_DATASOURCE_PASSWORD=$WH_APP_PASSWORD
SPRING_FLYWAY_URL=jdbc:postgresql://postgres:5432/$WAREHOUSE_E2E_DATABASE
SPRING_FLYWAY_USER=$WH_OWNER_USER
SPRING_FLYWAY_PASSWORD=$WH_OWNER_PASSWORD
FTTH_S3_ENDPOINT=http://minio:9000
FTTH_S3_ACCESS_KEY=warehouse_storage
FTTH_S3_SECRET_KEY=$WH_MINIO_PASSWORD
FTTH_S3_BUCKET=warehouse-e2e
SPRING_PROFILES_ACTIVE=warehouse-e2e
WAREHOUSE_ENVIRONMENT_MARKER=$WH_MARKER
WAREHOUSE_E2E_DATABASE=$WAREHOUSE_E2E_DATABASE
WAREHOUSE_QA=true
FTTH_JWT_SECRET=$WH_APP_PASSWORD
FTTH_ENCRYPTION_SECRET=$WH_OWNER_PASSWORD
FTTH_SCHEDULING_ENABLED=false
FTTH_MONITORING_SERVER_POLL_ENABLED=false
FTTH_RADIUS_ENABLED=false
FTTH_PROVISIONING_AUTO_APPLY_ENABLED=false
FTTH_BILLING_PLATFORM_ENABLED=false
FTTH_SEED_DEMO=false
FTTH_CORS_ORIGINS=http://127.0.0.1:14188
MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always
MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS=always
SERVER_ADDRESS=0.0.0.0
SERVER_PORT=8080
ENV
cat > "$SMOKE_RUN/gateway.conf" <<'NGINX'
server {
    listen 80;
    client_max_body_size 25m;
    location /api/ { proxy_pass http://server:8080; }
    location /actuator/ { proxy_pass http://server:8080; }
    location / { proxy_pass http://web:80; }
}
NGINX
cat > "$SMOKE_RUN/compose.env" <<ENV
WH_MARKER=$WH_MARKER
WH_SMOKE_ID=$SMOKE_ID
WH_SMOKE_SERVER_IMAGE=$SERVER_IMAGE
WH_SMOKE_WEB_IMAGE=$WEB_IMAGE
WH_SMOKE_APPLICATION_ENV=$SMOKE_RUN/application.env
WH_SMOKE_GATEWAY_CONFIG=$SMOKE_RUN/gateway.conf
WH_SMOKE_NETWORK=${WH_PROJECT}_warehouse
ENV
smoke_compose() {
    timeout --kill-after=10s 240s "${DOCKER[@]}" compose --env-file "$SMOKE_RUN/compose.env" \
        -p "$SMOKE_PROJECT" -f "$ROOT/deploy/docker-compose.warehouse-smoke.yml" "$@"
}
validate_smoke_containers() {
    local container service actual expected
    for service in server web gateway; do
        container=$(smoke_compose ps -aq "$service")
        [[ -n "$container" ]] || continue
        actual=$("${DOCKER[@]}" inspect -f '{{index .Config.Labels "warehouse.task"}}|{{index .Config.Labels "warehouse.image-smoke"}}|{{.Image}}' "$container")
        expected=$WEB_IMAGE
        [[ "$service" != server ]] || expected=$SERVER_IMAGE
        [[ "$actual" == "$WH_MARKER|$SMOKE_ID|$expected" ]] || refuse 'image smoke container identity mismatch'
    done
}
cleanup_smoke() {
    local status=$?
    trap - EXIT INT TERM
    validate_smoke_containers
    smoke_compose logs --no-color > "$SMOKE_RUN/containers.log" 2>&1 || status=64
    smoke_compose down --timeout 60 || status=64
    printf 'Image smoke database retained; private evidence: %s\n' "$SMOKE_RUN"
    exit "$status"
}
trap cleanup_smoke EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
OWNED_PIDS=' '
source "$ROOT/scripts/warehouse/qa-processes.sh"
smoke_compose up -d
validate_smoke_containers
for phase in before-restart after-restart; do
    if [[ "$phase" == after-restart ]]; then smoke_compose restart --timeout 60 server; fi
    await_json http://127.0.0.1:17880/actuator/health
    await_json http://127.0.0.1:14188/actuator/health
    python3 "$ROOT/scripts/warehouse/image-http.py" "$phase" "$SMOKE_RUN"
    validate_smoke_containers
done
python3 - "$SMOKE_RUN" "$IMAGE_COMMIT" "$SERVER_IMAGE" "$WEB_IMAGE" <<'PY'
from pathlib import Path
import json,sys
run=Path(sys.argv[1])
phases=[json.loads((run/f'{phase}.json').read_text()) for phase in ['before-restart','after-restart']]
assert all(p['persistedReplays']>0 and p['persistedReads']>0 and p['stockKinds']==4 for p in phases)
(run/'verification.json').write_text(json.dumps({'commit':sys.argv[2], 'images':{'server':sys.argv[3],'web':sys.argv[4]},
    'phases':phases,'sameImagesAfterRestart':True,'appRole':'warehouse_app','separateDatabase':True,
    'backendAndGatewayJsonReadiness':True,'databaseRetained':True},indent=2)+'\n')
print('PASS: exact Docker image identities, real read/write, authorization, replay and restart')
PY
