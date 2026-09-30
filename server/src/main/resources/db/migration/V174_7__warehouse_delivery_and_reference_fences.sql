CREATE TABLE fulfillment_warehouse_observation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
    operation_id uuid NOT NULL, document_id uuid NOT NULL, document_revision bigint NOT NULL CHECK (document_revision>=0),
    event_kind varchar(40) NOT NULL, work_order_id uuid, observed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_outbox(tenant_id,id),
    FOREIGN KEY (tenant_id,operation_id,document_id,document_revision)
        REFERENCES inventory_operation(tenant_id,id,document_id,document_revision)
);
CREATE INDEX fulfillment_warehouse_document_idx ON fulfillment_warehouse_observation(tenant_id,document_id,document_revision);
CREATE INDEX fulfillment_warehouse_work_order_idx ON fulfillment_warehouse_observation(tenant_id,work_order_id);
ALTER TABLE fulfillment_warehouse_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE fulfillment_warehouse_observation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fulfillment_warehouse_observation
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON fulfillment_warehouse_observation
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE TRIGGER warehouse_delivery_no_delete BEFORE DELETE ON inventory_outbox_delivery
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

ALTER TABLE work_order ADD COLUMN warehouse_revision bigint NOT NULL DEFAULT 0 CHECK (warehouse_revision>=0);
CREATE FUNCTION warehouse_work_order_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.id IS DISTINCT FROM OLD.id THEN
        RAISE EXCEPTION 'work order identity is immutable' USING ERRCODE='23514';
    END IF;
    NEW.warehouse_revision := OLD.warehouse_revision + 1;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_work_order_revision BEFORE UPDATE ON work_order
    FOR EACH ROW EXECUTE FUNCTION warehouse_work_order_revision();
CREATE FUNCTION warehouse_roster_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_tenant uuid; target_order uuid;
BEGIN
    IF TG_OP='UPDATE' AND (NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.work_order_id IS DISTINCT FROM OLD.work_order_id) THEN
        RAISE EXCEPTION 'roster identity is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN target_tenant:=OLD.tenant_id; target_order:=OLD.work_order_id;
    ELSE target_tenant:=NEW.tenant_id; target_order:=NEW.work_order_id; END IF;
    UPDATE work_order SET warehouse_revision=warehouse_revision+1 WHERE tenant_id=target_tenant AND id=target_order;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER warehouse_roster_revision BEFORE INSERT OR UPDATE OR DELETE ON work_order_assignee
    FOR EACH ROW EXECUTE FUNCTION warehouse_roster_revision();
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT ON fulfillment_warehouse_observation TO warehouse_app;
    END IF;
END $$;
