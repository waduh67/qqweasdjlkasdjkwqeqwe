\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(1731792026);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='173'
        AND script='V173__radius_server_cluster_support.sql' AND checksum=1143546403 AND success)
        OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE installed_rank >
            (SELECT installed_rank FROM flyway_schema_history WHERE version='173'))
        OR to_regclass('public.radius_server') IS NULL
        OR to_regclass('public.tenant_radius_server') IS NULL
        OR to_regclass('public.inventory_sku') IS NOT NULL THEN
        RAISE EXCEPTION 'Expected the reviewed legacy V173 RADIUS database';
    END IF;
END $$;

-- RADIUS SQL moved unchanged to V179 apart from its version comment.
UPDATE flyway_schema_history SET version='179', script='V179__radius_server_cluster_support.sql',
    checksum=-263454885 WHERE version='173';

SELECT format('ALTER TABLE %I.%I OWNER TO warehouse_owner', n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relkind IN ('r','p')
    AND c.relowner=(SELECT oid FROM pg_roles WHERE rolname='ftth') \gexec
SELECT format('ALTER SEQUENCE %I.%I OWNER TO warehouse_owner', n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND c.relkind='S'
    AND c.relowner=(SELECT oid FROM pg_roles WHERE rolname='ftth') \gexec
SELECT format('ALTER FUNCTION %s OWNER TO warehouse_owner', p.oid::regprocedure)
FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
WHERE n.nspname='public' AND p.proowner=(SELECT oid FROM pg_roles WHERE rolname='ftth') \gexec
SELECT format('ALTER DATABASE %I OWNER TO warehouse_owner', current_database()) \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO warehouse_app;
SELECT format('GRANT CONNECT ON DATABASE %I TO warehouse_app', current_database()) \gexec
-- Establish the legacy grants BEFORE the new migrations narrow warehouse access.
GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO warehouse_app;
GRANT USAGE,SELECT ON ALL SEQUENCES IN SCHEMA public TO warehouse_app;
ALTER DEFAULT PRIVILEGES FOR ROLE warehouse_owner IN SCHEMA public
    GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO warehouse_app;
ALTER DEFAULT PRIVILEGES FOR ROLE warehouse_owner IN SCHEMA public
    GRANT USAGE,SELECT ON SEQUENCES TO warehouse_app;
COMMIT;
