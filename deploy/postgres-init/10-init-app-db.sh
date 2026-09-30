#!/bin/bash
# Dijalankan SEKALI oleh entrypoint Postgres saat data dir masih kosong (first boot).
#
# Role pemilik dipakai Flyway; runtime warehouse_app tidak memiliki database,
# tabel, fungsi, membership role, atau hak CREATE schema. BYPASSRLS hanya untuk
# pemilik migrasi agar backfill/definer berjalan dengan boundary yang sudah diuji.
# Skrip ini hanya untuk volume baru; tidak mengubah kepemilikan database lama.
set -euo pipefail

[[ ${FTTH_DB_USER:?} == warehouse_app ]] || {
	echo 'FTTH_DB_USER must be warehouse_app for the reviewed warehouse grants' >&2
	exit 64
}
[[ ${FTTH_DB_OWNER_USER:?} != "$FTTH_DB_USER" ]] || {
	echo 'The migration owner and application role must differ' >&2
	exit 64
}

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" \
	-v app_user="$FTTH_DB_USER" -v app_password="${FTTH_DB_PASSWORD:?}" \
	-v owner_user="$FTTH_DB_OWNER_USER" -v owner_password="${FTTH_DB_OWNER_PASSWORD:?}" \
	-v app_database="${FTTH_DB_NAME:?}" <<'SQL'
	CREATE ROLE :"owner_user" LOGIN PASSWORD :'owner_password'
	    NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;
	CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password'
	    NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
	CREATE DATABASE :"app_database" OWNER :"owner_user";
	REVOKE ALL ON DATABASE :"app_database" FROM PUBLIC;
	GRANT CONNECT ON DATABASE :"app_database" TO :"app_user";
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$FTTH_DB_NAME" \
	-v app_user="$FTTH_DB_USER" -v owner_user="$FTTH_DB_OWNER_USER" <<'SQL'
	CREATE EXTENSION IF NOT EXISTS postgis;
	CREATE EXTENSION IF NOT EXISTS timescaledb;
	REVOKE CREATE ON SCHEMA public FROM PUBLIC;
	GRANT USAGE ON SCHEMA public TO :"app_user";
	ALTER DEFAULT PRIVILEGES FOR ROLE :"owner_user" IN SCHEMA public
	    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";
	ALTER DEFAULT PRIVILEGES FOR ROLE :"owner_user" IN SCHEMA public
	    GRANT USAGE, SELECT ON SEQUENCES TO :"app_user";
SQL
# Never grant ALL TABLES after Flyway: later migrations deliberately narrow access.
