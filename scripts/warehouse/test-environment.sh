#!/usr/bin/env bash
set +x
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
RUNTIME="$ROOT/.omo/runtime"
ENV_FILE="$RUNTIME/warehouse-test.env"
COMPOSE_FILE="$ROOT/deploy/docker-compose.warehouse-test.yml"
TASK_ID=$(printf '%s' "$ROOT" | sha256sum | cut -c1-12)
DOCKER=(docker --host unix:///var/run/docker.sock)

refuse() { printf 'REFUSED: %s\n' "$*" >&2; exit 64; }

reject_overrides() {
    local variable
    while IFS= read -r variable; do
        case "$variable" in
            SPRING_*|FTTH_*|WH_*|PGHOST*|PGPORT|PGDATABASE|PGUSER|PGPASSWORD|PGSERVICE*|PGOPTIONS|DOCKER_HOST|DOCKER_CONTEXT|COMPOSE_*|_JAVA_OPTIONS|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|JAVA_OPTS|GRADLE_OPTS)
                refuse "external configuration override ($variable); use only the generated warehouse environment" ;;
        esac
    done < <(compgen -e)
}

docker_local() {
    if ! "${DOCKER[@]}" info >/dev/null 2>&1; then
        DOCKER=(sudo -n docker --host unix:///var/run/docker.sock)
        "${DOCKER[@]}" info >/dev/null 2>&1 || refuse 'local Docker unavailable; grant local Docker access or passwordless sudo for Docker'
    fi
}

compose() { timeout --kill-after=10s 240s "${DOCKER[@]}" compose --env-file "$ENV_FILE" -p "$WH_PROJECT" -f "$COMPOSE_FILE" "$@"; }

load_environment() {
    [[ ! -L "$ROOT/.omo" && ! -L "$RUNTIME" ]] || refuse 'runtime directory must not be a symlink'
    [[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || refuse 'missing generated environment marker; run test-environment.sh up'
    [[ $(stat -c '%a:%u' "$ENV_FILE") == "600:$(id -u)" ]] || refuse 'environment must be owned by current user with mode 0600'
    local line key value count=0
    declare -A seen=()
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ "$line" =~ ^(WH_[A-Z0-9_]+)=([a-zA-Z0-9_./:-]+)$ ]] || refuse 'invalid environment syntax'
        key=${BASH_REMATCH[1]}; value=${BASH_REMATCH[2]}
        case "$key" in
            WH_MARKER|WH_PROJECT|WH_HOST|WH_PG_PORT|WH_S3_PORT|WH_TEST_DB|WH_E2E_DB|WH_APP_USER|WH_OWNER_USER|WH_ADMIN_PASSWORD|WH_APP_PASSWORD|WH_OWNER_PASSWORD|WH_MINIO_PASSWORD) ;;
            *) refuse 'unexpected environment key' ;;
        esac
        [[ ! -v seen[$key] ]] || refuse 'duplicate environment key'
        seen[$key]=1
        printf -v "$key" '%s' "$value"
        count=$((count + 1))
    done < "$ENV_FILE"
    [[ "$count" == 13 ]] || refuse 'incomplete environment marker'
    [[ "$WH_MARKER" =~ ^warehouse-${TASK_ID}-[a-f0-9]{32}$ ]] || refuse 'stale or foreign environment marker'
    [[ "$WH_PROJECT" == "$WH_MARKER" ]] || refuse 'unexpected Compose project'
    [[ "$WH_HOST" == 127.0.0.1 && "$WH_PG_PORT" == 25432 && "$WH_S3_PORT" == 29000 ]] || refuse 'unexpected or production host/port'
    [[ "$WH_TEST_DB" == warehouse_test && "$WH_E2E_DB" == warehouse_e2e ]] || refuse 'unexpected or production database'
    [[ "$WH_APP_USER" == warehouse_app && "$WH_OWNER_USER" == warehouse_owner ]] || refuse 'unexpected application or migration role'
    for key in WH_ADMIN_PASSWORD WH_APP_PASSWORD WH_OWNER_PASSWORD WH_MINIO_PASSWORD; do
        [[ "${!key}" =~ ^[a-f0-9]{64}$ ]] || refuse 'invalid generated credential'
    done
}

