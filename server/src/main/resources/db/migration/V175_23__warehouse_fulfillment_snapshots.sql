ALTER TABLE fulfillment_checkpoint ADD CONSTRAINT fulfillment_checkpoint_tenant_id_unique UNIQUE(tenant_id,id);
ALTER TABLE work_order ADD CONSTRAINT work_order_tenant_id_unique UNIQUE(tenant_id,id);

CREATE TABLE fulfillment_approval_snapshot (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    namespace varchar(120) NOT NULL,
    operation_key varchar(240) NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    work_order_id uuid NOT NULL,
    work_order_revision bigint NOT NULL CHECK(work_order_revision>=0),
    approved_by uuid NOT NULL,
    usage_id uuid NOT NULL,
    use_revision bigint NOT NULL CHECK(use_revision>0),
    plan_id uuid NOT NULL,
    material_mode varchar(32) NOT NULL CHECK(material_mode IN ('NONE','MATERIAL_REQUIRED')),
    required_effects varchar(32)[] NOT NULL CHECK(cardinality(required_effects)>=2),
    snapshot text NOT NULL,
    request_payload text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(tenant_id,id),
    UNIQUE(tenant_id,namespace,operation_key),
    UNIQUE(tenant_id,work_order_id,work_order_revision),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,approved_by) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,usage_id) REFERENCES inventory_usage_snapshot(tenant_id,id),
    FOREIGN KEY(tenant_id,plan_id) REFERENCES inventory_material_plan(tenant_id,id),
    FOREIGN KEY(tenant_id,namespace,operation_key) REFERENCES fulfillment_checkpoint(tenant_id,namespace,operation_key) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX fulfillment_approval_usage_idx ON fulfillment_approval_snapshot(tenant_id,usage_id);
CREATE INDEX fulfillment_approval_plan_idx ON fulfillment_approval_snapshot(tenant_id,plan_id);
CREATE INDEX fulfillment_approval_actor_idx ON fulfillment_approval_snapshot(tenant_id,approved_by);

CREATE TABLE inventory_material_settlement (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    usage_id uuid NOT NULL,
    use_revision bigint NOT NULL CHECK(use_revision>0),
    plan_id uuid NOT NULL,
    payload_hash varchar(64) NOT NULL,
    material_mode varchar(32) NOT NULL CHECK(material_mode IN ('NONE','MATERIAL_REQUIRED')),
    result varchar(32) NOT NULL CHECK(result IN ('NO_MATERIAL','VERIFIED')),
    body text NOT NULL,
    verified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id),
    FOREIGN KEY(tenant_id,id) REFERENCES fulfillment_approval_snapshot(tenant_id,id),
    FOREIGN KEY(tenant_id,usage_id) REFERENCES inventory_usage_snapshot(tenant_id,id),
    FOREIGN KEY(tenant_id,plan_id) REFERENCES inventory_material_plan(tenant_id,id),
    CHECK((material_mode='NONE')=(result='NO_MATERIAL'))
);
CREATE INDEX inventory_material_settlement_usage_idx ON inventory_material_settlement(tenant_id,usage_id,use_revision);
CREATE INDEX inventory_material_settlement_plan_idx ON inventory_material_settlement(tenant_id,plan_id);

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['fulfillment_approval_snapshot','inventory_material_settlement'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
END $$;
