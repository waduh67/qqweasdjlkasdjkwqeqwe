CREATE TABLE b2b_client (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), created_month date NOT NULL,
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), UNIQUE(tenant_id,id),
    CHECK(extract(day FROM created_month)=1)
);
CREATE TABLE b2b_client_setting (
    tenant_id uuid NOT NULL, client_id uuid NOT NULL, effective_month date NOT NULL,
    name varchar(200) NOT NULL, address varchar(2000) NOT NULL, contact varchar(300) NOT NULL,
    technician_id uuid NOT NULL, target integer NOT NULL CHECK(target BETWEEN 1 AND 31), active boolean NOT NULL,
    PRIMARY KEY(tenant_id,client_id,effective_month),
    FOREIGN KEY(tenant_id,client_id) REFERENCES b2b_client(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id,technician_id) REFERENCES app_user(tenant_id,id),
    CHECK(extract(day FROM effective_month)=1)
);
CREATE TABLE b2b_client_month (
    tenant_id uuid NOT NULL, client_id uuid NOT NULL, month date NOT NULL,
    name varchar(200) NOT NULL, address varchar(2000) NOT NULL, contact varchar(300) NOT NULL,
    technician_id uuid NOT NULL, technician_name varchar(200) NOT NULL,
    target integer NOT NULL CHECK(target BETWEEN 1 AND 31), active boolean NOT NULL,
    PRIMARY KEY(tenant_id,client_id,month),
    FOREIGN KEY(tenant_id,client_id) REFERENCES b2b_client(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id,technician_id) REFERENCES app_user(tenant_id,id),
    CHECK(extract(day FROM month)=1)
);
CREATE TABLE b2b_visit (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, client_id uuid NOT NULL, month date NOT NULL,
    visit_date date NOT NULL, counted boolean NOT NULL, notes varchar(5000) NOT NULL,
    reporter_id uuid NOT NULL, reporter_name varchar(200) NOT NULL, created_at timestamptz NOT NULL,
    operation_key uuid NOT NULL, payload_hash varchar(64) NOT NULL,
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,operation_key),
    FOREIGN KEY(tenant_id,client_id,month) REFERENCES b2b_client_month(tenant_id,client_id,month),
    FOREIGN KEY(tenant_id,reporter_id) REFERENCES app_user(tenant_id,id),
    CHECK(date_trunc('month',visit_date)::date=month), CHECK(length(trim(notes))>0)
);
CREATE UNIQUE INDEX b2b_visit_counted_day ON b2b_visit(tenant_id,client_id,visit_date) WHERE counted;
CREATE INDEX b2b_visit_month_lookup ON b2b_visit(tenant_id,client_id,month,created_at);
CREATE TABLE b2b_visit_photo (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, visit_id uuid NOT NULL,
    object_key text NOT NULL, content_type varchar(100) NOT NULL, size_bytes bigint NOT NULL,
    sha256 varchar(64) NOT NULL, UNIQUE(tenant_id,id),
    FOREIGN KEY(tenant_id,visit_id) REFERENCES b2b_visit(tenant_id,id),
    CHECK(object_key LIKE tenant_id::text || '/b2b/%'), CHECK(size_bytes BETWEEN 1 AND 5242880)
);
CREATE TABLE b2b_client_command (
    tenant_id uuid NOT NULL REFERENCES tenant(id), operation_key uuid NOT NULL,
    actor_id uuid NOT NULL, canonical text NOT NULL, result jsonb NOT NULL,
    PRIMARY KEY(tenant_id,operation_key), FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['b2b_client','b2b_client_setting','b2b_client_month','b2b_visit','b2b_visit_photo','b2b_client_command'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',relation);
        END IF;
    END LOOP;
END $$;
CREATE FUNCTION b2b_append_only() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF TG_OP='DELETE' AND TG_TABLE_NAME='b2b_client_month' AND
       NOT EXISTS(SELECT FROM b2b_visit WHERE tenant_id=OLD.tenant_id AND client_id=OLD.client_id) THEN RETURN OLD; END IF;
    RAISE EXCEPTION 'B2B history is immutable' USING ERRCODE='23514';
END $$;
CREATE TRIGGER b2b_month_immutable BEFORE UPDATE OR DELETE ON b2b_client_month FOR EACH ROW EXECUTE FUNCTION b2b_append_only();
CREATE TRIGGER b2b_visit_immutable BEFORE UPDATE OR DELETE ON b2b_visit FOR EACH ROW EXECUTE FUNCTION b2b_append_only();
CREATE TRIGGER b2b_photo_immutable BEFORE UPDATE OR DELETE ON b2b_visit_photo FOR EACH ROW EXECUTE FUNCTION b2b_append_only();
CREATE TRIGGER b2b_command_immutable BEFORE UPDATE OR DELETE ON b2b_client_command FOR EACH ROW EXECUTE FUNCTION b2b_append_only();
