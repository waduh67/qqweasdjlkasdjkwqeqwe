CREATE TABLE inventory_material_template (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    work_type varchar(32) NOT NULL, action varchar(32) NOT NULL,
    template_revision bigint NOT NULL CHECK(template_revision>0),
    actor_id uuid NOT NULL, snapshot text NOT NULL CHECK(jsonb_typeof(snapshot::jsonb)='array' AND jsonb_array_length(snapshot::jsonb) BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,work_type,action,template_revision),
    UNIQUE(tenant_id,work_type,action,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE TABLE inventory_material_template_current (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    work_type varchar(32) NOT NULL, action varchar(32) NOT NULL, template_id uuid NOT NULL,
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,work_type,action),
    FOREIGN KEY(tenant_id,work_type,action,template_id) REFERENCES inventory_material_template(tenant_id,work_type,action,id)
);
CREATE TABLE inventory_material_plan_snapshot (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), template_id uuid,
    snapshot text NOT NULL CHECK(jsonb_typeof(snapshot::jsonb)='object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(tenant_id,id),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_material_plan(tenant_id,id),
    FOREIGN KEY(tenant_id,template_id) REFERENCES inventory_material_template(tenant_id,id)
);
CREATE TABLE inventory_material_submission (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), document_id uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,document_id),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_material_plan_snapshot(tenant_id,id),
    FOREIGN KEY(tenant_id,document_id) REFERENCES inventory_document(tenant_id,id)
);
CREATE TABLE inventory_material_command (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), resource_id uuid NOT NULL,
    action varchar(32) NOT NULL, operation_key varchar(240) NOT NULL CHECK(btrim(operation_key)<>''),
    actor_id uuid NOT NULL, authority_epoch bigint NOT NULL CHECK(authority_epoch>=0), cutover_epoch bigint NOT NULL CHECK(cutover_epoch>=0),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'), canonical_payload text NOT NULL,
    document_id uuid NOT NULL, document_revision bigint NOT NULL CHECK(document_revision>=0),
    original_body text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,action,operation_key),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE INDEX inventory_material_command_history ON inventory_material_command(tenant_id,resource_id,created_at,id);

CREATE FUNCTION warehouse_sealed_material_plan_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_tenant uuid; plan uuid;
BEGIN
    IF TG_TABLE_NAME='inventory_material_plan' THEN
        owner_tenant:=OLD.tenant_id; plan:=OLD.id;
    ELSIF TG_OP='INSERT' THEN
        owner_tenant:=NEW.tenant_id; plan:=NEW.plan_id;
    ELSE
        owner_tenant:=OLD.tenant_id; plan:=OLD.plan_id;
    END IF;
    IF EXISTS(SELECT FROM inventory_material_plan_snapshot WHERE tenant_id=owner_tenant AND id=plan) THEN
        IF TG_TABLE_NAME='inventory_material_plan' AND TG_OP='UPDATE' THEN
            IF OLD.state='DRAFT' AND NEW.state='SUBMITTED' AND
                (to_jsonb(NEW)-ARRAY['state','submitted_at','revision','updated_at']) =
                (to_jsonb(OLD)-ARRAY['state','submitted_at','revision','updated_at']) THEN RETURN NEW; END IF;
        END IF;
        RAISE EXCEPTION 'material plan snapshot is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_sealed_plan BEFORE UPDATE OR DELETE ON inventory_material_plan
    FOR EACH ROW EXECUTE FUNCTION warehouse_sealed_material_plan_guard();
CREATE TRIGGER warehouse_sealed_plan_line BEFORE INSERT OR UPDATE OR DELETE ON inventory_material_plan_line
    FOR EACH ROW EXECUTE FUNCTION warehouse_sealed_material_plan_guard();

CREATE FUNCTION warehouse_material_submission_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT FROM inventory_material_plan plan WHERE plan.tenant_id=NEW.tenant_id AND plan.id=NEW.id AND plan.state='SUBMITTED'
        AND ((plan.material_mode='NONE' AND NEW.document_id IS NULL) OR (plan.material_mode='MATERIAL_REQUIRED' AND EXISTS(
            SELECT FROM inventory_document document WHERE document.tenant_id=plan.tenant_id AND document.id=NEW.document_id
                AND document.kind='DEMAND' AND document.state='SUBMITTED' AND document.work_order_id=plan.work_order_id
                AND document.plan_revision=plan.plan_revision AND document.work_order_revision=plan.work_order_revision)))) THEN
        RAISE EXCEPTION 'material submission must bind the submitted plan' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_material_submission BEFORE INSERT ON inventory_material_submission
    FOR EACH ROW EXECUTE FUNCTION warehouse_material_submission_guard();

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_material_template','inventory_material_template_current',
        'inventory_material_plan_snapshot','inventory_material_submission','inventory_material_command'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        IF table_name='inventory_material_template_current' THEN
            EXECUTE format('CREATE TRIGGER warehouse_revision BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard()',table_name);
        ELSE
            EXECUTE format('CREATE TRIGGER warehouse_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        END IF;
    END LOOP;
END $$;
