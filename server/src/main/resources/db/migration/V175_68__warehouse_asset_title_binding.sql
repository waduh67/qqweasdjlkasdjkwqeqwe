CREATE FUNCTION warehouse_assert_asset_handover(scope uuid, handover_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE handover inventory_asset_handover; acceptance inventory_asset_acceptance; assignment inventory_asset_assignment;
    operation inventory_operation; incoming inventory_movement_leg; outgoing inventory_movement_leg;
    original jsonb; snapshot jsonb; target_movement uuid; expected_owner text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO handover FROM inventory_asset_handover WHERE tenant_id=scope AND id=$2;
    IF NOT FOUND THEN RAISE EXCEPTION 'ASSET_HANDOVER_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO acceptance FROM inventory_asset_acceptance WHERE tenant_id=scope AND inventory_asset_acceptance.handover_id=$2;
    SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=scope AND id=handover.assignment_id;
    SELECT history.snapshot INTO original FROM inventory_asset_assignment_history history
        WHERE history.tenant_id=scope AND history.assignment_id=handover.assignment_id AND history.revision=handover.assignment_revision;
    expected_owner:=CASE handover.ownership_mode WHEN 'LOAN' THEN 'ISP' ELSE 'CUSTOMER' END;
    IF acceptance.handover_id IS NULL OR assignment.id IS NULL OR original IS NULL
        OR handover.warehouse_admission<>'VERIFIED' OR assignment.warehouse_admission<>'VERIFIED'
        OR assignment.ended_at IS NOT NULL OR assignment.revision<>handover.assignment_revision+1
        OR (assignment.asset_id,assignment.customer_id,assignment.work_order_id,assignment.ownership_mode,assignment.legal_owner)
            IS DISTINCT FROM (handover.asset_id,handover.customer_id,handover.work_order_id,handover.ownership_mode,expected_owner)
        OR original->>'legal_owner' IS DISTINCT FROM 'ISP'
        OR (to_jsonb(assignment)-ARRAY['legal_owner','revision']) IS DISTINCT FROM (original-ARRAY['legal_owner','revision']) THEN
        RAISE EXCEPTION 'ASSET_HANDOVER_ASSIGNMENT_BINDING' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT FROM inventory_deployment_result result JOIN inventory_deployment_authorization permit
        ON permit.tenant_id=result.tenant_id AND permit.id=result.authorization_id
        WHERE result.tenant_id=scope AND result.authorization_id=acceptance.authorization_id AND result.assignment_id=assignment.id
        AND permit.consumed AND permit.asset_id=handover.asset_id AND permit.customer_id=handover.customer_id
        AND permit.work_order_id=handover.work_order_id AND permit.actor_id=handover.actor_id AND permit.ownership_mode=handover.ownership_mode)
        OR NOT EXISTS(SELECT FROM wo_signature signature JOIN evidence_object_registry registry
            ON registry.tenant_id=signature.tenant_id AND registry.revision_id=signature.id
            WHERE signature.tenant_id=scope AND signature.id=handover.evidence_id AND signature.work_order_id=handover.work_order_id
            AND signature.storage_key=handover.evidence_reference AND signature.sha256=acceptance.evidence_digest
            AND registry.expected_sha256=acceptance.evidence_digest AND registry.object_key=handover.evidence_reference
            AND signature.receipt_at<=handover.accepted_at)
        OR handover.accepted_at<assignment.started_at THEN
        RAISE EXCEPTION 'ASSET_HANDOVER_SOURCE_BINDING' USING ERRCODE='23514';
    END IF;
    snapshot:=acceptance.snapshot::jsonb;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=scope AND id=acceptance.operation_id;
    IF operation.id IS NULL OR operation.namespace<>'warehouse.asset.handover' OR operation.business_action<>'ACCEPT'
        OR operation.actor_id<>handover.actor_id OR operation.resource_id<>assignment.id
        OR operation.operation_key<>acceptance.operation_key OR operation.payload_hash<>acceptance.payload_hash
        OR operation.authority_epoch<>acceptance.authority_epoch OR operation.cutover_epoch<>acceptance.cutover_epoch
        OR operation.document_id<>acceptance.operation_id OR operation.document_revision<>1 OR operation.original_status<>200
        OR snapshot->'assignment' IS DISTINCT FROM operation.original_body::jsonb
        OR (snapshot->>'id')::uuid IS DISTINCT FROM handover.id
        OR (snapshot->>'authorizationId')::uuid IS DISTINCT FROM acceptance.authorization_id
        OR (snapshot->>'actorId')::uuid IS DISTINCT FROM handover.actor_id
        OR (snapshot->>'acceptedAt')::timestamptz IS DISTINCT FROM handover.accepted_at
        OR snapshot#>>'{signature,digest}' IS DISTINCT FROM acceptance.evidence_digest
        OR snapshot#>>'{signature,reference}' IS DISTINCT FROM handover.evidence_reference
        OR (snapshot#>>'{signature,id}')::uuid IS DISTINCT FROM handover.evidence_id
        OR (snapshot#>>'{assignment,assignmentId}')::uuid IS DISTINCT FROM assignment.id
        OR (snapshot#>>'{assignment,revision}')::bigint IS DISTINCT FROM assignment.revision
        OR snapshot#>>'{assignment,legalOwner}' IS DISTINCT FROM assignment.legal_owner
        OR snapshot#>>'{assignment,handoverState}' IS DISTINCT FROM 'ACCEPTED'
        OR NOT EXISTS(SELECT FROM inventory_document WHERE tenant_id=scope AND id=acceptance.operation_id
            AND kind='ASSET_HANDOVER' AND state='POSTED' AND revision=1 AND actor_id=handover.actor_id
            AND customer_id=handover.customer_id AND work_order_id=handover.work_order_id
            AND work_order_revision=acceptance.work_order_revision AND cutover_epoch=acceptance.cutover_epoch
            AND authority_epoch=acceptance.authority_epoch) THEN
        RAISE EXCEPTION 'ASSET_HANDOVER_OPERATION_BINDING' USING ERRCODE='23514';
    END IF;
    IF handover.ownership_mode='LOAN' THEN
        IF (SELECT count(*) FROM inventory_asset_recovery_obligation WHERE tenant_id=scope AND assignment_id=assignment.id)<>1
            OR NOT EXISTS(SELECT FROM inventory_asset_recovery_obligation WHERE tenant_id=scope AND assignment_id=assignment.id
                AND inventory_asset_recovery_obligation.handover_id=handover.id AND asset_id=handover.asset_id AND customer_id=handover.customer_id)
            OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND operation_id=acceptance.operation_id)
            OR EXISTS(SELECT FROM inventory_document_line WHERE tenant_id=scope AND document_id=acceptance.operation_id) THEN
            RAISE EXCEPTION 'ASSET_LOAN_OBLIGATION_BINDING' USING ERRCODE='23514';
        END IF;
    ELSE
        IF EXISTS(SELECT FROM inventory_asset_recovery_obligation WHERE tenant_id=scope AND assignment_id=assignment.id)
            OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=acceptance.operation_id)<>1
            OR (SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=acceptance.operation_id)<>1 THEN
            RAISE EXCEPTION 'ASSET_SALE_POSTING_CARDINALITY' USING ERRCODE='23514';
        END IF;
        SELECT id INTO target_movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=acceptance.operation_id
            AND kind='TITLE_TRANSFER' AND state='APPLIED' AND document_id=acceptance.operation_id AND actor_id=handover.actor_id;
        IF target_movement IS NULL OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=target_movement)<>2
            OR EXISTS(SELECT FROM inventory_customer_material_fact WHERE tenant_id=scope AND posting_id=target_movement) THEN
            RAISE EXCEPTION 'ASSET_SALE_POSTING_REQUIRED' USING ERRCODE='23514';
        END IF;
        SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=target_movement AND direction='IN';
        SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=target_movement AND direction='OUT';
        IF (incoming.stock_identity_id,incoming.quantity_base,incoming.base_unit,incoming.legal_owner,incoming.status,incoming.custody_owner_id,incoming.custody_owner_kind)
            IS DISTINCT FROM (handover.asset_id,1::bigint,'EA'::text,'CUSTOMER'::varchar,'CUSTOMER_INSTALLED'::varchar,handover.customer_id,'CUSTOMER'::varchar)
            OR (outgoing.stock_identity_id,outgoing.quantity_base,outgoing.base_unit,outgoing.legal_owner,outgoing.status,outgoing.custody_owner_id,outgoing.custody_owner_kind)
            IS DISTINCT FROM (handover.asset_id,1::bigint,'EA'::text,'ISP'::varchar,'CUSTOMER_INSTALLED'::varchar,handover.customer_id,'CUSTOMER'::varchar)
            OR incoming.location_id<>outgoing.location_id OR incoming.condition<>'SERVICEABLE' OR outgoing.condition<>'SERVICEABLE'
            OR incoming.document_line_id<>acceptance.operation_id OR outgoing.document_line_id<>acceptance.operation_id
            OR incoming.sku_id<>outgoing.sku_id OR incoming.lot_id IS DISTINCT FROM outgoing.lot_id THEN
            RAISE EXCEPTION 'ASSET_SALE_EXACT_OWNER_LEGS' USING ERRCODE='23514';
        END IF;
    END IF;
