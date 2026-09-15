CREATE OR REPLACE FUNCTION warehouse_approval_posting_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.operation_namespace='warehouse.approval.effect' AND NOT EXISTS (
        SELECT FROM inventory_approval approval JOIN inventory_operation operation ON operation.tenant_id=approval.tenant_id AND operation.id=NEW.operation_id
        WHERE approval.tenant_id=NEW.tenant_id AND approval.id::text=NEW.operation_key
            AND approval.source_document_id=NEW.document_id AND approval.source_document_revision+1=NEW.document_revision
            AND approval.status='APPROVED' AND approval.expires_at>clock_timestamp()
            AND ((approval.business_action='RECEIPT' AND NEW.kind='RECEIVE') OR
                (approval.business_action='TITLE_REACQUISITION' AND NEW.kind='TITLE_CORRECTION' AND EXISTS(
                    SELECT FROM inventory_asset_title_request WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id)))
            AND operation.namespace='warehouse.approval.effect' AND operation.resource_id=approval.source_document_id
            AND operation.payload_hash=approval.source_snapshot_hash
    ) THEN RAISE EXCEPTION 'posting requires live document-bound approval' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assignment_history_guard()'::regprocedure);
    IF strpos(definition,'IF TG_OP=''UPDATE'' THEN')=0 THEN RAISE EXCEPTION 'assignment guard missing'; END IF;
    EXECUTE replace(definition,'IF TG_OP=''UPDATE'' THEN',$body$
    IF TG_OP='UPDATE' THEN
        IF NEW.revision=OLD.revision+1 AND OLD.ended_at IS NULL AND NEW.ended_at IS NULL
            AND to_jsonb(NEW)-ARRAY['legal_owner','revision']=to_jsonb(OLD)-ARRAY['legal_owner','revision']
            AND EXISTS(SELECT FROM inventory_asset_title_transfer transfer WHERE transfer.tenant_id=NEW.tenant_id
                AND transfer.assignment_id=NEW.id AND transfer.source_assignment_revision=OLD.revision
                AND transfer.assignment_revision=NEW.revision AND transfer.source_owner=OLD.legal_owner
                AND transfer.target_owner=NEW.legal_owner AND transfer.created_xid=pg_current_xact_id()) THEN RETURN NEW; END IF;
    $body$);
END $$;

