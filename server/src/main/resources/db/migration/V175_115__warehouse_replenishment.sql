DO $$ DECLARE constraint_name text;
BEGIN
    SELECT conname INTO STRICT constraint_name FROM pg_constraint
        WHERE conrelid='inventory_replenishment_rule'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%maximum_base > minimum_base%';
    EXECUTE format('ALTER TABLE inventory_replenishment_rule DROP CONSTRAINT %I',constraint_name);
END $$;
ALTER TABLE inventory_replenishment_rule
    ADD CONSTRAINT warehouse_replenishment_minmax CHECK(maximum_base>=minimum_base),
    ADD COLUMN target_base bigint,
    ADD COLUMN package_multiple_base bigint NOT NULL DEFAULT 1 CHECK(package_multiple_base>0),
    ADD COLUMN lead_time_days integer NOT NULL DEFAULT 0 CHECK(lead_time_days BETWEEN 0 AND 3650),
    ADD CONSTRAINT warehouse_replenishment_target CHECK(target_base BETWEEN minimum_base AND maximum_base);

ALTER TABLE inventory_replenishment_request
    ADD COLUMN rule_snapshot jsonb,
    ADD COLUMN position_snapshot jsonb,
    ADD COLUMN accepted_at timestamptz,
    ADD COLUMN accepted_by uuid,
    ADD COLUMN receiving_line_id uuid,
    ADD COLUMN receiving_revision bigint CHECK(receiving_revision>=0),
    ADD CONSTRAINT warehouse_replenishment_actor FOREIGN KEY(tenant_id,accepted_by) REFERENCES app_user(tenant_id,id),
    ADD CONSTRAINT warehouse_replenishment_line FOREIGN KEY(tenant_id,receiving_line_id) REFERENCES inventory_document_line(tenant_id,id),
    ADD CONSTRAINT warehouse_replenishment_acceptance CHECK((accepted_at IS NULL)=(accepted_by IS NULL)),
    ADD CONSTRAINT warehouse_replenishment_reference CHECK(receiving_line_id IS NULL OR
        (source_document_id IS NOT NULL AND receiving_revision IS NOT NULL AND accepted_at IS NOT NULL));
CREATE UNIQUE INDEX warehouse_replenishment_receiving_uq ON inventory_replenishment_request(tenant_id,receiving_line_id)
    WHERE receiving_line_id IS NOT NULL;

CREATE TABLE inventory_replenishment_operation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    namespace varchar(80) NOT NULL, operation_key varchar(240) NOT NULL,
    actor_id uuid NOT NULL, rule_id uuid NOT NULL, request_id uuid,
    revision bigint NOT NULL CHECK(revision>=0), payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    original_body text NOT NULL, cutover_epoch bigint NOT NULL CHECK(cutover_epoch>=0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,namespace,operation_key),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,rule_id) REFERENCES inventory_replenishment_rule(tenant_id,id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_replenishment_request(tenant_id,id)
);
CREATE INDEX warehouse_replenishment_history_idx ON inventory_replenishment_operation(tenant_id,rule_id,created_at,id);
ALTER TABLE inventory_replenishment_operation ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_replenishment_operation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_replenishment_operation
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_replenishment_operation_immutable BEFORE UPDATE OR DELETE ON inventory_replenishment_operation
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE FUNCTION warehouse_replenishment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_replenishment_rule' THEN
        IF TG_OP='UPDATE' AND (NEW.tenant_id,NEW.id,NEW.sku_id,NEW.location_id,NEW.base_unit,NEW.created_at)
            IS DISTINCT FROM (OLD.tenant_id,OLD.id,OLD.sku_id,OLD.location_id,OLD.base_unit,OLD.created_at) THEN
            RAISE EXCEPTION 'replenishment rule identity is immutable' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.rule_snapshot IS NULL OR NEW.position_snapshot IS NULL THEN
        RAISE EXCEPTION 'replenishment snapshots required' USING ERRCODE='23514';
    END IF;
    IF TG_OP='UPDATE' THEN
        IF (NEW.tenant_id,NEW.id,NEW.rule_id,NEW.business_key,NEW.created_at)
            IS DISTINCT FROM (OLD.tenant_id,OLD.id,OLD.rule_id,OLD.business_key,OLD.created_at) OR OLD.state<>'PENDING' THEN
            RAISE EXCEPTION 'replenishment window is immutable or terminal' USING ERRCODE='23514';
        END IF;
        IF OLD.accepted_at IS NOT NULL AND
            (NEW.rule_revision,NEW.quantity_base,NEW.rule_snapshot,NEW.position_snapshot,NEW.accepted_at,NEW.accepted_by)
            IS DISTINCT FROM (OLD.rule_revision,OLD.quantity_base,OLD.rule_snapshot,OLD.position_snapshot,OLD.accepted_at,OLD.accepted_by) THEN
            RAISE EXCEPTION 'accepted replenishment snapshot is immutable' USING ERRCODE='23514';
        END IF;
        IF OLD.receiving_line_id IS NOT NULL AND (NEW.source_document_id,NEW.receiving_line_id,NEW.receiving_revision)
            IS DISTINCT FROM (OLD.source_document_id,OLD.receiving_line_id,OLD.receiving_revision) THEN
            RAISE EXCEPTION 'receiving reference is immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_replenishment_rule_guard BEFORE INSERT OR UPDATE ON inventory_replenishment_rule
    FOR EACH ROW EXECUTE FUNCTION warehouse_replenishment_guard();
CREATE TRIGGER warehouse_replenishment_request_guard BEFORE INSERT OR UPDATE ON inventory_replenishment_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_replenishment_guard();
CREATE TABLE inventory_replenishment_scan (
    tenant_id uuid PRIMARY KEY REFERENCES tenant(id), last_rule_id uuid,
    FOREIGN KEY(tenant_id,last_rule_id) REFERENCES inventory_replenishment_rule(tenant_id,id)
);
ALTER TABLE inventory_replenishment_scan ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_replenishment_scan FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_replenishment_scan
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT ON inventory_replenishment_operation TO warehouse_app;
        GRANT SELECT,INSERT,UPDATE ON inventory_replenishment_scan TO warehouse_app;
    END IF;
END $$;
