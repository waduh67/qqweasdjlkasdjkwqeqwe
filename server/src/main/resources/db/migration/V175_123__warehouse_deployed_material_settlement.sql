-- Installation is the serial counterpart of measured material usage. Only an
-- immutable result bound to this issue and its actual APPLIED deployment counts;
-- subsequent title transfer, repair and removal do not create another use.
CREATE OR REPLACE FUNCTION warehouse_material_obligation_totals(target_tenant uuid,target_work_order uuid)
RETURNS TABLE(issue_line_id uuid,stock_identity_id uuid,base_unit varchar,issued_base bigint,used_base bigint,
    returned_base bigint,transferred_base bigint,accountable_base bigint,transit_base bigint,acknowledged_base bigint)
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    RETURN QUERY SELECT line.id,line.stock_identity_id,line.base_unit,line.quantity_base,
        used.amount::bigint,returned.amount::bigint,0::bigint,
        (line.quantity_base-used.amount-returned.amount)::bigint,
        (line.quantity_base-received.amount+pending.amount)::bigint,received.amount::bigint
    FROM inventory_document_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
    JOIN inventory_issue_line issue ON issue.tenant_id=line.tenant_id AND issue.id=line.id
    CROSS JOIN LATERAL (SELECT coalesce(sum(usage.used_base::numeric),0) amount FROM inventory_material_usage_line usage
        WHERE usage.tenant_id=line.tenant_id AND usage.issue_line_id=line.id) material_used
    CROSS JOIN LATERAL (SELECT count(*)::numeric amount
        FROM inventory_deployment_authorization permit
        JOIN inventory_deployment_result result ON result.tenant_id=permit.tenant_id AND result.authorization_id=permit.id
        JOIN inventory_movement movement ON movement.tenant_id=result.tenant_id AND movement.id=result.posting_id
            AND movement.operation_id=result.operation_id AND movement.kind='DEPLOY' AND movement.state='APPLIED'
        WHERE permit.tenant_id=line.tenant_id AND permit.issue_line_id=line.id
            AND permit.work_order_id=document.work_order_id AND permit.asset_id=line.stock_identity_id
            AND permit.purpose IN ('INSTALL','REPLACE') AND permit.consumed AND line.base_unit='EA'
            AND EXISTS(SELECT FROM inventory_movement_leg incoming
                WHERE incoming.tenant_id=movement.tenant_id AND incoming.movement_id=movement.id
                    AND incoming.direction='IN' AND incoming.stock_identity_id=permit.asset_id
                    AND incoming.quantity_base=1 AND incoming.base_unit='EA' AND incoming.status='CUSTOMER_INSTALLED')) deployed
    CROSS JOIN LATERAL (SELECT material_used.amount+deployed.amount amount) used
    CROSS JOIN LATERAL (SELECT coalesce(sum(receipt.accepted_base::numeric),0) amount FROM inventory_material_receipt_line receipt
        WHERE receipt.tenant_id=line.tenant_id AND receipt.issue_line_id=line.id) received
    CROSS JOIN LATERAL (SELECT coalesce(sum(residual.quantity_base::numeric),0) amount FROM inventory_material_residual residual
        JOIN inventory_material_residual_ack ack ON ack.tenant_id=residual.tenant_id AND ack.residual_id=residual.id
        WHERE residual.tenant_id=line.tenant_id AND residual.issue_line_id=line.id AND residual.purpose='RETURN') returned
    CROSS JOIN LATERAL (SELECT coalesce(sum(residual.quantity_base::numeric),0) amount FROM inventory_material_residual residual
        WHERE residual.tenant_id=line.tenant_id AND residual.issue_line_id=line.id AND NOT EXISTS (
            SELECT FROM inventory_material_residual_ack ack WHERE ack.tenant_id=residual.tenant_id AND ack.residual_id=residual.id)) pending
    WHERE document.tenant_id=target_tenant AND document.work_order_id=target_work_order AND document.kind='ISSUE'
        AND document.state IN ('DISPATCHED','PART_RECEIVED','RECEIVED') ORDER BY line.id;
END $$;

-- Count equality with caller-supplied JSON alone permits the empty/empty case.
-- Seal the exact source-key set for each new lifecycle, leaving old history intact.
CREATE FUNCTION warehouse_material_snapshot_set_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF EXISTS (
        (SELECT issue_line_id,stock_identity_id,base_unit FROM warehouse_material_obligation_totals(NEW.tenant_id,NEW.work_order_id)
         EXCEPT ALL
         SELECT issue_line_id,stock_identity_id,base_unit FROM inventory_material_obligation_snapshot
             WHERE tenant_id=NEW.tenant_id AND lifecycle_id=NEW.id)
        UNION ALL
        (SELECT issue_line_id,stock_identity_id,base_unit FROM inventory_material_obligation_snapshot
             WHERE tenant_id=NEW.tenant_id AND lifecycle_id=NEW.id
         EXCEPT ALL
         SELECT issue_line_id,stock_identity_id,base_unit FROM warehouse_material_obligation_totals(NEW.tenant_id,NEW.work_order_id))
    ) THEN RAISE EXCEPTION 'MATERIAL_LIFECYCLE_SOURCE_SET_INCOMPLETE' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_material_snapshot_set AFTER INSERT ON inventory_material_lifecycle
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_snapshot_set_guard();
