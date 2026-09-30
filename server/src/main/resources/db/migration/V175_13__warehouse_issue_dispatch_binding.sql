CREATE FUNCTION warehouse_assert_issue_dispatch(target_tenant uuid,target_issue uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE posting uuid; frozen jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT snapshot.snapshot::jsonb INTO frozen FROM inventory_document document JOIN inventory_issue_snapshot snapshot
        ON snapshot.tenant_id=document.tenant_id AND snapshot.id=document.id
        WHERE document.tenant_id=target_tenant AND document.id=target_issue AND document.state IN ('DISPATCHED','PART_RECEIVED','RECEIVED');
    IF NOT FOUND THEN RETURN; END IF;
    SELECT movement.id INTO posting FROM inventory_operation operation JOIN inventory_movement movement
        ON movement.tenant_id=operation.tenant_id AND movement.operation_id=operation.id
        WHERE operation.tenant_id=target_tenant AND operation.document_id=target_issue AND operation.business_action='DISPATCH'
            AND operation.document_revision=(frozen->>'revision')::bigint+1 AND movement.document_id=target_issue
            AND movement.kind='ISSUE' AND movement.state='APPLIED';
    IF posting IS NULL OR EXISTS (SELECT FROM inventory_issue_unpick WHERE tenant_id=target_tenant AND id=target_issue) OR
        (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=posting)<>
            2*(SELECT count(*) FROM inventory_issue_line WHERE tenant_id=target_tenant AND issue_id=target_issue) OR
        EXISTS (SELECT FROM inventory_issue_line binding JOIN inventory_document_line line ON line.tenant_id=binding.tenant_id AND line.id=binding.id
            WHERE binding.tenant_id=target_tenant AND binding.issue_id=target_issue AND (
                NOT EXISTS (SELECT FROM inventory_movement_leg leg WHERE leg.tenant_id=target_tenant AND leg.movement_id=posting
                    AND leg.document_line_id=binding.id AND leg.stock_identity_id=binding.stock_identity_id AND leg.quantity_base=binding.quantity_base
                    AND leg.sku_id=line.sku_id AND leg.base_unit=line.base_unit AND leg.lot_id IS NOT DISTINCT FROM line.lot_id
                    AND leg.direction='OUT' AND leg.status='AVAILABLE' AND leg.location_id=line.location_id
                    AND leg.custody_owner_id=line.custodian_id AND leg.custody_owner_kind=line.custodian_kind) OR
                NOT EXISTS (SELECT FROM inventory_movement_leg leg WHERE leg.tenant_id=target_tenant AND leg.movement_id=posting
                    AND leg.document_line_id=binding.id AND leg.stock_identity_id=binding.stock_identity_id AND leg.quantity_base=binding.quantity_base
                    AND leg.sku_id=line.sku_id AND leg.base_unit=line.base_unit AND leg.lot_id IS NOT DISTINCT FROM line.lot_id
                    AND leg.direction='IN' AND leg.status='IN_TRANSIT'))) THEN
        RAISE EXCEPTION 'warehouse_live_issue_binding_ck: dispatched issue requires its exact paired posting' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_issue_dispatch_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    PERFORM warehouse_assert_issue_dispatch(NEW.tenant_id,NEW.id);
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_issue_dispatch_posted AFTER UPDATE ON inventory_document
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_issue_dispatch_binding_guard();