owned_resources() {
    local resource marker service resources
    resources=$("${DOCKER[@]}" ps -aq --filter "label=com.docker.compose.project=$WH_PROJECT") || refuse 'cannot inventory owned containers'
    for resource in $resources; do
        marker=$("${DOCKER[@]}" inspect -f '{{index .Config.Labels "warehouse.task"}}' "$resource")
        service=$("${DOCKER[@]}" inspect -f '{{index .Config.Labels "com.docker.compose.service"}}' "$resource")
        [[ "$marker" == "$WH_MARKER" && "$service" =~ ^(postgres|minio)$ ]] || refuse 'stale Compose container marker'
    done
    resources=$("${DOCKER[@]}" volume ls -q --filter "name=^${WH_PROJECT}_(postgres|minio)$") || refuse 'cannot inventory owned volumes'
    for resource in $resources; do
        marker=$("${DOCKER[@]}" volume inspect -f '{{index .Labels "warehouse.task"}}' "$resource")
        [[ "$marker" == "$WH_MARKER" ]] || refuse 'stale Compose volume marker'
    done
    resources=$("${DOCKER[@]}" network ls -q --filter "name=^${WH_PROJECT}_warehouse$") || refuse 'cannot inventory owned networks'
    for resource in $resources; do
        marker=$("${DOCKER[@]}" network inspect -f '{{index .Labels "warehouse.task"}}' "$resource")
        [[ "$marker" == "$WH_MARKER" ]] || refuse 'stale Compose network marker'
    done
}

sql_app() {
    compose exec -T postgres sh -c 'PGPASSWORD="$WH_APP_PASSWORD" PGCONNECT_TIMEOUT=5 exec psql -X -h 127.0.0.1 -U warehouse_app -d "$1" -v ON_ERROR_STOP=1 -At' sh "$1"
}

check_environment() {
    owned_resources
    local database result container service port binding
    for service in postgres minio; do
        container=$(compose ps -q "$service")
        [[ -n "$container" ]] || refuse "owned $service is not running; run test-environment.sh up"
        if [[ "$service" == postgres ]]; then port=5432; binding=25432; else port=9000; binding=29000; fi
        [[ $("${DOCKER[@]}" inspect -f "{{range (index .NetworkSettings.Ports \"$port/tcp\")}}{{.HostIp}}:{{.HostPort}}{{end}}" "$container") == "127.0.0.1:$binding" ]] || refuse 'unexpected Compose port binding'
    done
    for database in warehouse_test warehouse_e2e; do
        result=$(sql_app "$database" <<'SQL'
SELECT current_database() || '|' || current_user || '|' || rolsuper || '|' || rolbypassrls || '|' || rolcreaterole || '|' || rolcreatedb || '|' || pg_has_role(current_user, 'warehouse_owner', 'MEMBER') || '|' || pg_get_userbyid(datdba) || '|' || marker
FROM pg_roles, pg_database, warehouse_environment.identity
WHERE rolname = current_user AND datname = current_database();
SQL
        ) || refuse 'database identity query failed'
        [[ "$result" == "$database|warehouse_app|false|false|false|false|false|warehouse_owner|$WH_MARKER" ]] || refuse 'unsafe app role, owner separation, or database marker'
        result=$(sql_app "$database" <<'SQL'
SELECT NOT EXISTS (SELECT FROM pg_class WHERE relowner=(SELECT oid FROM pg_roles WHERE rolname=current_user))
AND NOT EXISTS (SELECT FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user))
AND NOT has_schema_privilege(current_user, 'public', 'CREATE')
AND (SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole FROM pg_roles WHERE rolname='warehouse_owner')
AND (SELECT count(*)=2 FROM pg_extension WHERE extname IN ('postgis','timescaledb'));
SQL
        ) || refuse 'role privilege query failed'
        [[ "$result" == t ]] || refuse 'app owns relations, inherits a role, has DDL privilege, or extensions are missing'
    done
    curl --noproxy '*' --fail --silent --show-error --max-time 5 "http://127.0.0.1:$WH_S3_PORT/minio/health/ready" >/dev/null || refuse 'MinIO readiness failed'
    printf 'PASS: warehouse_test + warehouse_e2e marker, owner separation, app NOSUPERUSER NOBYPASSRLS, local MinIO\n'
}

