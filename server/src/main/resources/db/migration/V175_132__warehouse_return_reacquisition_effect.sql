-- An independent title approval changes current quarantine ownership, never the
-- closed customer assignment or the immutable intake/repair evidence.
CREATE TABLE inventory_return_title_effect (
    tenant_id uuid NOT NULL, request_id uuid NOT NULL, approval_id uuid NOT NULL,
    posting_operation_id uuid NOT NULL, return_operation_id uuid NOT NULL,
    return_id uuid NOT NULL, source_return_revision bigint NOT NULL,
    return_revision bigint NOT NULL CHECK(return_revision=source_return_revision+1),
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,request_id), UNIQUE(tenant_id,approval_id), UNIQUE(tenant_id,posting_operation_id),
    UNIQUE(tenant_id,return_operation_id), UNIQUE(tenant_id,return_id,return_revision),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_return_title_request(tenant_id,id),
    FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id),
    FOREIGN KEY(tenant_id,posting_operation_id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY(tenant_id,return_operation_id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,return_id) REFERENCES inventory_return_case(tenant_id,id)
);
ALTER TABLE inventory_return_title_effect ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_return_title_effect FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_return_title_effect
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_return_title_effect
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_return_title_effect TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_assert_return_title_posting_source(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE request inventory_return_title_request; asset inventory_serialized_asset; returned inventory_document; previous jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT request FROM inventory_return_title_request WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT returned FROM inventory_document WHERE tenant_id=scope AND id=request.return_id FOR UPDATE;
    SELECT * INTO STRICT asset FROM inventory_serialized_asset WHERE tenant_id=scope AND id=request.asset_id FOR UPDATE;
    SELECT original_body::jsonb INTO previous FROM inventory_operation
        WHERE tenant_id=scope AND document_id=request.return_id AND document_revision=returned.revision;
    IF returned.state<>'RECEIVED_IN_INSPECTION' OR returned.revision<>request.source_return_revision
        OR previous IS DISTINCT FROM request.snapshot::jsonb#>'{returned,view}'
        OR to_jsonb(asset) IS DISTINCT FROM request.origin_snapshot->'asset'
        OR asset.legal_owner<>'CUSTOMER' OR asset.custody_owner_kind<>'WAREHOUSE' OR asset.status<>'QUARANTINE'
        OR EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=scope AND asset_id=request.asset_id AND ended_at IS NULL)
        OR EXISTS(SELECT FROM inventory_repair_case WHERE tenant_id=scope AND return_document_id=request.return_id AND state<>'CLOSED') THEN
        RAISE EXCEPTION 'RETURN_TITLE_STALE_POSTING_SOURCE' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_assert_return_title_effect(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE effect inventory_return_title_effect; request inventory_return_title_request; approval inventory_approval;
    posted inventory_operation; transition inventory_operation; movement inventory_movement;
    outgoing inventory_movement_leg; incoming inventory_movement_leg;
    body jsonb; previous jsonb; snapshot jsonb; canonical text; parties uuid[];
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO effect FROM inventory_return_title_effect WHERE tenant_id=scope AND request_id=target;
    SELECT * INTO request FROM inventory_return_title_request WHERE tenant_id=scope AND id=target;
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=scope AND id=effect.approval_id;
    SELECT * INTO posted FROM inventory_operation WHERE tenant_id=scope AND id=effect.posting_operation_id;
    SELECT * INTO transition FROM inventory_operation WHERE tenant_id=scope AND id=effect.return_operation_id;
    body:=request.snapshot::jsonb;
    parties:=ARRAY[request.actor_id,request.customer_id,(body#>>'{context,handoverActorId}')::uuid,
        (body#>>'{context,removalActorId}')::uuid,(body#>>'{returned,view,receivedBy}')::uuid];
    IF effect.request_id IS NULL OR request.id IS NULL OR approval.id IS NULL OR posted.id IS NULL OR transition.id IS NULL
        OR (effect.return_id,effect.source_return_revision) IS DISTINCT FROM (request.return_id,request.source_return_revision)
        OR approval.status<>'APPROVED' OR approval.business_action<>'TITLE_REACQUISITION'
        OR approval.source_document_id<>request.id OR approval.source_document_revision<>0 OR approval.requester_id<>request.actor_id
        OR approval.source_snapshot::jsonb->'returnTitle' IS DISTINCT FROM body
        OR posted.namespace<>'warehouse.approval.effect' OR posted.business_action<>'TITLE_REACQUISITION'
        OR posted.resource_id<>request.id OR posted.operation_key<>approval.id::text OR posted.resource_scope<>'approval:'||approval.id
        OR posted.document_id<>request.id OR posted.document_revision<>1 OR posted.payload_hash<>approval.source_snapshot_hash
        OR posted.original_status<>200 OR posted.original_body IS DISTINCT FROM approval.terminal_body
        OR posted.cutover_epoch<>(body->>'cutoverEpoch')::bigint
        OR NOT EXISTS(SELECT FROM inventory_approval_effect WHERE tenant_id=scope AND approval_id=approval.id
            AND posting_operation_id=posted.id AND source_document_id=request.id AND original_body=posted.original_body)
        OR EXISTS(SELECT FROM inventory_approval_decision WHERE tenant_id=scope AND approval_id=approval.id
            AND (approver_id=ANY(parties) OR delegated_from=ANY(parties))) THEN
        RAISE EXCEPTION 'RETURN_TITLE_INDEPENDENT_APPROVAL_REQUIRED' USING ERRCODE='23514'; END IF;
    IF NOT EXISTS(SELECT FROM inventory_document WHERE tenant_id=scope AND id=request.id AND kind='RETURN_TITLE' AND state='POSTED' AND revision=1)
        OR (SELECT count(*) FROM inventory_operation WHERE tenant_id=scope AND document_id=request.id)<>1
        OR NOT EXISTS(SELECT FROM inventory_asset_assignment assignment WHERE tenant_id=scope AND id=request.assignment_id
            AND ended_at IS NOT NULL AND to_jsonb(assignment)=request.origin_snapshot->'assignment') THEN
        RAISE EXCEPTION 'RETURN_TITLE_HISTORICAL_ASSIGNMENT_IMMUTABLE' USING ERRCODE='23514'; END IF;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=posted.id;
    SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
    SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
    IF movement.id IS NULL OR movement.kind<>'TITLE_CORRECTION' OR movement.state<>'APPLIED'
        OR movement.document_id<>request.id OR movement.document_revision<>1
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=posted.id)<>1
        OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
        OR outgoing.id IS NULL OR incoming.id IS NULL
        OR (outgoing.stock_identity_id,outgoing.sku_id,outgoing.lot_id,outgoing.quantity_base,outgoing.base_unit,outgoing.document_line_id,
            outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,outgoing.condition,outgoing.status,outgoing.legal_owner)
            IS DISTINCT FROM (request.asset_id,(body#>>'{source,dimension,skuId}')::uuid,NULL::uuid,1::bigint,'EA'::varchar,request.id,
                (body#>>'{source,dimension,locationId}')::uuid,(body#>>'{source,dimension,locationId}')::uuid,'WAREHOUSE'::varchar,
                body#>>'{source,dimension,condition}','QUARANTINE'::varchar,'CUSTOMER'::varchar)
        OR to_jsonb(incoming)-ARRAY['id','direction','legal_owner'] IS DISTINCT FROM to_jsonb(outgoing)-ARRAY['id','direction','legal_owner']
        OR incoming.legal_owner<>'ISP'
        OR EXISTS(SELECT FROM inventory_customer_material_fact WHERE tenant_id=scope AND posting_id=movement.id)
        OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=posted.id
            AND document_id=request.id AND document_revision=1 AND event_kind='TITLE_REACQUIRED') THEN
        RAISE EXCEPTION 'RETURN_TITLE_EXACT_QUARANTINE_POSTING' USING ERRCODE='23514'; END IF;
    SELECT original_body::jsonb INTO previous FROM inventory_operation WHERE tenant_id=scope AND document_id=request.return_id
        AND document_revision=request.source_return_revision;
    SELECT canonical_payload INTO canonical FROM inventory_command_identity WHERE tenant_id=scope AND id=transition.id;
    snapshot:=transition.original_body::jsonb;
    IF previous IS NULL OR previous IS DISTINCT FROM body#>'{returned,view}'
        OR previous->>'legalOwner' IS DISTINCT FROM 'CUSTOMER' OR snapshot->>'legalOwner' IS DISTINCT FROM 'ISP'
        OR snapshot-ARRAY['revision','legalOwner','recordedAt'] IS DISTINCT FROM previous-ARRAY['revision','legalOwner','recordedAt']
        OR (snapshot->>'revision')::bigint IS DISTINCT FROM effect.return_revision
        OR (snapshot->>'recordedAt')::timestamptz IS DISTINCT FROM posted.created_at
        OR (transition.namespace,transition.operation_key,transition.actor_id,transition.resource_id,transition.resource_scope,
            transition.document_id,transition.document_revision,transition.business_action,transition.original_status,
            transition.cutover_epoch,transition.authority_epoch,transition.created_at)
            IS DISTINCT FROM ('warehouse.return.reacquire'::varchar,approval.id::text,posted.actor_id,request.return_id,'return:'||request.return_id,
                request.return_id,effect.return_revision,'TITLE_REACQUIRED'::varchar,200,posted.cutover_epoch,posted.authority_epoch,posted.created_at)
        OR canonical IS NULL OR transition.payload_hash<>encode(sha256(convert_to(canonical,'UTF8')),'hex')
        OR canonical::jsonb IS DISTINCT FROM jsonb_build_object('returnId',request.return_id,'requestId',request.id,
            'approvalId',approval.id,'sourceRevision',request.source_return_revision)
        OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND operation_id=transition.id) THEN
        RAISE EXCEPTION 'RETURN_TITLE_EXACT_RETURN_REVISION' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_assert_return_reacquisition_step(scope uuid, target uuid, target_operation uuid, previous jsonb, snapshot jsonb)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE effect inventory_return_title_effect;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO effect FROM inventory_return_title_effect WHERE tenant_id=scope AND return_operation_id=target_operation;
    IF effect.request_id IS NULL OR effect.return_id<>target
        OR (previous->>'revision')::bigint IS DISTINCT FROM effect.source_return_revision
        OR (snapshot->>'revision')::bigint IS DISTINCT FROM effect.return_revision THEN
        RAISE EXCEPTION 'RETURN_TITLE_STEP_REQUIRED' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_return_title_effect(scope,effect.request_id);
END $$;

CREATE FUNCTION warehouse_return_title_effect_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data jsonb; scope uuid; target uuid; doc uuid; operation uuid; approval uuid; movement uuid; request uuid;
BEGIN
    FOR row_data IN SELECT value FROM jsonb_array_elements(CASE TG_OP WHEN 'INSERT' THEN jsonb_build_array(to_jsonb(NEW))
        WHEN 'DELETE' THEN jsonb_build_array(to_jsonb(OLD)) ELSE jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW)) END) LOOP
        scope:=(row_data->>'tenant_id')::uuid;
        PERFORM warehouse_assert_deferred_scope(scope);
        target:=(row_data->>'id')::uuid;
        doc:=CASE WHEN TG_TABLE_NAME='inventory_document' THEN target ELSE (row_data->>'document_id')::uuid END;
        operation:=CASE WHEN TG_TABLE_NAME IN ('inventory_operation','inventory_command_identity') THEN target ELSE (row_data->>'operation_id')::uuid END;
        approval:=CASE WHEN TG_TABLE_NAME='inventory_approval' THEN target ELSE (row_data->>'approval_id')::uuid END;
        movement:=CASE WHEN TG_TABLE_NAME='inventory_movement' THEN target ELSE coalesce((row_data->>'movement_id')::uuid,(row_data->>'posting_id')::uuid) END;
        FOR request IN SELECT title.id FROM inventory_return_title_request title
            LEFT JOIN inventory_return_title_effect effect ON effect.tenant_id=title.tenant_id AND effect.request_id=title.id
            WHERE title.tenant_id=scope AND (title.id=doc OR title.return_id=doc OR title.assignment_id=target
                OR title.id=(row_data->>'request_id')::uuid OR effect.approval_id=approval
                OR effect.posting_operation_id=operation OR effect.return_operation_id=operation
                OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND id=movement AND operation_id=effect.posting_operation_id)) LOOP
            PERFORM warehouse_assert_return_title_request(scope,request);
        END LOOP;
    END LOOP;
    RETURN NULL;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_return_title_effect','inventory_document','inventory_document_line',
        'inventory_operation','inventory_command_identity','inventory_movement','inventory_movement_leg','inventory_customer_material_fact',
        'inventory_asset_assignment','inventory_approval','inventory_approval_decision','inventory_approval_effect','inventory_outbox'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_return_title_effect_final AFTER INSERT OR UPDATE OR DELETE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_return_title_effect_final_guard()',table_name);
    END LOOP;
END $$;

CREATE OR REPLACE FUNCTION public.warehouse_approval_posting_guard()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.operation_namespace='warehouse.approval.effect' AND NOT EXISTS (
        SELECT FROM inventory_approval approval JOIN inventory_operation operation ON operation.tenant_id=approval.tenant_id AND operation.id=NEW.operation_id
        WHERE approval.tenant_id=NEW.tenant_id AND approval.id::text=NEW.operation_key
            AND approval.source_document_id=NEW.document_id AND approval.source_document_revision+CASE WHEN approval.business_action='COUNT_VARIANCE' THEN 2 ELSE 1 END=NEW.document_revision
            AND approval.status='APPROVED' AND approval.expires_at>clock_timestamp()
            AND ((approval.business_action='ADJUSTMENT' AND NEW.kind='TRANSFER' AND EXISTS (
        SELECT FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id AND transfer_remainder_action IS NOT NULL)) OR
        (approval.business_action='RECEIPT' AND NEW.kind='RECEIVE') OR (approval.business_action='COUNT_VARIANCE' AND NEW.kind='COUNT_VARIANCE' AND EXISTS(SELECT FROM inventory_count_scope WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id)) OR
                (approval.business_action='TITLE_REACQUISITION' AND NEW.kind='TITLE_CORRECTION' AND EXISTS(
                    SELECT FROM inventory_asset_title_request WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id
                    UNION ALL SELECT FROM inventory_return_title_request WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id)))
            AND operation.namespace='warehouse.approval.effect' AND operation.resource_id=approval.source_document_id
            AND operation.payload_hash=approval.source_snapshot_hash
    ) THEN RAISE EXCEPTION 'posting requires live document-bound approval' USING ERRCODE='23514'; END IF;
    IF NEW.kind='TITLE_CORRECTION' AND EXISTS(SELECT FROM inventory_return_title_request WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id) THEN
        PERFORM warehouse_assert_return_title_posting_source(NEW.tenant_id,NEW.document_id);
    END IF;
    RETURN NEW;
END $function$;

CREATE OR REPLACE FUNCTION public.warehouse_assert_returned_asset(scope uuid, target uuid)
 RETURNS void
 LANGUAGE plpgsql
AS $function$
DECLARE intake inventory_return_case; document inventory_document; line inventory_document_line;
    removal inventory_asset_removal; origin inventory_asset_removal_origin; physical inventory_serialized_asset;
    balance inventory_balance_projection; operation inventory_operation; movement inventory_movement;
    outgoing inventory_movement_leg; incoming inventory_movement_leg;
    record jsonb; initial jsonb; previous jsonb; snapshot jsonb; command jsonb; canonical text; inspection jsonb;
    released boolean; expected_revision bigint:=0; outgoing_owner uuid; outgoing_kind text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT entry.* INTO intake FROM inventory_return_case entry WHERE entry.tenant_id=scope AND entry.id=target AND entry.origin='ASSET_REMOVAL';
    IF NOT FOUND THEN RAISE EXCEPTION 'RETURN_ASSET_CASE_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    SELECT * INTO removal FROM inventory_asset_removal WHERE tenant_id=scope AND id=intake.source_document_id;
    SELECT * INTO origin FROM inventory_asset_removal_origin WHERE tenant_id=scope AND removal_id=removal.id;
    physical:=jsonb_populate_record(NULL::inventory_serialized_asset,intake.recovery_snapshot->'asset');
    balance:=jsonb_populate_record(NULL::inventory_balance_projection,intake.recovery_snapshot->'balance');
    record:=intake.body::jsonb;
    initial:=record->'view';
    IF removal.id IS NULL OR origin.removal_id IS NULL OR physical.id IS NULL OR balance.id IS NULL
        OR document.kind<>'RETURN' OR document.source_document_id IS DISTINCT FROM removal.id OR document.source_revision<>1
        OR document.actor_id=removal.actor_id OR document.actor_id::text IS DISTINCT FROM initial->>'receivedBy'
        OR physical.id<>removal.asset_id OR physical.id<>intake.stock_identity_id OR physical.legal_owner<>removal.legal_owner
        OR physical.revision<>removal.source_asset_revision+1 OR physical.status<>'QUARANTINE' OR physical.condition<>'QUARANTINE'
        OR physical.location_id<>removal.recovery_location_id OR physical.custody_owner_id<>removal.actor_id OR physical.custody_owner_kind<>'TRANSIT'
        OR to_jsonb(physical)-ARRAY['location_id','custody_owner_id','custody_owner_kind','status','condition','revision'] IS DISTINCT FROM
            to_jsonb(jsonb_populate_record(NULL::inventory_serialized_asset,origin.asset_snapshot))-
                ARRAY['location_id','custody_owner_id','custody_owner_kind','status','condition','revision']
        OR (record->'intake'->>'sourceDocumentId')::uuid IS DISTINCT FROM removal.id
        OR record->'intake'->>'origin' IS DISTINCT FROM intake.origin
        OR (record->'intake'->>'quarantineLocationId')::uuid IS DISTINCT FROM intake.quarantine_location_id
        OR nullif(btrim(record->'intake'->>'evidenceReference'),'') IS NULL
        OR record->'source'->'dimension' IS DISTINCT FROM jsonb_build_object('skuId',physical.sku_id,'stockIdentityId',physical.id,
            'lotId',NULL,'locationId',physical.location_id,'custodianId',physical.custody_owner_id,
            'custodianKind','TRANSIT','condition','QUARANTINE','legalOwner',physical.legal_owner)
        OR (record->'source'->>'quantity')::bigint IS DISTINCT FROM 1::bigint OR record->'source'->>'unit' IS DISTINCT FROM 'EA'
        OR record->'source'->>'tracking' IS DISTINCT FROM 'SERIAL' OR record->'source'->>'serial' IS DISTINCT FROM physical.serial_number
        OR (record->'source'->>'balanceRevision')::bigint IS DISTINCT FROM balance.revision
        OR (balance.stock_identity_id,balance.sku_id,balance.quantity_base,balance.base_unit,balance.location_id,
            balance.custody_owner_id,balance.custody_owner_kind,balance.condition,balance.status,balance.legal_owner) IS DISTINCT FROM
            (physical.id,physical.sku_id,1::bigint,'EA'::varchar,physical.location_id,physical.custody_owner_id,
            'TRANSIT'::varchar,'QUARANTINE'::varchar,'QUARANTINE'::varchar,physical.legal_owner)
        OR (line.stock_identity_id,line.sku_id,line.quantity_base,line.base_unit,line.tracking,line.location_id,
            line.custodian_id,line.custodian_kind,line.condition,line.legal_owner,line.source_line_id) IS DISTINCT FROM
            (physical.id,physical.sku_id,1::bigint,'EA'::varchar,'SERIAL'::varchar,physical.location_id,physical.custody_owner_id,
            'TRANSIT'::varchar,'QUARANTINE'::varchar,physical.legal_owner,removal.id)
        OR line.lot_id IS NOT NULL OR (record->>'sourceLineId')::uuid IS DISTINCT FROM removal.id
        OR (initial->>'id')::uuid IS DISTINCT FROM target OR initial->>'state' IS DISTINCT FROM 'DRAFT'
        OR (initial->>'revision')::bigint IS DISTINCT FROM 0::bigint OR initial->>'origin' IS DISTINCT FROM intake.origin
        OR (initial->>'sourceDocumentId')::uuid IS DISTINCT FROM removal.id
        OR (initial->>'stockIdentityId')::uuid IS DISTINCT FROM physical.id OR (initial->>'skuId')::uuid IS DISTINCT FROM physical.sku_id
        OR initial->>'lotId' IS NOT NULL OR initial->>'baseUnit' IS DISTINCT FROM 'EA' OR initial->>'quantityBase' IS DISTINCT FROM '1'
        OR (initial->>'locationId')::uuid IS DISTINCT FROM physical.location_id
        OR initial->>'condition' IS DISTINCT FROM 'QUARANTINE' OR initial->>'legalOwner' IS DISTINCT FROM physical.legal_owner THEN
        RAISE EXCEPTION 'RETURN_ASSET_ORIGIN_BINDING' USING ERRCODE='23514';
    END IF;
    FOR operation IN SELECT * FROM inventory_operation WHERE tenant_id=scope AND document_id=target ORDER BY document_revision LOOP
        snapshot:=operation.original_body::jsonb;
        SELECT canonical_payload INTO canonical FROM inventory_command_identity WHERE tenant_id=scope AND id=operation.id;
        command:=canonical::jsonb;
        IF operation.document_revision<>expected_revision OR operation.resource_id<>target OR operation.resource_scope<>'return:'||target
            OR canonical IS NULL OR operation.payload_hash<>encode(sha256(convert_to(canonical,'UTF8')),'hex')
            OR (snapshot->>'revision')::bigint IS DISTINCT FROM expected_revision
            OR snapshot-ARRAY['revision','state','locationId','condition','recordedAt','inspection','repair','legalOwner'] IS DISTINCT FROM
                initial-ARRAY['revision','state','locationId','condition','recordedAt','inspection','repair','legalOwner'] THEN
            RAISE EXCEPTION 'RETURN_ASSET_OPERATION_BINDING' USING ERRCODE='23514'; END IF;
        
        IF operation.namespace='warehouse.return.reacquire' THEN
            PERFORM warehouse_assert_return_reacquisition_step(scope,target,operation.id,previous,snapshot);
            previous:=snapshot; expected_revision:=expected_revision+1; CONTINUE;
        END IF;
        IF snapshot->>'legalOwner' IS DISTINCT FROM coalesce(previous->>'legalOwner',initial->>'legalOwner') THEN
            RAISE EXCEPTION 'RETURN_OWNER_CHANGE_REQUIRES_APPROVED_TITLE' USING ERRCODE='23514'; END IF;
        IF operation.namespace IN ('warehouse.return.repair-dispatch','warehouse.return.repair-receive') THEN
            PERFORM warehouse_assert_return_repair_step(scope,target,operation.id,previous,snapshot);
            previous:=snapshot; expected_revision:=expected_revision+1; CONTINUE;
        END IF;
        IF snapshot->'repair' IS DISTINCT FROM previous->'repair' THEN
            RAISE EXCEPTION 'REPAIR_PROGRESS_IMMUTABLE' USING ERRCODE='23514'; END IF;
        IF expected_revision=0 THEN
            IF operation.namespace<>'warehouse.return.open' OR operation.actor_id<>document.actor_id
                OR command IS DISTINCT FROM record->'intake' OR snapshot IS DISTINCT FROM initial
                OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id) THEN
                RAISE EXCEPTION 'RETURN_ASSET_OPEN_BINDING' USING ERRCODE='23514'; END IF;
        ELSE
            released:=snapshot->>'state'='ACCEPTED';
            IF expected_revision=1 THEN
                IF operation.namespace<>'warehouse.return.receive' OR operation.actor_id<>document.actor_id
                    OR command IS DISTINCT FROM record->'intake' OR snapshot->>'condition' IS DISTINCT FROM 'QUARANTINE'
                    OR snapshot->>'state' IS DISTINCT FROM 'RECEIVED_IN_INSPECTION'
                    OR (snapshot->>'locationId')::uuid IS DISTINCT FROM intake.quarantine_location_id THEN
                    RAISE EXCEPTION 'RETURN_ASSET_RECEIPT_BINDING' USING ERRCODE='23514'; END IF;
                outgoing_owner:=removal.actor_id;
                outgoing_kind:='TRANSIT';
            ELSE
                outgoing_owner:=(previous->>'locationId')::uuid;
                outgoing_kind:='WAREHOUSE';
                inspection:=snapshot->'inspection';
                IF operation.namespace<>'warehouse.return.inspect' OR previous->>'state' IS DISTINCT FROM 'RECEIVED_IN_INSPECTION'
                    OR jsonb_typeof(inspection) IS DISTINCT FROM 'object'
                    OR command->>'id' IS DISTINCT FROM target::text OR command->'request' IS DISTINCT FROM inspection
                    OR (inspection->>'expectedRevision')::bigint IS DISTINCT FROM expected_revision-1
                    OR inspection->>'measuredQuantityBase' IS DISTINCT FROM '1'
                    OR nullif(btrim(inspection->>'evidenceReference'),'') IS NULL
                    OR snapshot->>'condition' IS DISTINCT FROM inspection->>'condition'
                    OR snapshot->>'locationId' IS DISTINCT FROM inspection->>'destinationLocationId'
                    OR snapshot->>'state' NOT IN ('ACCEPTED','RECEIVED_IN_INSPECTION')
                    OR snapshot->>'condition' NOT IN ('SERVICEABLE','QUARANTINE','DAMAGED')
                    OR released IS DISTINCT FROM (snapshot->>'condition'='SERVICEABLE' AND previous->>'legalOwner'='ISP')
                    OR inspection->>'observedSerial' IS DISTINCT FROM physical.serial_number
                    OR snapshot->>'condition'='SERVICEABLE' AND (inspection->>'resetConfirmed' IS DISTINCT FROM 'true'
                        OR nullif(btrim(inspection->>'resetEvidenceReference'),'') IS NULL) THEN
                    RAISE EXCEPTION 'RETURN_ASSET_INSPECTION_BINDING' USING ERRCODE='23514'; END IF;
            END IF;
            IF NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=(snapshot->>'locationId')::uuid
                AND (CASE WHEN released THEN kind='BIN' ELSE kind='QUARANTINE' END)) THEN
                RAISE EXCEPTION 'RETURN_ASSET_DESTINATION' USING ERRCODE='23514'; END IF;
            SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id;
            SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
            SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
            IF movement.id IS NULL OR movement.kind<>'RETURN' OR movement.state<>'APPLIED'
                OR movement.document_id<>target OR movement.document_revision<>expected_revision
                OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id)<>1
                OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
                OR outgoing.id IS NULL OR incoming.id IS NULL
                OR (outgoing.stock_identity_id,outgoing.sku_id,outgoing.lot_id,outgoing.quantity_base,outgoing.base_unit,
                    outgoing.document_line_id,outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,
                    outgoing.condition,outgoing.status,outgoing.legal_owner) IS DISTINCT FROM
                    (line.stock_identity_id,line.sku_id,line.lot_id,1::bigint,line.base_unit,line.id,
                    (previous->>'locationId')::uuid,outgoing_owner,outgoing_kind,previous->>'condition','QUARANTINE'::varchar,previous->>'legalOwner')
                OR (incoming.stock_identity_id,incoming.sku_id,incoming.lot_id,incoming.quantity_base,incoming.base_unit,
                    incoming.document_line_id,incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,
                    incoming.condition,incoming.status,incoming.legal_owner) IS DISTINCT FROM
                    (line.stock_identity_id,line.sku_id,line.lot_id,1::bigint,line.base_unit,line.id,
                    (snapshot->>'locationId')::uuid,(snapshot->>'locationId')::uuid,'WAREHOUSE'::varchar,
                    snapshot->>'condition',CASE WHEN released THEN 'AVAILABLE' ELSE 'QUARANTINE' END,snapshot->>'legalOwner')
                OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=operation.id
                    AND document_id=target AND document_revision=expected_revision AND event_kind='RETURN_RECEIVED') THEN
                RAISE EXCEPTION 'RETURN_ASSET_EXACT_POSTING' USING ERRCODE='23514'; END IF;
        END IF;
        previous:=snapshot;
        expected_revision:=expected_revision+1;
    END LOOP;
    IF expected_revision<2 OR expected_revision<>document.revision+1 OR document.state IS DISTINCT FROM
        (CASE WHEN snapshot->>'state'='ACCEPTED' THEN 'ACCEPTED' WHEN snapshot->>'state'='REPAIR' THEN 'REPAIR' ELSE 'RECEIVED_IN_INSPECTION' END) THEN
        RAISE EXCEPTION 'RETURN_ASSET_REVISION_GAP' USING ERRCODE='23514'; END IF;
