-- Authority can expire before installation. Several fully verified permits may
-- reference one handover, but only one may ever create a durable installation.
CREATE TABLE inventory_rma_consumption (
    tenant_id uuid NOT NULL, handover_id uuid NOT NULL, authorization_id uuid NOT NULL,
    PRIMARY KEY(tenant_id,handover_id), UNIQUE(tenant_id,authorization_id),
    FOREIGN KEY(tenant_id,handover_id) REFERENCES inventory_rma_handover(tenant_id,id),
    FOREIGN KEY(tenant_id,authorization_id) REFERENCES inventory_deployment_result(tenant_id,authorization_id)
);
INSERT INTO inventory_rma_consumption(tenant_id,handover_id,authorization_id)
SELECT execution.tenant_id,execution.rma_handover_id,execution.authorization_id
FROM inventory_deployment_execution execution JOIN inventory_deployment_result result
    ON result.tenant_id=execution.tenant_id AND result.authorization_id=execution.authorization_id
WHERE execution.rma_handover_id IS NOT NULL;

ALTER TABLE inventory_rma_consumption ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_rma_consumption FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_rma_consumption
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_rma_consumption
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_rma_consumption TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_capture_rma_consumption() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE handover uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT rma_handover_id INTO handover FROM inventory_deployment_execution
        WHERE tenant_id=NEW.tenant_id AND authorization_id=NEW.authorization_id;
    IF handover IS NOT NULL THEN
        INSERT INTO inventory_rma_consumption(tenant_id,handover_id,authorization_id)
            VALUES (NEW.tenant_id,handover,NEW.authorization_id);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_aa_rma_consumption AFTER INSERT ON inventory_deployment_result
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_rma_consumption();

CREATE FUNCTION warehouse_rma_consumption_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NOT EXISTS(SELECT FROM inventory_deployment_execution execution
        JOIN inventory_deployment_authorization permit ON permit.tenant_id=execution.tenant_id AND permit.id=execution.authorization_id
        WHERE execution.tenant_id=NEW.tenant_id AND execution.authorization_id=NEW.authorization_id
            AND execution.rma_handover_id=NEW.handover_id AND permit.purpose='RETURN_CUSTOMER_RMA' AND permit.consumed) THEN
        RAISE EXCEPTION 'RMA_CONSUMPTION_REQUIRES_EXACT_RESULT' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_deployment_result(NEW.tenant_id,NEW.authorization_id);
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_rma_consumption_final AFTER INSERT ON inventory_rma_consumption
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_rma_consumption_guard();

DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_rma_execution(uuid,uuid)'::regprocedure);
    anchor:='source:=execution.source::jsonb; binding:=execution.binding::jsonb;';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'RMA execution result binding changed'; END IF;
    EXECUTE replace(definition,anchor,anchor||$patch$
    IF permit.consumed AND NOT EXISTS(SELECT FROM inventory_rma_consumption
        WHERE tenant_id=scope AND handover_id=handover.id AND authorization_id=permit.id) THEN
        RAISE EXCEPTION 'RMA_CONSUMPTION_REQUIRES_EXACT_RESULT' USING ERRCODE='23514'; END IF;
    $patch$);
END $$;
ALTER TABLE inventory_deployment_execution DROP CONSTRAINT warehouse_rma_execution_once;
CREATE INDEX warehouse_rma_execution_handover ON inventory_deployment_execution(tenant_id,rma_handover_id)
    WHERE rma_handover_id IS NOT NULL;