generate_environment() {
    [[ ! -e "$ENV_FILE" ]] || return 0
    umask 077
    local marker="warehouse-$TASK_ID-$(openssl rand -hex 16)"
    {
        printf 'WH_MARKER=%s\nWH_PROJECT=%s\n' "$marker" "$marker"
        printf 'WH_HOST=127.0.0.1\nWH_PG_PORT=25432\nWH_S3_PORT=29000\nWH_TEST_DB=warehouse_test\nWH_E2E_DB=warehouse_e2e\nWH_APP_USER=warehouse_app\nWH_OWNER_USER=warehouse_owner\n'
        printf 'WH_ADMIN_PASSWORD=%s\nWH_APP_PASSWORD=%s\nWH_OWNER_PASSWORD=%s\nWH_MINIO_PASSWORD=%s\n' "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" "$(openssl rand -hex 32)"
    } > "$ENV_FILE"
}

initialize_databases() {
    compose exec -T postgres psql -X -U warehouse_admin -d postgres -v ON_ERROR_STOP=1 >/dev/null <<SQL
SET log_min_error_statement = 'panic';
SELECT 'CREATE ROLE warehouse_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS PASSWORD ''$WH_OWNER_PASSWORD''' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_owner') \gexec
SELECT 'CREATE ROLE warehouse_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD ''$WH_APP_PASSWORD''' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') \gexec
SELECT 'CREATE DATABASE warehouse_test OWNER warehouse_owner' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='warehouse_test') \gexec
SELECT 'CREATE DATABASE warehouse_e2e OWNER warehouse_owner' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='warehouse_e2e') \gexec
SQL
    local database
    for database in warehouse_test warehouse_e2e; do
        compose exec -T postgres psql -X -U warehouse_admin -d "$database" -v ON_ERROR_STOP=1 >/dev/null <<SQL
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE SCHEMA IF NOT EXISTS warehouse_environment AUTHORIZATION warehouse_admin;
CREATE TABLE IF NOT EXISTS warehouse_environment.identity (marker text PRIMARY KEY);
INSERT INTO warehouse_environment.identity SELECT '$WH_MARKER' WHERE NOT EXISTS (SELECT FROM warehouse_environment.identity);
REVOKE ALL ON DATABASE $database FROM PUBLIC;
GRANT CONNECT ON DATABASE $database TO warehouse_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public, warehouse_environment TO warehouse_app;
GRANT SELECT ON warehouse_environment.identity TO warehouse_app;
ALTER DEFAULT PRIVILEGES FOR ROLE warehouse_owner IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO warehouse_app;
ALTER DEFAULT PRIVILEGES FOR ROLE warehouse_owner IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO warehouse_app;
SQL
    done
}

environment_main() {
    [[ $# == 1 && "$1" =~ ^(up|down|check)$ ]] || refuse 'usage: test-environment.sh up|down|check'
    reject_overrides
    [[ ! -L "$ROOT/.omo" && ! -L "$RUNTIME" ]] || refuse 'runtime directory must not be a symlink'
    mkdir -p "$RUNTIME"
    exec 9>"$RUNTIME/warehouse.lock"
    flock -w 30 9 || refuse 'warehouse QA is busy; retry after the owned command finishes'
    [[ "$1" != up ]] || generate_environment
    load_environment
    docker_local
    owned_resources
    case "$1" in
        up)
            trap 'status=$?; trap - EXIT INT TERM; if (( status != 0 )); then owned_resources && compose down --timeout 10; fi; exit "$status"' EXIT
            trap 'exit 130' INT
            trap 'exit 143' TERM
            compose up -d --wait --wait-timeout 150
            initialize_databases
            local attempt
            for attempt in {1..30}; do
                if curl --noproxy '*' -fsS --max-time 2 "http://127.0.0.1:$WH_S3_PORT/minio/health/ready" >/dev/null 2>&1; then break; fi
                sleep 1
            done
            check_environment
            ;;
        check) check_environment ;;
        down) compose down --timeout 10; printf 'PASS: removed only owned containers/network; volumes retained\n' ;;
    esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then environment_main "$@"; fi
