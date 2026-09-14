CREATE FUNCTION warehouse_assert_deployment_result(scope uuid, permit_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE permit inventory_deployment_authorization; execution inventory_deployment_execution;
    outcome inventory_deployment_result; assignment inventory_asset_assignment; episode customer_asset_installation;
    incoming inventory_movement_leg; outgoing inventory_movement_leg; binding jsonb; source jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO execution FROM inventory_deployment_execution WHERE tenant_id=scope AND authorization_id=permit_id;
    IF NOT FOUND THEN RETURN; END IF;
    PERFORM warehouse_assert_deployment_authorization(scope,permit_id);
    SELECT * INTO STRICT permit FROM inventory_deployment_authorization WHERE tenant_id=scope AND id=permit_id;
    binding:=execution.binding::jsonb; source:=execution.source::jsonb;
    IF (binding->>'authorizationId')::uuid IS DISTINCT FROM permit.id OR
       (binding->>'operationId')::uuid IS DISTINCT FROM permit.operation_id OR
       (binding->>'tenantId')::uuid IS DISTINCT FROM scope OR
       (binding->>'actorId')::uuid IS DISTINCT FROM permit.actor_id OR
       (binding->>'assetId')::uuid IS DISTINCT FROM permit.asset_id OR
       (binding->>'stockIdentityId')::uuid IS DISTINCT FROM permit.asset_id OR
       (binding->>'issueLineId')::uuid IS DISTINCT FROM permit.issue_line_id OR
       (binding->>'workOrderId')::uuid IS DISTINCT FROM permit.work_order_id OR
       (binding->>'customerId')::uuid IS DISTINCT FROM permit.customer_id OR
       binding->>'purpose' IS DISTINCT FROM 'INSTALL' OR permit.purpose<>'INSTALL' OR
       binding->>'ownershipMode' IS DISTINCT FROM permit.ownership_mode OR
       (binding->>'assetRevision')::bigint IS DISTINCT FROM permit.expected_asset_revision OR
       (binding->>'issueRevision')::bigint IS DISTINCT FROM permit.expected_issue_revision OR
       (binding->>'authorityEpoch')::bigint IS DISTINCT FROM permit.authority_epoch OR
       (binding->>'cutoverEpoch')::bigint IS DISTINCT FROM permit.cutover_epoch OR
       (binding#>>'{revisions,workOrderRevision}')::bigint IS DISTINCT FROM permit.expected_work_order_revision OR
       (binding#>>'{revisions,planRevision}')::bigint IS DISTINCT FROM permit.expected_plan_revision OR
       (binding#>>'{revisions,useRevision}')::bigint IS DISTINCT FROM execution.use_revision OR
       (source->>'receiptId')::uuid IS DISTINCT FROM execution.receipt_id OR
       (source->>'receiptRevision')::bigint IS DISTINCT FROM execution.receipt_revision OR
       (source->>'planId')::uuid IS DISTINCT FROM execution.plan_id OR
       (source->>'workOrderId')::uuid IS DISTINCT FROM permit.work_order_id OR
       (source->>'customerId')::uuid IS DISTINCT FROM permit.customer_id OR
       (source->>'issueLineId')::uuid IS DISTINCT FROM permit.issue_line_id OR
       (source->>'assetRevision')::bigint IS DISTINCT FROM permit.expected_asset_revision THEN
        RAISE EXCEPTION 'DEPLOYMENT_EXECUTION_BINDING' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT FROM inventory_material_receipt receipt
        JOIN inventory_material_receipt_line line ON line.tenant_id=receipt.tenant_id AND line.receipt_id=receipt.id
        JOIN inventory_issue_snapshot issue ON issue.tenant_id=receipt.tenant_id AND issue.id=receipt.issue_id
        WHERE receipt.tenant_id=scope AND receipt.id=execution.receipt_id AND receipt.receiver_id=permit.actor_id
            AND receipt.issue_revision=execution.receipt_revision AND issue.plan_id=execution.plan_id
            AND line.issue_line_id=permit.issue_line_id AND line.accepted_identity_id=permit.asset_id AND line.accepted_base=1
            AND EXISTS(SELECT FROM jsonb_array_elements(receipt.snapshot::jsonb->'lines') item
                WHERE (item#>>'{selection,issueLineId}')::uuid=permit.issue_line_id AND item->'accepted'=source->'custody')) THEN
        RAISE EXCEPTION 'DEPLOYMENT_RECEIPT_BINDING' USING ERRCODE='23514';
    END IF;
    SELECT * INTO outcome FROM inventory_deployment_result WHERE tenant_id=scope AND authorization_id=permit_id;
    IF NOT permit.consumed THEN
        IF outcome.authorization_id IS NOT NULL OR EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=scope AND id=permit.operation_id)
            OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND id=permit.operation_id)
            OR EXISTS(SELECT FROM customer_asset_installation WHERE tenant_id=scope AND operation_id=permit.operation_id) THEN
            RAISE EXCEPTION 'DEPLOYMENT_PARTIAL_RESULT' USING ERRCODE='23514';
        END IF;
        RETURN;
    END IF;
    IF outcome.authorization_id IS NULL THEN RAISE EXCEPTION 'DEPLOYMENT_RESULT_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=scope AND id=outcome.assignment_id;
    SELECT * INTO episode FROM customer_asset_installation WHERE tenant_id=scope AND operation_id=permit.operation_id;
    IF (assignment.asset_id,assignment.customer_id,assignment.work_order_id,assignment.issue_line_id,assignment.actor_id,
        assignment.purpose,assignment.ownership_mode,assignment.legal_owner,assignment.id)
        IS DISTINCT FROM (permit.asset_id,permit.customer_id,permit.work_order_id,permit.issue_line_id,permit.actor_id,
        permit.purpose,permit.ownership_mode,'ISP'::text,permit.operation_id)
        OR assignment.ended_at IS NOT NULL OR outcome.operation_id<>permit.operation_id
        OR outcome.use_revision<>execution.use_revision+1 OR outcome.creates_onu IS DISTINCT FROM (source->>'createsOnu')::boolean
        OR (episode.customer_id,episode.asset_id,episode.assignment_id,episode.operation_id)
            IS DISTINCT FROM (permit.customer_id,permit.asset_id,assignment.id,permit.operation_id)
        OR (episode.onu_id IS NOT NULL) IS DISTINCT FROM outcome.creates_onu THEN
        RAISE EXCEPTION 'DEPLOYMENT_OWNER_RESULT_BINDING' USING ERRCODE='23514';
    END IF;
    IF outcome.creates_onu AND NOT EXISTS(SELECT FROM onu WHERE tenant_id=scope AND id=episode.onu_id
        AND asset_id=permit.asset_id AND assignment_id=assignment.id AND customer_id=permit.customer_id
        AND warehouse_admission='VERIFIED' AND started_at=assignment.started_at AND retired_at IS NULL) THEN
        RAISE EXCEPTION 'DEPLOYMENT_ONU_REQUIRED' USING ERRCODE='23514';
    END IF;
    IF (SELECT count(*) FROM onu WHERE tenant_id=scope AND assignment_id=assignment.id)<>(CASE WHEN outcome.creates_onu THEN 1 ELSE 0 END) THEN
        RAISE EXCEPTION 'DEPLOYMENT_ONU_CARDINALITY' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT FROM inventory_operation operation JOIN inventory_movement movement
        ON movement.tenant_id=operation.tenant_id AND movement.operation_id=operation.id
        WHERE operation.tenant_id=scope AND operation.id=permit.operation_id AND movement.id=outcome.posting_id
            AND movement.state='APPLIED' AND movement.kind='CONSUME' AND operation.namespace='warehouse.deployment.consume'
            AND operation.operation_key=outcome.consume_key AND operation.payload_hash=outcome.consume_hash
            AND operation.actor_id=permit.actor_id AND operation.resource_id=permit.id AND operation.original_body=outcome.result
            AND operation.authority_epoch=permit.authority_epoch AND operation.cutover_epoch=permit.cutover_epoch)
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=permit.operation_id)<>1
        OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=outcome.posting_id)<>2 THEN
        RAISE EXCEPTION 'DEPLOYMENT_POSTING_BINDING' USING ERRCODE='23514';
    END IF;
    SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=outcome.posting_id AND direction='IN';
    SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=outcome.posting_id AND direction='OUT';
    IF (incoming.stock_identity_id,incoming.quantity_base,incoming.base_unit,incoming.custody_owner_id,incoming.custody_owner_kind,incoming.status)
        IS DISTINCT FROM (permit.asset_id,1::bigint,'EA'::text,permit.customer_id,'CUSTOMER'::character varying,'CUSTOMER_INSTALLED'::character varying)
        OR (outgoing.stock_identity_id,outgoing.quantity_base,outgoing.custody_owner_id,outgoing.custody_owner_kind,outgoing.status)
        IS DISTINCT FROM (permit.asset_id,1::bigint,permit.actor_id,'TECHNICIAN'::character varying,'ISSUED'::character varying)
        OR NOT EXISTS(SELECT FROM inventory_document_line WHERE tenant_id=scope AND id=incoming.document_line_id AND source_line_id=permit.issue_line_id)
        OR incoming.document_line_id<>outgoing.document_line_id OR incoming.legal_owner<>'ISP' OR incoming.condition<>'SERVICEABLE' THEN
        RAISE EXCEPTION 'DEPLOYMENT_PHYSICAL_LEGS' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=permit.asset_id
        AND location_id=incoming.location_id AND custody_owner_id=permit.customer_id AND custody_owner_kind='CUSTOMER'
        AND status='CUSTOMER_INSTALLED' AND quantity_base=1 AND condition='SERVICEABLE' AND legal_owner='ISP')
        OR (SELECT count(*) FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=permit.asset_id AND quantity_base>0)<>1
        OR NOT EXISTS(SELECT FROM inventory_serialized_asset WHERE tenant_id=scope AND id=permit.asset_id
            AND location_id=incoming.location_id AND custody_owner_id=permit.customer_id AND custody_owner_kind='CUSTOMER'
            AND status='CUSTOMER_INSTALLED' AND legal_owner='ISP' AND condition='SERVICEABLE') THEN
        RAISE EXCEPTION 'DEPLOYMENT_INSTALLED_POSITION' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_deployment_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_scope uuid; identity uuid; permit_id uuid; row_data jsonb;
