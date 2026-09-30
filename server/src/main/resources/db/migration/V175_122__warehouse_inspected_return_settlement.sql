-- Returned is a historical physical disposition. Settlement is a separate
-- fact backed by the accepted inspection of that exact acknowledged residual.
CREATE FUNCTION warehouse_material_settled_return_base(scope uuid, issue_line uuid) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE amount numeric;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT coalesce(sum(residual.quantity_base::numeric),0) INTO amount
    FROM inventory_material_residual residual
    JOIN inventory_material_residual_ack acknowledgement
        ON acknowledgement.tenant_id=residual.tenant_id AND acknowledgement.residual_id=residual.id
    JOIN inventory_return_case intake ON intake.tenant_id=residual.tenant_id AND intake.source_document_id=residual.id
        AND intake.origin='MATERIAL_RESIDUAL' AND intake.stock_identity_id=residual.transit_identity_id
    JOIN inventory_document document ON document.tenant_id=intake.tenant_id AND document.id=intake.id
        AND document.kind='RETURN' AND document.state='ACCEPTED'
    JOIN inventory_operation operation ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id
        AND operation.document_revision=document.revision AND operation.namespace='warehouse.return.inspect'
    WHERE residual.tenant_id=scope AND residual.issue_line_id=$2 AND residual.purpose='RETURN'
        AND operation.original_body::jsonb->>'state'='ACCEPTED'
        AND operation.original_body::jsonb->>'quantityBase'=residual.quantity_base::text
        AND EXISTS(SELECT FROM inventory_movement movement JOIN inventory_movement_leg incoming
            ON incoming.tenant_id=movement.tenant_id AND incoming.movement_id=movement.id
            WHERE movement.tenant_id=scope AND movement.operation_id=operation.id AND movement.state='APPLIED'
                AND movement.kind='RETURN' AND incoming.direction='IN' AND incoming.stock_identity_id=intake.stock_identity_id
                AND incoming.quantity_base=residual.quantity_base AND incoming.base_unit=residual.base_unit
                AND incoming.condition='SERVICEABLE' AND incoming.status='AVAILABLE' AND incoming.legal_owner='ISP');
    RETURN amount::bigint;
END $$;

ALTER TABLE inventory_material_obligation_snapshot ADD COLUMN settled_return_base bigint NOT NULL DEFAULT 0
    CHECK(settled_return_base>=0 AND settled_return_base<=returned_base);

CREATE FUNCTION warehouse_settled_return_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE lifecycle inventory_material_lifecycle; entry jsonb; expected bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT lifecycle FROM inventory_material_lifecycle WHERE tenant_id=NEW.tenant_id AND id=NEW.lifecycle_id;
    expected:=warehouse_material_settled_return_base(NEW.tenant_id,NEW.issue_line_id);
    SELECT value INTO STRICT entry FROM jsonb_array_elements(lifecycle.body::jsonb->'lines')
        WHERE value->>'issueLineId'=NEW.issue_line_id::text AND value->>'stockIdentityId'=NEW.stock_identity_id::text;
    IF NEW.settled_return_base<>expected OR entry->>'settledReturnBase' IS DISTINCT FROM nullif(expected,0)::text THEN
        RAISE EXCEPTION 'SETTLED_RETURN_REQUIRES_ACCEPTED_INSPECTION' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_settled_return_snapshot BEFORE INSERT ON inventory_material_obligation_snapshot
    FOR EACH ROW EXECUTE FUNCTION warehouse_settled_return_snapshot_guard();

CREATE FUNCTION warehouse_settled_return_lifecycle_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE outstanding numeric;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT coalesce(sum(accountable_base::numeric+returned_base::numeric-
        warehouse_material_settled_return_base(NEW.tenant_id,issue_line_id)),0) INTO outstanding
        FROM warehouse_material_obligation_totals(NEW.tenant_id,NEW.work_order_id);
    IF (NEW.body::jsonb->>'outstandingBase')::numeric IS DISTINCT FROM outstanding THEN
        RAISE EXCEPTION 'MATERIAL_OUTSTANDING_SNAPSHOT_MISMATCH' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_settled_return_lifecycle BEFORE INSERT ON inventory_material_lifecycle
    FOR EACH ROW EXECUTE FUNCTION warehouse_settled_return_lifecycle_guard();

DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_material_lifecycle_insert_guard()'::regprocedure);
    anchor:='WHERE accountable_base>0 OR returned_base>0';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'material initial close binding changed'; END IF;
    EXECUTE replace(definition,anchor,
        'WHERE accountable_base>0 OR returned_base>warehouse_material_settled_return_base(NEW.tenant_id,issue_line_id)');
    definition:=pg_get_functiondef('warehouse_assert_material_close(uuid,uuid)'::regprocedure);
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'material final close binding changed'; END IF;
    EXECUTE replace(definition,anchor,
        'WHERE accountable_base>0 OR returned_base>warehouse_material_settled_return_base(target_tenant,issue_line_id)');
END $$;
