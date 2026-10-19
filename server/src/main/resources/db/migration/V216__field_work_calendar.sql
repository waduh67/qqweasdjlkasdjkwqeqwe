CREATE TABLE field_operational_settings (
    tenant_id uuid PRIMARY KEY REFERENCES tenant(id), revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    saturday_off boolean NOT NULL DEFAULT true, fo_overdue_limit integer NOT NULL DEFAULT 4 CHECK(fo_overdue_limit BETWEEN 1 AND 1000),
    fo_point_target integer NOT NULL DEFAULT 15 CHECK(fo_point_target BETWEEN 1 AND 10000)
);
CREATE TABLE field_work_calendar_year (
    tenant_id uuid NOT NULL REFERENCES tenant(id), year integer NOT NULL CHECK(year BETWEEN 2000 AND 2100),
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), imported jsonb NOT NULL DEFAULT '{}'::jsonb,
    overrides jsonb NOT NULL DEFAULT '{}'::jsonb, refreshed_at timestamptz, PRIMARY KEY(tenant_id,year),
    CHECK(jsonb_typeof(imported)='object' AND jsonb_typeof(overrides)='object')
);
CREATE TABLE field_settings_command (
    tenant_id uuid NOT NULL REFERENCES tenant(id), operation_key uuid NOT NULL, actor_id uuid NOT NULL,
    canonical text NOT NULL, result jsonb NOT NULL, PRIMARY KEY(tenant_id,operation_key),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['field_operational_settings','field_work_calendar_year','field_settings_command'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO warehouse_app',relation);
        END IF;
    END LOOP;
END $$;
CREATE TRIGGER field_settings_command_immutable BEFORE UPDATE OR DELETE ON field_settings_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
