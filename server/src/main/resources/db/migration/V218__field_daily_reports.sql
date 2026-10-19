CREATE TABLE field_daily_report (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), technician_id uuid NOT NULL,
    report_date date NOT NULL, slot varchar(10) NOT NULL CHECK(slot IN ('MORNING','EVENING')),
    revision bigint NOT NULL CHECK(revision>=1), UNIQUE(tenant_id,id), UNIQUE(tenant_id,technician_id,report_date,slot),
    FOREIGN KEY(tenant_id,technician_id) REFERENCES app_user(tenant_id,id)
);
CREATE TABLE field_daily_report_revision (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, report_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>=1),
    technician_name varchar(200) NOT NULL, notes text NOT NULL CHECK(length(notes) BETWEEN 1 AND 5000),
    submitted_at timestamptz NOT NULL, operation_key uuid NOT NULL, payload_hash varchar(64) NOT NULL,
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,report_id,revision), UNIQUE(tenant_id,operation_key),
    FOREIGN KEY(tenant_id,report_id) REFERENCES field_daily_report(tenant_id,id)
);
CREATE TABLE field_daily_report_photo (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, revision_id uuid NOT NULL, object_key text NOT NULL,
    content_type varchar(20) NOT NULL CHECK(content_type IN ('image/jpeg','image/png')),
    size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 1 AND 5242880), sha256 varchar(64) NOT NULL,
    UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,revision_id) REFERENCES field_daily_report_revision(tenant_id,id),
    CHECK(object_key LIKE tenant_id::text||'/%')
);
CREATE INDEX field_report_history ON field_daily_report(tenant_id,report_date DESC,technician_id);
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['field_daily_report','field_daily_report_revision','field_daily_report_photo'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT ON %I TO warehouse_app',relation);
        END IF;
    END LOOP;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN GRANT UPDATE ON field_daily_report TO warehouse_app; END IF;
END $$;
CREATE TRIGGER field_report_revision_immutable BEFORE UPDATE OR DELETE ON field_daily_report_revision
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE TRIGGER field_report_photo_immutable BEFORE UPDATE OR DELETE ON field_daily_report_photo
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE FUNCTION field_report_next_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR
        NEW.technician_id IS DISTINCT FROM OLD.technician_id OR NEW.report_date IS DISTINCT FROM OLD.report_date OR
        NEW.slot IS DISTINCT FROM OLD.slot OR NEW.revision<>OLD.revision+1 THEN
        RAISE EXCEPTION 'report requires next immutable revision' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER field_report_next_revision BEFORE UPDATE OR DELETE ON field_daily_report
    FOR EACH ROW EXECUTE FUNCTION field_report_next_revision();
CREATE FUNCTION field_report_revision_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; current_revision bigint;
BEGIN
    IF TG_TABLE_NAME='field_daily_report' THEN target:=NEW.id; ELSE target:=NEW.report_id; END IF;
    SELECT revision INTO STRICT current_revision FROM field_daily_report WHERE tenant_id=NEW.tenant_id AND id=target;
    IF (SELECT count(*) FROM field_daily_report_revision WHERE tenant_id=NEW.tenant_id AND report_id=target)<>current_revision OR
        (SELECT max(revision) FROM field_daily_report_revision WHERE tenant_id=NEW.tenant_id AND report_id=target) IS DISTINCT FROM current_revision THEN
        RAISE EXCEPTION 'report differs from immutable revisions' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER field_report_revision_binding AFTER INSERT OR UPDATE ON field_daily_report
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION field_report_revision_binding();
CREATE CONSTRAINT TRIGGER field_report_history_binding AFTER INSERT ON field_daily_report_revision
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION field_report_revision_binding();