END $function$;

CREATE OR REPLACE FUNCTION public.warehouse_assert_return_repair_step(scope uuid, target uuid, target_operation uuid, previous jsonb, snapshot jsonb)
 RETURNS void
 LANGUAGE plpgsql
AS $function$
DECLARE repair inventory_repair_case; intake inventory_return_case; operation inventory_operation;
    line inventory_document_line; movement inventory_movement; outgoing inventory_movement_leg; incoming inventory_movement_leg;
    command jsonb; request jsonb; progress jsonb; outbound boolean; source_owner uuid; source_kind text;
    destination_owner uuid; destination_kind text; expected_event text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO repair FROM inventory_repair_case WHERE tenant_id=scope AND id=(snapshot->'repair'->>'id')::uuid;
    SELECT * INTO intake FROM inventory_return_case WHERE tenant_id=scope AND id=target;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=scope AND id=$3;
    SELECT * INTO STRICT line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    SELECT canonical_payload::jsonb INTO command FROM inventory_command_identity WHERE tenant_id=scope AND id=$3;
    request:=command->'request';
    outbound:=operation.namespace='warehouse.return.repair-dispatch';
    IF repair.id IS NULL OR intake.origin IS DISTINCT FROM 'ASSET_REMOVAL' OR operation.id IS NULL
        OR operation.namespace NOT IN ('warehouse.return.repair-dispatch','warehouse.return.repair-receive')
        OR repair.return_document_id<>target OR repair.asset_id<>intake.stock_identity_id OR repair.legal_owner IS DISTINCT FROM previous->>'legalOwner'
        OR repair.legal_owner IS DISTINCT FROM snapshot->>'legalOwner'
        OR repair.legal_owner IS DISTINCT FROM repair.source_snapshot->>'legalOwner'
        OR repair.outbound_document_id IS DISTINCT FROM target OR repair.replacement_asset_id IS NOT NULL
        OR command->>'id' IS DISTINCT FROM target::text OR command->'request' IS NULL
        OR (request->>'expectedRevision')::bigint IS DISTINCT FROM operation.document_revision-1
        OR request->>'observedSerial' IS DISTINCT FROM intake.body::jsonb->'source'->>'serial'
        OR nullif(btrim(request->>'evidenceReference'),'') IS NULL OR nullif(btrim(request->>'vendorReference'),'') IS NULL
        OR snapshot->'inspection' IS DISTINCT FROM previous->'inspection'
        OR snapshot->>'condition' IS DISTINCT FROM previous->>'condition'
        OR repair.vendor_id IS DISTINCT FROM (repair.dispatch_request->>'vendorId')::uuid
        OR repair.vendor_reference IS DISTINCT FROM repair.dispatch_request->>'vendorReference'
        OR repair.source_snapshot->>'id' IS DISTINCT FROM target::text
        OR (repair.source_snapshot->>'revision')::bigint IS DISTINCT FROM repair.source_return_revision
        OR NOT EXISTS(SELECT FROM inventory_operation source WHERE source.tenant_id=scope AND source.document_id=target
            AND source.document_revision=repair.source_return_revision AND source.original_body::jsonb=repair.source_snapshot) THEN
        RAISE EXCEPTION 'REPAIR_SOURCE_BINDING' USING ERRCODE='23514'; END IF;
    progress:=jsonb_build_object('id',repair.id,'vendorId',repair.vendor_id,'vendorReference',repair.vendor_reference,
        'repairLocationId',(repair.dispatch_request->>'repairLocationId')::uuid,'dispatchRevision',repair.source_return_revision+1,
        'returnedRevision',NULL,'result',NULL,'receiptReference',NULL);
    IF outbound THEN
        IF previous IS DISTINCT FROM repair.source_snapshot OR previous->>'state' IS DISTINCT FROM 'RECEIVED_IN_INSPECTION'
            OR previous->'inspection' IS NULL OR previous->'inspection'='null'::jsonb OR previous->'repair' IS NOT NULL
            OR operation.document_revision<>repair.source_return_revision+1 OR request IS DISTINCT FROM repair.dispatch_request
            OR snapshot->>'state' IS DISTINCT FROM 'REPAIR' OR snapshot->>'locationId' IS DISTINCT FROM request->>'repairLocationId'
            OR NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=(request->>'repairLocationId')::uuid AND kind='TRANSIT') THEN
            RAISE EXCEPTION 'REPAIR_DISPATCH_BINDING' USING ERRCODE='23514'; END IF;
        source_owner:=(previous->>'locationId')::uuid; source_kind:='WAREHOUSE';
        destination_owner:=repair.vendor_id; destination_kind:='REPAIR'; expected_event:='DISPATCHED';
    ELSE
        IF previous->>'state' IS DISTINCT FROM 'REPAIR' OR previous->'repair' IS DISTINCT FROM progress
            OR repair.receive_revision IS DISTINCT FROM operation.document_revision
            OR repair.receive_revision<>repair.source_return_revision+2 OR request IS DISTINCT FROM repair.receive_request
            OR repair.result IS DISTINCT FROM request->>'result' OR repair.result NOT IN ('REPAIRED','UNREPAIRED')
            OR snapshot->>'state' IS DISTINCT FROM 'RECEIVED_IN_INSPECTION'
            OR snapshot->>'locationId' IS DISTINCT FROM request->>'quarantineLocationId'
            OR NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=(request->>'quarantineLocationId')::uuid AND kind='QUARANTINE') THEN
            RAISE EXCEPTION 'REPAIR_RECEIPT_BINDING' USING ERRCODE='23514'; END IF;
        progress:=progress||jsonb_build_object('returnedRevision',repair.receive_revision,'result',repair.result,
            'receiptReference',request->>'vendorReference');
        source_owner:=repair.vendor_id; source_kind:='REPAIR';
        destination_owner:=(snapshot->>'locationId')::uuid; destination_kind:='WAREHOUSE'; expected_event:='RETURN_RECEIVED';
    END IF;
    IF snapshot->'repair' IS DISTINCT FROM progress THEN
        RAISE EXCEPTION 'REPAIR_RESPONSE_BINDING' USING ERRCODE='23514'; END IF;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=$3;
    SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
    SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
    IF movement.id IS NULL OR movement.kind<>'REPAIR' OR movement.state<>'APPLIED'
        OR movement.document_id<>target OR movement.document_revision<>operation.document_revision
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=$3)<>1
        OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
        OR outgoing.id IS NULL OR incoming.id IS NULL
        OR (outgoing.stock_identity_id,outgoing.sku_id,outgoing.lot_id,outgoing.quantity_base,outgoing.base_unit,
            outgoing.document_line_id,outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,
            outgoing.condition,outgoing.status,outgoing.legal_owner) IS DISTINCT FROM
            (line.stock_identity_id,line.sku_id,line.lot_id,1::bigint,line.base_unit,line.id,
            (previous->>'locationId')::uuid,source_owner,source_kind,previous->>'condition','QUARANTINE'::varchar,repair.legal_owner)
        OR (incoming.stock_identity_id,incoming.sku_id,incoming.lot_id,incoming.quantity_base,incoming.base_unit,
            incoming.document_line_id,incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,
            incoming.condition,incoming.status,incoming.legal_owner) IS DISTINCT FROM
            (line.stock_identity_id,line.sku_id,line.lot_id,1::bigint,line.base_unit,line.id,
            (snapshot->>'locationId')::uuid,destination_owner,destination_kind,snapshot->>'condition','QUARANTINE'::varchar,repair.legal_owner)
        OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=$3 AND document_id=target
            AND document_revision=operation.document_revision AND event_kind=expected_event) THEN
        RAISE EXCEPTION 'REPAIR_EXACT_POSTING' USING ERRCODE='23514'; END IF;
