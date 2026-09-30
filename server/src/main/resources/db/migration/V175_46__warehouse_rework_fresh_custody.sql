CREATE FUNCTION warehouse_assert_delta_custody(target_tenant uuid,target_usage uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE delta inventory_material_usage_delta%ROWTYPE; usage inventory_usage_snapshot%ROWTYPE;
    receipt inventory_material_receipt%ROWTYPE; accepted inventory_material_receipt_line%ROWTYPE;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO STRICT delta FROM inventory_material_usage_delta WHERE tenant_id=target_tenant AND id=target_usage;
    SELECT * INTO STRICT usage FROM inventory_usage_snapshot WHERE tenant_id=target_tenant AND id=target_usage;
    IF delta.rework_id IS NULL OR delta.source_usage_id IS NOT NULL THEN
        PERFORM warehouse_assert_residual_source(target_tenant,usage.work_order_id,delta.receipt_id,delta.issue_line_id,
            CASE WHEN delta.rework_id IS NULL THEN delta.previous_usage_id ELSE delta.source_usage_id END,delta.source_identity_id,delta.actor_id);
        RETURN;
    END IF;
    SELECT * INTO STRICT receipt FROM inventory_material_receipt WHERE tenant_id=target_tenant AND id=delta.receipt_id;
    SELECT * INTO STRICT accepted FROM inventory_material_receipt_line WHERE tenant_id=target_tenant AND receipt_id=delta.receipt_id AND issue_line_id=delta.issue_line_id;
    IF accepted.accepted_base<=0 OR receipt.snapshot::jsonb->'issue'->>'workOrderId' IS DISTINCT FROM usage.work_order_id::text OR
        EXISTS (SELECT FROM inventory_material_usage_line line JOIN inventory_usage_snapshot prior ON prior.tenant_id=line.tenant_id AND prior.id=line.usage_id
            WHERE line.tenant_id=target_tenant AND line.receipt_id=delta.receipt_id AND line.issue_line_id=delta.issue_line_id AND prior.use_revision<usage.use_revision) OR
        NOT EXISTS (WITH RECURSIVE ancestry(id,parent_segment_id) AS (
            SELECT id,parent_segment_id FROM inventory_segment WHERE tenant_id=target_tenant AND id=delta.source_identity_id
            UNION SELECT segment.id,segment.parent_segment_id FROM inventory_segment segment JOIN ancestry ON ancestry.parent_segment_id=segment.id
                WHERE segment.tenant_id=target_tenant) SELECT FROM ancestry WHERE id=accepted.accepted_identity_id) THEN
        RAISE EXCEPTION 'fresh rework use requires its exact initial acknowledged source' USING ERRCODE='23514';
    END IF;
    IF receipt.receiver_id<>delta.actor_id AND NOT EXISTS (
        WITH RECURSIVE ancestry(id,parent_segment_id) AS (
            SELECT id,parent_segment_id FROM inventory_segment WHERE tenant_id=target_tenant AND id=delta.source_identity_id
            UNION SELECT segment.id,segment.parent_segment_id FROM inventory_segment segment JOIN ancestry ON ancestry.parent_segment_id=segment.id
                WHERE segment.tenant_id=target_tenant)
        SELECT FROM inventory_material_residual residual JOIN inventory_material_residual_ack ack
            ON ack.tenant_id=residual.tenant_id AND ack.residual_id=residual.id JOIN ancestry ON ancestry.id=residual.transit_identity_id
        WHERE residual.tenant_id=target_tenant AND residual.receipt_id=delta.receipt_id AND residual.issue_line_id=delta.issue_line_id
            AND residual.purpose='HANDOVER' AND residual.receiver_id=delta.actor_id AND ack.actor_id=delta.actor_id) THEN
        RAISE EXCEPTION 'fresh rework use requires acknowledged actor custody' USING ERRCODE='23514';
    END IF;
END $$;

DO $$ DECLARE definition text; invocation text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_material_usage(uuid,uuid)'::regprocedure);
    invocation:='PERFORM warehouse_assert_residual_source(target_tenant,usage.work_order_id,delta.receipt_id,delta.issue_line_id,
            CASE WHEN delta.rework_id IS NULL THEN delta.previous_usage_id ELSE delta.source_usage_id END,delta.source_identity_id,delta.actor_id);';
    IF position(invocation IN definition)=0 THEN RAISE EXCEPTION 'expected delta custody invocation missing'; END IF;
    EXECUTE replace(definition,invocation,'PERFORM warehouse_assert_delta_custody(target_tenant,target_usage);');
END $$;
