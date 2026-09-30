#!/usr/bin/env bash
set +x
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/test-environment.sh"
umask 077
[[ $# == 2 && "$1" =~ ^(create|drop)$ && "$2" =~ ^warehouse_fixture_[a-f0-9]{32}$ ]] || refuse 'expected create|drop and a generated warehouse fixture database name'
[[ ${WAREHOUSE_QA:-} == true ]] || refuse 'database fixtures are only available in warehouse QA'
action=$1
database=$2
load_environment
docker_local
check_environment

if [[ "$action" == create ]]; then
    # A separate database is required: immutable historical migrations contain
    # explicit public-qualified function declarations. A sibling schema is unsafe.
    compose exec -T postgres psql -X -U warehouse_admin -d postgres -v ON_ERROR_STOP=1 >/dev/null <<SQL
CREATE DATABASE $database OWNER warehouse_owner;
REVOKE ALL ON DATABASE $database FROM PUBLIC;
GRANT CONNECT ON DATABASE $database TO warehouse_app;
SQL
    compose exec -T postgres psql -X -U warehouse_admin -d "$database" -v ON_ERROR_STOP=1 >/dev/null <<SQL
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE SCHEMA warehouse_environment AUTHORIZATION warehouse_admin;
CREATE TABLE warehouse_environment.identity (marker text PRIMARY KEY);
INSERT INTO warehouse_environment.identity VALUES ('$WH_MARKER');
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public, warehouse_environment TO warehouse_app;
GRANT SELECT ON warehouse_environment.identity TO warehouse_app;
ALTER DEFAULT PRIVILEGES FOR ROLE warehouse_owner IN SCHEMA public GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO warehouse_app;
ALTER DEFAULT PRIVILEGES FOR ROLE warehouse_owner IN SCHEMA public GRANT USAGE,SELECT ON SEQUENCES TO warehouse_app;
SQL
else
    identity=$(sql_app "$database" <<'SQL'
SELECT current_database()||'|'||pg_get_userbyid(datdba)||'|'||marker
FROM pg_database, warehouse_environment.identity WHERE datname=current_database();
SQL
    )
    [[ "$identity" == "$database|warehouse_owner|$WH_MARKER" ]] || refuse 'fixture database ownership or marker mismatch'
    # Only connections to this disposable, marker-verified fixture are closed.
    compose exec -T postgres psql -X -U warehouse_admin -d postgres -v ON_ERROR_STOP=1 >/dev/null <<SQL
DROP DATABASE $database WITH (FORCE);
SQL
fi
printf 'PASS: %s owned database fixture %s\n' "$action" "$database"
