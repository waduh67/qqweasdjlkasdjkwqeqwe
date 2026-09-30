CREATE OR REPLACE FUNCTION warehouse_asset_delivery_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'ASSET_DELIVERY_HISTORY_REQUIRED' USING ERRCODE='23514'; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.state<>'PENDING' OR NEW.revision<>0 OR NEW.attempts<>0 OR NEW.failure_code IS NOT NULL THEN
            RAISE EXCEPTION 'ASSET_DELIVERY_INITIAL_STATE' USING ERRCODE='23514'; END IF;
    ELSE
        IF (NEW.tenant_id,NEW.operation_id) IS DISTINCT FROM (OLD.tenant_id,OLD.operation_id) OR NEW.revision<>OLD.revision+1
            OR NOT ((NEW.state='DELIVERING' AND NEW.attempts=OLD.attempts+1 AND NEW.failure_code IS NULL AND NEW.lease_token IS NOT NULL
                AND NEW.lease_until>clock_timestamp() AND (OLD.state IN ('PENDING','RECONCILIATION_REQUIRED')
                    OR (OLD.state='DELIVERING' AND OLD.lease_until<=clock_timestamp())))
                OR (OLD.state='DELIVERING' AND OLD.lease_until>clock_timestamp() AND NEW.state IN ('SUCCEEDED','RECONCILIATION_REQUIRED')
                    AND NEW.attempts=OLD.attempts AND NEW.lease_token IS NULL AND NEW.lease_until IS NULL
                    AND ((NEW.state='SUCCEEDED' AND NEW.failure_code IS NULL) OR (NEW.state='RECONCILIATION_REQUIRED' AND NEW.failure_code IS NOT NULL)))) THEN
            RAISE EXCEPTION 'ASSET_DELIVERY_TRANSITION_INVALID' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE VIEW inventory_asset_recovery_state WITH (security_invoker=true) AS
    SELECT removal.tenant_id,removal.assignment_id,removal.asset_id,removal.customer_id,removal.id removal_id,
        removal.legal_owner,removal.removed_at,'IN_RECOVERY'::text recovery_state,
        removal.legal_owner='ISP' recovery_required,
        obligation.handover_id
    FROM inventory_asset_removal removal LEFT JOIN inventory_asset_recovery_obligation obligation
        ON obligation.tenant_id=removal.tenant_id AND obligation.assignment_id=removal.assignment_id;

ALTER TABLE odp ADD CONSTRAINT odp_asset_relocation_tenant_key UNIQUE(tenant_id,id);
CREATE TABLE customer_asset_relocation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, customer_id uuid NOT NULL, assignment_id uuid NOT NULL,
    onu_id uuid NOT NULL, work_order_id uuid NOT NULL, actor_id uuid NOT NULL, operation_key text NOT NULL,
    payload_hash text NOT NULL, source_revision bigint NOT NULL, target_revision bigint NOT NULL,
    target_odp_id uuid NOT NULL, target_port integer NOT NULL CHECK(target_port>0), response text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,operation_key), UNIQUE(tenant_id,onu_id,source_revision),
    CHECK(source_revision>=0 AND target_revision=source_revision+1 AND length(operation_key) BETWEEN 1 AND 200),
    CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,onu_id) REFERENCES onu(tenant_id,id),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,target_odp_id) REFERENCES odp(tenant_id,id)
);
ALTER TABLE customer_asset_relocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_asset_relocation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_asset_relocation USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON customer_asset_relocation FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE FUNCTION warehouse_relocation_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original onu_topology_history; changed onu_topology_history;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO original FROM onu_topology_history WHERE tenant_id=NEW.tenant_id AND onu_id=NEW.onu_id AND revision=NEW.source_revision;
    SELECT * INTO changed FROM onu_topology_history WHERE tenant_id=NEW.tenant_id AND onu_id=NEW.onu_id AND revision=NEW.target_revision;
    IF original.onu_id IS NULL OR changed.onu_id IS NULL OR original.assignment_id<>NEW.assignment_id OR changed.assignment_id<>NEW.assignment_id
        OR (changed.snapshot->>'odpId')::uuid IS DISTINCT FROM NEW.target_odp_id
        OR (changed.snapshot->>'portNumber')::integer IS DISTINCT FROM NEW.target_port
        OR NOT EXISTS(SELECT FROM onu WHERE tenant_id=NEW.tenant_id AND id=NEW.onu_id AND customer_id=NEW.customer_id AND assignment_id=NEW.assignment_id)
        OR (NEW.response::jsonb->>'operationId')::uuid IS DISTINCT FROM NEW.id
        OR (NEW.response::jsonb->>'revision')::bigint IS DISTINCT FROM NEW.target_revision THEN
        RAISE EXCEPTION 'ASSET_RELOCATION_HISTORY_BINDING' USING ERRCODE='23514'; END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_relocation_final AFTER INSERT ON customer_asset_relocation DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION warehouse_relocation_final_guard();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT ON inventory_asset_recovery_state TO warehouse_app;
        GRANT SELECT,INSERT,UPDATE,DELETE ON customer_asset_relocation TO warehouse_app;
    END IF;
END $$;