END $function$;

CREATE OR REPLACE FUNCTION public.warehouse_assert_return_title_request(scope uuid, target uuid)
 RETURNS void
 LANGUAGE plpgsql
AS $function$
DECLARE request inventory_return_title_request; document inventory_document; line inventory_document_line; body jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO request FROM inventory_return_title_request WHERE tenant_id=scope AND id=target;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    body:=request.snapshot::jsonb;
    IF request.id IS NULL OR document.id IS NULL OR line.id IS NULL OR request.origin_snapshot IS NULL
        OR document.kind<>'RETURN_TITLE' OR NOT (
            document.state='POSTED' AND document.revision=1 AND document.approval_disposition IS NULL OR
            document.state='DRAFT' AND (document.revision=0 AND document.approval_disposition IS NULL OR
                document.revision=1 AND document.approval_disposition='REWORK_REQUIRED' AND EXISTS(
                    SELECT FROM inventory_approval WHERE tenant_id=scope AND source_document_id=target
                        AND source_document_revision=0 AND status='REWORK_REQUIRED'))) 
        OR (document.actor_id,document.customer_id,document.work_order_id,document.source_document_id,document.source_revision)
            IS DISTINCT FROM (request.actor_id,request.customer_id,request.work_order_id,request.return_id,request.source_return_revision)
        OR document.code IS DISTINCT FROM body->>'code' OR document.source_reference IS DISTINCT FROM request.id::text
        OR document.reason IS DISTINCT FROM body#>>'{request,reason}'
        OR document.work_order_revision IS DISTINCT FROM (body->>'workOrderRevision')::bigint
        OR document.authority_epoch IS DISTINCT FROM (body->>'authorityEpoch')::bigint
        OR document.cutover_epoch IS DISTINCT FROM (body->>'cutoverEpoch')::bigint
        OR (SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=target)<>1
        OR (line.id,line.document_revision,line.line_number,line.stock_identity_id,line.sku_id,line.base_unit,line.tracking,line.quantity_base,
            line.source_line_id,line.location_id,line.custodian_id,line.custodian_kind,line.condition,line.legal_owner)
            IS DISTINCT FROM (request.id,0::bigint,1,request.asset_id,(body#>>'{source,dimension,skuId}')::uuid,'EA'::varchar,'SERIAL'::varchar,1::bigint,
                request.return_id,(body#>>'{source,dimension,locationId}')::uuid,(body#>>'{source,dimension,custodianId}')::uuid,
                'WAREHOUSE'::varchar,body#>>'{source,dimension,condition}','CUSTOMER'::varchar)
        OR line.lot_id IS NOT NULL THEN
        RAISE EXCEPTION 'RETURN_TITLE_REQUEST_DOCUMENT_BINDING' USING ERRCODE='23514'; END IF;
    IF document.state='POSTED' THEN
        PERFORM warehouse_assert_return_title_effect(scope,target);
    ELSIF EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=target)
        OR EXISTS(SELECT FROM inventory_return_title_effect WHERE tenant_id=scope AND request_id=target) THEN
        RAISE EXCEPTION 'RETURN_TITLE_DRAFT_HAS_NO_EFFECT' USING ERRCODE='23514'; END IF;
END $function$;

-- Every TITLE_CORRECTION must belong to exactly one supported title workflow.
DO $$ DECLARE definition text; old_clause text; new_clause text;
BEGIN
    definition:=pg_get_functiondef('warehouse_title_final_guard()'::regprocedure);
    old_clause:='SELECT FROM inventory_asset_title_transfer WHERE tenant_id=scope AND inventory_asset_title_transfer.operation_id=target_operation)';
    new_clause:='SELECT FROM inventory_asset_title_transfer WHERE tenant_id=scope AND inventory_asset_title_transfer.operation_id=target_operation
        UNION ALL SELECT FROM inventory_return_title_effect WHERE tenant_id=scope AND posting_operation_id=target_operation)';
    IF position(old_clause IN definition)=0 THEN RAISE EXCEPTION 'title posting source guard changed'; END IF;
    EXECUTE replace(definition,old_clause,new_clause);
END $$;