BEGIN
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    target_scope:=(row_data->>'tenant_id')::uuid;
    PERFORM warehouse_assert_deferred_scope(target_scope);
    IF TG_TABLE_NAME IN ('inventory_deployment_execution','inventory_deployment_result') THEN
        PERFORM warehouse_assert_deployment_result(target_scope,(row_data->>'authorization_id')::uuid);
    ELSIF TG_TABLE_NAME='inventory_deployment_authorization' THEN
        PERFORM warehouse_assert_deployment_result(target_scope,(row_data->>'id')::uuid);
    ELSE
        identity:=CASE TG_TABLE_NAME
            WHEN 'inventory_serialized_asset' THEN (row_data->>'id')::uuid
            WHEN 'inventory_balance_projection' THEN (row_data->>'stock_identity_id')::uuid
            WHEN 'inventory_movement_leg' THEN (row_data->>'stock_identity_id')::uuid
            ELSE (row_data->>'asset_id')::uuid END;
        FOR permit_id IN SELECT permit.id FROM inventory_deployment_authorization permit
            JOIN inventory_deployment_execution execution ON execution.tenant_id=permit.tenant_id AND execution.authorization_id=permit.id
            WHERE permit.tenant_id=target_scope AND permit.asset_id=identity LOOP
            PERFORM warehouse_assert_deployment_result(target_scope,permit_id);
        END LOOP;
        IF row_data->>'status'='CUSTOMER_INSTALLED' AND NOT EXISTS(SELECT FROM inventory_deployment_authorization permit
            JOIN inventory_deployment_result result ON result.tenant_id=permit.tenant_id AND result.authorization_id=permit.id
            WHERE permit.tenant_id=target_scope AND permit.asset_id=identity AND permit.consumed) THEN
            RAISE EXCEPTION 'DEPLOYMENT_INSTALLED_REQUIRES_AUTHORIZATION' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_deployment_execution','inventory_deployment_result','inventory_deployment_authorization',
        'inventory_asset_assignment','customer_asset_installation','onu','inventory_serialized_asset','inventory_balance_projection','inventory_movement_leg'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_deployment_final AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_deployment_final_guard()',table_name);
    END LOOP;
END $$;