CREATE FUNCTION warehouse_apply_asset_title_transfer(scope uuid, transfer_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE transfer inventory_asset_title_transfer;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO transfer FROM inventory_asset_title_transfer WHERE tenant_id=scope AND id=$2;
    IF NOT FOUND OR transfer.created_xid<>pg_current_xact_id() OR NOT EXISTS (
        SELECT FROM inventory_approval approval JOIN inventory_movement movement ON movement.tenant_id=approval.tenant_id
            AND movement.id=transfer.posting_id WHERE approval.tenant_id=scope AND approval.id=transfer.approval_id
            AND approval.status='APPROVED' AND approval.business_action='TITLE_REACQUISITION'
            AND approval.source_document_id=transfer.request_id AND movement.kind='TITLE_CORRECTION'
            AND movement.operation_id=transfer.operation_id AND movement.state='APPLIED') THEN
        RAISE EXCEPTION 'TITLE_APPROVED_POSTING_REQUIRED' USING ERRCODE='23514';
    END IF;
    UPDATE inventory_asset_assignment SET legal_owner=transfer.target_owner,revision=revision+1
        WHERE tenant_id=scope AND id=transfer.assignment_id AND revision=transfer.source_assignment_revision
        AND legal_owner=transfer.source_owner AND ended_at IS NULL;
    IF NOT FOUND THEN RAISE EXCEPTION 'TITLE_STALE_ASSIGNMENT' USING ERRCODE='40001'; END IF;
END $$;

CREATE FUNCTION warehouse_assert_title_transfer(scope uuid, transfer_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE transfer inventory_asset_title_transfer; request inventory_asset_title_request; approval inventory_approval;
    operation inventory_operation; incoming inventory_movement_leg; outgoing inventory_movement_leg; initial jsonb; result jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO transfer FROM inventory_asset_title_transfer WHERE tenant_id=scope AND id=$2;
    SELECT * INTO request FROM inventory_asset_title_request WHERE tenant_id=scope AND id=transfer.request_id;
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=scope AND id=transfer.approval_id;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=scope AND id=transfer.operation_id;
    IF transfer.id IS NULL OR request.id IS NULL OR approval.id IS NULL OR operation.id IS NULL
        OR (transfer.assignment_id,transfer.asset_id,transfer.customer_id,transfer.work_order_id,transfer.handover_id,
            transfer.source_assignment_revision,transfer.source_title_revision,transfer.source_asset_revision,transfer.source_owner,transfer.target_owner)
        IS DISTINCT FROM (request.assignment_id,request.asset_id,request.customer_id,request.work_order_id,request.handover_id,
            request.source_assignment_revision,request.source_title_revision,request.source_asset_revision,request.source_owner,request.target_owner)
        OR transfer.assignment_revision<>request.source_assignment_revision+1 OR transfer.title_revision<>request.source_title_revision+1
        OR approval.status<>'APPROVED' OR approval.business_action<>'TITLE_REACQUISITION'
        OR approval.source_document_id<>request.id OR approval.source_document_revision<>0 OR approval.requester_id<>request.actor_id
        OR approval.source_snapshot::jsonb#>>'{document,source_reference}' IS DISTINCT FROM request.snapshot
        OR operation.namespace<>'warehouse.approval.effect' OR operation.business_action<>'TITLE_REACQUISITION'
        OR operation.resource_id<>request.id OR operation.operation_key<>approval.id::text
        OR operation.document_id<>request.id OR operation.document_revision<>1 OR operation.payload_hash<>approval.source_snapshot_hash
        OR operation.actor_id<>transfer.actor_id OR operation.original_body<>approval.terminal_body
        OR NOT EXISTS(SELECT FROM inventory_approval_effect WHERE tenant_id=scope AND approval_id=approval.id
            AND posting_operation_id=operation.id AND source_document_id=request.id AND original_body=operation.original_body)
        OR EXISTS(SELECT FROM inventory_approval_decision decision JOIN inventory_asset_handover handover
            ON handover.tenant_id=decision.tenant_id AND handover.id=request.handover_id
            WHERE decision.tenant_id=scope AND decision.approval_id=approval.id AND
                (decision.approver_id IN (request.actor_id,request.customer_id,handover.actor_id)
                OR decision.delegated_from IN (request.actor_id,request.customer_id,handover.actor_id))) THEN
        RAISE EXCEPTION 'TITLE_TRANSFER_APPROVAL_BINDING' USING ERRCODE='23514';
    END IF;
    SELECT history.snapshot INTO initial FROM inventory_asset_assignment_history history WHERE tenant_id=scope
        AND assignment_id=request.assignment_id AND revision=transfer.source_assignment_revision;
    SELECT history.snapshot INTO result FROM inventory_asset_assignment_history history WHERE tenant_id=scope
        AND assignment_id=request.assignment_id AND revision=transfer.assignment_revision;
    IF initial IS NULL OR result IS NULL OR initial->>'legal_owner' IS DISTINCT FROM transfer.source_owner
        OR result->>'legal_owner' IS DISTINCT FROM transfer.target_owner
        OR initial-ARRAY['legal_owner','revision'] IS DISTINCT FROM result-ARRAY['legal_owner','revision'] THEN
        RAISE EXCEPTION 'TITLE_TRANSFER_ASSIGNMENT_HISTORY' USING ERRCODE='23514';
    END IF;
    IF (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=transfer.operation_id)<>1
        OR NOT EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND id=transfer.posting_id AND operation_id=transfer.operation_id
            AND kind='TITLE_CORRECTION' AND state='APPLIED' AND document_id=request.id AND document_revision=1)
        OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=transfer.posting_id)<>2
        OR EXISTS(SELECT FROM inventory_customer_material_fact WHERE tenant_id=scope AND posting_id=transfer.posting_id) THEN
        RAISE EXCEPTION 'TITLE_TRANSFER_EXACT_POSTING' USING ERRCODE='23514';
    END IF;
    SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=transfer.posting_id AND direction='IN';
    SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=transfer.posting_id AND direction='OUT';
    IF (incoming.stock_identity_id,incoming.quantity_base,incoming.base_unit,incoming.legal_owner,incoming.custody_owner_id,incoming.custody_owner_kind,incoming.status)
        IS DISTINCT FROM (request.asset_id,1::bigint,'EA'::text,request.target_owner::varchar,request.customer_id,'CUSTOMER'::varchar,'CUSTOMER_INSTALLED'::varchar)
        OR (outgoing.stock_identity_id,outgoing.quantity_base,outgoing.base_unit,outgoing.legal_owner,outgoing.custody_owner_id,outgoing.custody_owner_kind,outgoing.status)
        IS DISTINCT FROM (request.asset_id,1::bigint,'EA'::text,request.source_owner::varchar,request.customer_id,'CUSTOMER'::varchar,'CUSTOMER_INSTALLED'::varchar)
        OR incoming.location_id IS DISTINCT FROM (request.snapshot::jsonb#>>'{position,dimension,locationId}')::uuid
        OR incoming.location_id<>outgoing.location_id OR incoming.sku_id<>outgoing.sku_id OR incoming.lot_id IS DISTINCT FROM outgoing.lot_id
        OR incoming.condition<>'SERVICEABLE' OR outgoing.condition<>'SERVICEABLE'
        OR incoming.document_line_id<>request.id OR outgoing.document_line_id<>request.id THEN
        RAISE EXCEPTION 'TITLE_TRANSFER_EXACT_OWNER_LEGS' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT FROM inventory_asset_recovery_transition WHERE tenant_id=scope AND inventory_asset_recovery_transition.transfer_id=transfer.id
        AND assignment_id=request.assignment_id AND asset_id=request.asset_id AND customer_id=request.customer_id AND required=(request.target_owner='ISP')) THEN
        RAISE EXCEPTION 'TITLE_TRANSFER_RECOVERY_BINDING' USING ERRCODE='23514';
    END IF;
END $$;