END $$;

CREATE FUNCTION warehouse_asset_handover_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data jsonb; scope uuid; target uuid; handover_id uuid; target_operation uuid;
BEGIN
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(row_data->>'tenant_id')::uuid;
    PERFORM warehouse_assert_deferred_scope(scope);
    IF TG_TABLE_NAME='inventory_asset_handover' THEN
        PERFORM warehouse_assert_asset_handover(scope,(row_data->>'id')::uuid);
    ELSIF TG_TABLE_NAME IN ('inventory_asset_acceptance','inventory_asset_recovery_obligation') THEN
        PERFORM warehouse_assert_asset_handover(scope,(row_data->>'handover_id')::uuid);
    ELSE
        target:=CASE TG_TABLE_NAME
            WHEN 'inventory_asset_assignment' THEN (row_data->>'id')::uuid
            WHEN 'inventory_serialized_asset' THEN (row_data->>'id')::uuid
            WHEN 'inventory_movement_leg' THEN (row_data->>'stock_identity_id')::uuid
            WHEN 'inventory_balance_projection' THEN (row_data->>'stock_identity_id')::uuid
            WHEN 'wo_signature' THEN (row_data->>'id')::uuid
            WHEN 'evidence_object_registry' THEN (row_data->>'revision_id')::uuid END;
        target_operation:=CASE TG_TABLE_NAME
            WHEN 'inventory_operation' THEN (row_data->>'id')::uuid
            WHEN 'inventory_document' THEN (row_data->>'id')::uuid
            WHEN 'inventory_document_line' THEN (row_data->>'document_id')::uuid
            ELSE (row_data->>'operation_id')::uuid END;
        FOR handover_id IN SELECT handover.id FROM inventory_asset_handover handover
            JOIN inventory_asset_acceptance acceptance ON acceptance.tenant_id=handover.tenant_id AND acceptance.handover_id=handover.id
            WHERE handover.tenant_id=scope AND (handover.assignment_id=target OR handover.asset_id=target
                OR handover.evidence_id=target OR acceptance.operation_id=target_operation) LOOP
            PERFORM warehouse_assert_asset_handover(scope,handover_id);
        END LOOP;
        IF (row_data->>'namespace'='warehouse.asset.handover' OR row_data->>'kind' IN ('TITLE_TRANSFER','ASSET_HANDOVER'))
            AND coalesce(row_data->>'state','')<>'DRAFT' AND NOT EXISTS(SELECT FROM inventory_asset_acceptance
                WHERE tenant_id=scope AND inventory_asset_acceptance.operation_id=target_operation) THEN
            RAISE EXCEPTION 'ASSET_HANDOVER_OPERATION_REQUIRES_ACCEPTANCE' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NULL;
END $$;

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_handover','inventory_asset_acceptance','inventory_asset_recovery_obligation',
        'inventory_asset_assignment','inventory_serialized_asset','inventory_balance_projection','inventory_movement','inventory_movement_leg',
        'inventory_operation','inventory_document','inventory_document_line','wo_signature','evidence_object_registry'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_asset_handover_final AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_asset_handover_final_guard()',table_name);
    END LOOP;
END $$;
