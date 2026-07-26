#!/bin/bash
# Dijalankan SEKALI oleh entrypoint Postgres saat data dir masih kosong (first boot).
#
# Membuat role aplikasi NON-superuser + database + extension spasial/time-series.
# Role app sengaja bukan superuser dan tanpa BYPASSRLS supaya Row-Level Security
# benar-benar menegakkan isolasi antar-tenant (lapisan kedua di atas filter
# @TenantId Hibernate). Superuser 'postgres' hanya dipakai untuk memasang extension.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-SQL
	CREATE ROLE "${FTTH_DB_USER}" LOGIN PASSWORD '${FTTH_DB_PASSWORD}'
	    NOSUPERUSER NOCREATEROLE NOBYPASSRLS;
	CREATE DATABASE "${FTTH_DB_NAME}" OWNER "${FTTH_DB_USER}";
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${FTTH_DB_NAME}" <<-SQL
	CREATE EXTENSION IF NOT EXISTS postgis;
	CREATE EXTENSION IF NOT EXISTS timescaledb;
SQL
