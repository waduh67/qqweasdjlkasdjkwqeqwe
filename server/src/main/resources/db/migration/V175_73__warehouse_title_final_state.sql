CREATE FUNCTION warehouse_assert_title_request(scope uuid, request_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE request inventory_asset_title_request; handover inventory_asset_handover; acceptance inventory_asset_acceptance;
    document inventory_document; line inventory_document_line; body jsonb; history jsonb; position jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO request FROM inventory_asset_title_request WHERE tenant_id=scope AND id=$2;
    IF NOT FOUND THEN RAISE EXCEPTION 'TITLE_REQUEST_REQUIRED' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_asset_handover(scope,request.handover_id);
    SELECT * INTO handover FROM inventory_asset_handover WHERE tenant_id=scope AND id=request.handover_id;
    SELECT * INTO acceptance FROM inventory_asset_acceptance WHERE tenant_id=scope AND handover_id=request.handover_id;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=request.id;
    SELECT * INTO line FROM inventory_document_line WHERE tenant_id=scope AND document_id=request.id;
    SELECT snapshot INTO history FROM inventory_asset_assignment_history WHERE tenant_id=scope AND assignment_id=request.assignment_id
        AND revision=request.source_assignment_revision;
    body:=request.snapshot::jsonb;
    position:=(acceptance.snapshot::jsonb->'position')||jsonb_build_object('assetRevision',request.source_asset_revision,
        'dimension',(acceptance.snapshot::jsonb#>'{position,dimension}')||jsonb_build_object('legalOwner',request.source_owner));
    IF history IS NULL OR (request.assignment_id,request.asset_id,request.customer_id,request.work_order_id)
        IS DISTINCT FROM (handover.assignment_id,handover.asset_id,handover.customer_id,handover.work_order_id)
        OR history->>'legal_owner' IS DISTINCT FROM request.source_owner
        OR request.source_title_revision<>request.source_assignment_revision-1+(CASE handover.ownership_mode WHEN 'SALE' THEN 1 ELSE 0 END)
        OR request.source_asset_revision<>acceptance.source_asset_revision+request.source_title_revision
        OR (body->>'id')::uuid IS DISTINCT FROM request.id OR (body->>'actorId')::uuid IS DISTINCT FROM request.actor_id
        OR body->>'targetOwner' IS DISTINCT FROM request.target_owner OR body->>'reason' IS DISTINCT FROM request.reason
        OR body->'handover' IS DISTINCT FROM acceptance.snapshot::jsonb OR body->'position' IS DISTINCT FROM position
        OR (body#>>'{source,assignmentId}')::uuid IS DISTINCT FROM request.assignment_id
        OR (body#>>'{source,assetId}')::uuid IS DISTINCT FROM request.asset_id OR (body#>>'{source,customerId}')::uuid IS DISTINCT FROM request.customer_id
        OR (body#>>'{source,workOrderId}')::uuid IS DISTINCT FROM request.work_order_id
        OR body#>>'{source,ownershipMode}' IS DISTINCT FROM handover.ownership_mode OR body#>>'{source,legalOwner}' IS DISTINCT FROM request.source_owner
        OR (body#>>'{source,assignmentRevision}')::bigint IS DISTINCT FROM request.source_assignment_revision
        OR (body#>>'{source,titleRevision}')::bigint IS DISTINCT FROM request.source_title_revision
        OR (body#>>'{source,handoverId}')::uuid IS DISTINCT FROM handover.id
        OR (body#>>'{evidence,id}')::uuid IS DISTINCT FROM request.evidence_id OR body#>>'{evidence,digest}' IS DISTINCT FROM request.evidence_digest
        OR document.id IS NULL OR document.kind<>'TITLE_CORRECTION' OR document.source_reference IS DISTINCT FROM request.snapshot
        OR (document.actor_id,document.work_order_id,document.customer_id,document.source_document_id,document.source_revision)
            IS DISTINCT FROM (request.actor_id,request.work_order_id,request.customer_id,acceptance.operation_id,1::bigint)
        OR document.code IS DISTINCT FROM body->>'code' OR document.authority_epoch IS DISTINCT FROM (body->>'authorityEpoch')::bigint
        OR document.cutover_epoch IS DISTINCT FROM (body->>'cutoverEpoch')::bigint
        OR document.work_order_revision IS DISTINCT FROM (body->>'workOrderRevision')::bigint
        OR (SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=request.id)<>1
        OR (line.id,line.document_id,line.document_revision,line.line_number,line.stock_identity_id,line.quantity_base,line.base_unit,line.tracking,
            line.source_line_id,line.location_id,line.custodian_id,line.custodian_kind,line.condition,line.legal_owner)
            IS DISTINCT FROM (request.id,request.id,0::bigint,1,request.asset_id,1::bigint,'EA'::text,'SERIAL'::text,
                request.assignment_id,(body#>>'{position,dimension,locationId}')::uuid,request.customer_id,'CUSTOMER'::text,'SERVICEABLE'::text,request.source_owner)
        OR NOT EXISTS(SELECT FROM wo_signature signed JOIN evidence_object_registry registry ON registry.tenant_id=signed.tenant_id
            AND registry.revision_id=signed.id WHERE signed.tenant_id=scope AND signed.id=request.evidence_id
            AND signed.work_order_id=request.work_order_id AND signed.sha256=request.evidence_digest
            AND signed.storage_key=body#>>'{evidence,reference}' AND signed.signer_name=body#>>'{evidence,receiverLabel}'
            AND signed.receipt_at=(body#>>'{evidence,receivedAt}')::timestamptz AND registry.object_key=signed.storage_key
            AND registry.expected_sha256=signed.sha256) THEN
        RAISE EXCEPTION 'TITLE_REQUEST_EXACT_SOURCE_BINDING' USING ERRCODE='23514';
    END IF;
    IF document.state='POSTED' AND NOT EXISTS(SELECT FROM inventory_asset_title_transfer WHERE tenant_id=scope AND inventory_asset_title_transfer.request_id=request.id) THEN
        RAISE EXCEPTION 'TITLE_POSTED_REQUEST_REQUIRES_TRANSFER' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_assert_current_asset_title(scope uuid, assignment_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE assignment inventory_asset_assignment; handover inventory_asset_handover; acceptance inventory_asset_acceptance;
    transfer inventory_asset_title_transfer; owner text:='ISP'; revision bigint:=0; title_revision bigint:=0;
    asset inventory_serialized_asset; base_asset_revision bigint; initial jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=scope AND id=$2;
    IF NOT FOUND OR assignment.warehouse_admission<>'VERIFIED' OR assignment.ended_at IS NOT NULL THEN
        RAISE EXCEPTION 'TITLE_ACTIVE_ASSIGNMENT_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT history.snapshot INTO initial FROM inventory_asset_assignment_history history WHERE history.tenant_id=scope AND history.assignment_id=$2 AND history.revision=0;
    SELECT expected_asset_revision+1 INTO base_asset_revision FROM inventory_deployment_authorization permit
        JOIN inventory_deployment_result result ON result.tenant_id=permit.tenant_id AND result.authorization_id=permit.id
        WHERE result.tenant_id=scope AND result.assignment_id=assignment.id;
    IF initial IS NULL OR base_asset_revision IS NULL THEN RAISE EXCEPTION 'TITLE_DEPLOYMENT_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO handover FROM inventory_asset_handover WHERE tenant_id=scope AND inventory_asset_handover.assignment_id=$2;
    IF FOUND THEN
        PERFORM warehouse_assert_asset_handover(scope,handover.id);
        revision:=1;
        IF handover.ownership_mode='SALE' THEN owner:='CUSTOMER'; title_revision:=1; END IF;
    END IF;
    FOR transfer IN SELECT * FROM inventory_asset_title_transfer WHERE tenant_id=scope AND inventory_asset_title_transfer.assignment_id=$2
        ORDER BY inventory_asset_title_transfer.title_revision LOOP
        PERFORM warehouse_assert_title_request(scope,transfer.request_id);
        PERFORM warehouse_assert_title_transfer(scope,transfer.id);
        IF transfer.source_owner<>owner OR transfer.source_assignment_revision<>revision OR transfer.source_title_revision<>title_revision
            OR transfer.source_asset_revision<>base_asset_revision+title_revision THEN
            RAISE EXCEPTION 'TITLE_TRANSFER_CHAIN_GAP' USING ERRCODE='23514'; END IF;
        owner:=transfer.target_owner; revision:=transfer.assignment_revision; title_revision:=transfer.title_revision;
    END LOOP;
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=scope AND id=assignment.asset_id;
    IF assignment.legal_owner<>owner OR assignment.revision<>revision OR asset.legal_owner<>owner
        OR asset.revision<>base_asset_revision+title_revision OR asset.status<>'CUSTOMER_INSTALLED'
        OR asset.condition<>'SERVICEABLE' OR asset.custody_owner_kind<>'CUSTOMER' OR asset.custody_owner_id<>assignment.customer_id
        OR to_jsonb(assignment)-ARRAY['revision','legal_owner'] IS DISTINCT FROM
            to_jsonb(jsonb_populate_record(NULL::inventory_asset_assignment,initial))-ARRAY['revision','legal_owner']
        OR (SELECT count(*) FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=asset.id AND quantity_base>0)<>1
        OR NOT EXISTS(SELECT FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=asset.id
            AND quantity_base=1 AND base_unit='EA' AND legal_owner=owner AND status='CUSTOMER_INSTALLED' AND condition='SERVICEABLE'
            AND location_id=asset.location_id AND custody_owner_kind='CUSTOMER' AND custody_owner_id=assignment.customer_id) THEN
        RAISE EXCEPTION 'TITLE_CURRENT_OWNER_POSITION_BINDING' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_title_request_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE assignment inventory_asset_assignment; asset inventory_serialized_asset;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.created_xid<>pg_current_xact_id() THEN RAISE EXCEPTION 'TITLE_REQUEST_TRANSACTION_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND id=NEW.assignment_id FOR UPDATE;
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id FOR UPDATE;
    PERFORM warehouse_assert_current_asset_title(NEW.tenant_id,NEW.assignment_id);
    IF assignment.revision<>NEW.source_assignment_revision OR assignment.legal_owner<>NEW.source_owner
        OR asset.revision<>NEW.source_asset_revision OR asset.legal_owner<>NEW.source_owner THEN
        RAISE EXCEPTION 'TITLE_REQUEST_STALE_SOURCE' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_title_request_insert BEFORE INSERT ON inventory_asset_title_request FOR EACH ROW EXECUTE FUNCTION warehouse_title_request_insert_guard();

CREATE FUNCTION warehouse_title_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data jsonb; scope uuid; target uuid; target_document uuid; target_operation uuid; target_request uuid; target_assignment uuid;
BEGIN
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(row_data->>'tenant_id')::uuid;
    PERFORM warehouse_assert_deferred_scope(scope);
    IF TG_TABLE_NAME='inventory_asset_title_request' THEN
        PERFORM warehouse_assert_title_request(scope,(row_data->>'id')::uuid);
    END IF;
    target:=CASE TG_TABLE_NAME WHEN 'inventory_asset_assignment' THEN (row_data->>'id')::uuid
        WHEN 'inventory_serialized_asset' THEN (row_data->>'id')::uuid WHEN 'inventory_identity_claim' THEN (row_data->>'admitted_asset_id')::uuid
        WHEN 'wo_signature' THEN (row_data->>'id')::uuid WHEN 'evidence_object_registry' THEN (row_data->>'revision_id')::uuid
        ELSE coalesce((row_data->>'asset_id')::uuid,(row_data->>'stock_identity_id')::uuid,(row_data->>'assignment_id')::uuid) END;
    target_document:=CASE TG_TABLE_NAME WHEN 'inventory_document' THEN (row_data->>'id')::uuid
        WHEN 'inventory_asset_title_request' THEN (row_data->>'id')::uuid
        ELSE coalesce((row_data->>'document_id')::uuid,(row_data->>'source_document_id')::uuid,(row_data->>'request_id')::uuid) END;
    target_operation:=CASE WHEN TG_TABLE_NAME='inventory_operation' THEN (row_data->>'id')::uuid
        ELSE coalesce((row_data->>'operation_id')::uuid,(row_data->>'posting_operation_id')::uuid) END;
    FOR target_request IN SELECT request.id FROM inventory_asset_title_request request
        LEFT JOIN inventory_asset_title_transfer transfer ON transfer.tenant_id=request.tenant_id AND transfer.request_id=request.id
        WHERE request.tenant_id=scope AND (request.id=target_document OR request.asset_id=target OR request.assignment_id=target
            OR request.evidence_id=target OR transfer.operation_id=target_operation) LOOP
        PERFORM warehouse_assert_title_request(scope,target_request);
    END LOOP;
    FOR target_assignment IN SELECT DISTINCT assignment.id FROM inventory_asset_assignment assignment
        JOIN inventory_deployment_result result ON result.tenant_id=assignment.tenant_id AND result.assignment_id=assignment.id
        LEFT JOIN inventory_asset_title_transfer transfer ON transfer.tenant_id=assignment.tenant_id AND transfer.assignment_id=assignment.id
        LEFT JOIN inventory_asset_handover handover ON handover.tenant_id=assignment.tenant_id AND handover.assignment_id=assignment.id
        WHERE assignment.tenant_id=scope AND (assignment.id=target OR assignment.asset_id=target OR transfer.operation_id=target_operation
            OR handover.id=(row_data->>'handover_id')::uuid OR transfer.id=(row_data->>'transfer_id')::uuid
            OR (TG_TABLE_NAME='inventory_asset_title_transfer' AND transfer.id=(row_data->>'id')::uuid)) LOOP
        PERFORM warehouse_assert_current_asset_title(scope,target_assignment);
    END LOOP;
    IF row_data->>'kind'='TITLE_CORRECTION' AND TG_TABLE_NAME='inventory_movement' AND NOT EXISTS(
        SELECT FROM inventory_asset_title_transfer WHERE tenant_id=scope AND inventory_asset_title_transfer.operation_id=target_operation) THEN
        RAISE EXCEPTION 'TITLE_CORRECTION_POSTING_REQUIRES_TRANSFER' USING ERRCODE='23514'; END IF;
    RETURN NULL;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_title_request','inventory_asset_title_transfer','inventory_asset_recovery_transition',
        'inventory_asset_acceptance_origin','inventory_asset_acceptance','inventory_asset_handover','inventory_asset_assignment',
        'inventory_serialized_asset','inventory_balance_projection','inventory_movement','inventory_movement_leg','inventory_operation',
        'inventory_document','inventory_document_line','inventory_approval','inventory_approval_decision','inventory_approval_effect',
        'wo_signature','evidence_object_registry','inventory_identity_claim'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_title_final AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_title_final_guard()',table_name);
    END LOOP;
END $$;
