CREATE TABLE inventory_disposition_effect (
    tenant_id uuid NOT NULL, request_id uuid NOT NULL, approval_id uuid NOT NULL,
    posting_operation_id uuid NOT NULL, return_operation_id uuid NOT NULL, return_id uuid NOT NULL,
    source_return_revision bigint NOT NULL, return_revision bigint NOT NULL,
    source_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,request_id), UNIQUE(tenant_id,approval_id), UNIQUE(tenant_id,posting_operation_id),
    UNIQUE(tenant_id,return_operation_id), UNIQUE(tenant_id,return_id,source_return_revision),
    CHECK(return_revision=source_return_revision+1),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_disposition_request(tenant_id,id),
    FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id),
    FOREIGN KEY(tenant_id,return_id) REFERENCES inventory_return_case(tenant_id,id),
    FOREIGN KEY(tenant_id,posting_operation_id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,return_operation_id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE inventory_disposition_effect ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_disposition_effect FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_disposition_effect
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_disposition_effect
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $migration$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_disposition_effect TO warehouse_app;
    END IF;
END $migration$;

CREATE FUNCTION warehouse_capture_disposition_effect() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE request inventory_disposition_request; document inventory_document; source inventory_document;
    approval inventory_approval; balance inventory_balance_projection; segment inventory_segment; asset inventory_serialized_asset;
    view jsonb; current_source jsonb; revision bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT request FROM inventory_disposition_request WHERE tenant_id=NEW.tenant_id AND id=NEW.request_id;
    SELECT warehouse_revision INTO revision FROM work_order WHERE tenant_id=NEW.tenant_id
        AND id=(request.snapshot::jsonb#>>'{context,workOrderId}')::uuid FOR SHARE;
    SELECT * INTO source FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.return_id FOR UPDATE;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.request_id;
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=NEW.tenant_id AND id=NEW.approval_id;
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=request.stock_identity_id FOR UPDATE;
    SELECT * INTO segment FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=request.stock_identity_id FOR UPDATE;
    SELECT * INTO balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id
        AND id=(request.source_snapshot#>>'{balance,id}')::uuid FOR UPDATE;
    SELECT original_body::jsonb INTO view FROM inventory_operation WHERE tenant_id=NEW.tenant_id
        AND document_id=source.id AND document_revision=source.revision;
    current_source:=jsonb_build_object('document',to_jsonb(source),'returnView',view,'balance',to_jsonb(balance),
        'segment',to_jsonb(segment),'asset',CASE WHEN asset.id IS NULL THEN 'null'::jsonb ELSE to_jsonb(asset) END,
        'cost',request.source_snapshot->'cost','workOrderId',request.source_snapshot->'workOrderId','workOrderRevision',revision);
    IF NEW.created_xid<>pg_current_xact_id() OR current_source IS DISTINCT FROM request.source_snapshot
        OR NEW.return_id<>request.source_document_id OR NEW.source_return_revision<>source.revision
        OR NEW.source_return_revision IS DISTINCT FROM (request.snapshot::jsonb#>>'{input,expectedRevision}')::bigint
        OR source.state<>'RECEIVED_IN_INSPECTION' OR document.state<>'DRAFT' OR document.revision<>0
        OR approval.id IS NULL OR approval.status<>'APPROVED' OR approval.source_document_id<>request.id
        OR approval.source_document_revision<>0 OR approval.business_action<>document.kind
        OR approval.source_snapshot::jsonb->'disposition' IS DISTINCT FROM request.snapshot::jsonb
        OR EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND asset_id=request.stock_identity_id AND ended_at IS NULL)
        OR EXISTS(SELECT FROM inventory_reservation WHERE tenant_id=NEW.tenant_id AND stock_identity_id=request.stock_identity_id
            AND state='OPEN' AND (reserved_unpicked_base>0 OR reserved_picked_base>0)) THEN
        RAISE EXCEPTION 'DISPOSITION_CURRENT_APPROVED_SOURCE_REQUIRED' USING ERRCODE='23514'; END IF;
    NEW.source_snapshot:=current_source;
    RETURN NEW;
END $function$;
CREATE TRIGGER warehouse_disposition_effect_capture BEFORE INSERT ON inventory_disposition_effect
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_disposition_effect();

-- Reconcile current positions for both EA assets and measured MM segments.
-- A later legitimate split may retire a segment only after its ledger is zero.
CREATE FUNCTION warehouse_assert_disposition_position(scope uuid, identity uuid) RETURNS void LANGUAGE plpgsql AS $function$
DECLARE piece inventory_segment; asset inventory_serialized_asset; balance inventory_balance_projection;
    ledger record; positions integer:=0; quantity numeric:=0;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO piece FROM inventory_segment WHERE tenant_id=scope AND id=identity;
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=scope AND id=identity;
    IF piece.id IS NULL OR piece.warehouse_admission<>'VERIFIED' THEN
        RAISE EXCEPTION 'DISPOSITION_CURRENT_SOURCE_REQUIRED' USING ERRCODE='23514'; END IF;
    FOR ledger IN SELECT leg.sku_id,leg.lot_id,leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,
        leg.condition,leg.legal_owner,leg.base_unit,
        sum(CASE leg.direction WHEN 'IN' THEN leg.quantity_base::numeric ELSE -leg.quantity_base::numeric END) quantity_base,
        (array_agg(leg.status ORDER BY leg.revision DESC,movement.server_received_at DESC) FILTER(WHERE leg.direction='IN'))[1] status
        FROM inventory_movement_leg leg JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
        WHERE leg.tenant_id=scope AND leg.stock_identity_id=identity AND leg.warehouse_admission='VERIFIED'
            AND movement.state='APPLIED' AND leg.status<>'RECEIPT_SOURCE'
        GROUP BY leg.sku_id,leg.lot_id,leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,leg.condition,leg.legal_owner,leg.base_unit
    LOOP
        IF ledger.quantity_base=0 THEN CONTINUE; END IF;
        positions:=positions+1; quantity:=quantity+ledger.quantity_base;
        SELECT * INTO balance FROM inventory_balance_projection position WHERE position.tenant_id=scope
            AND position.stock_identity_id=identity AND position.sku_id=ledger.sku_id AND position.lot_id IS NOT DISTINCT FROM ledger.lot_id
            AND position.location_id=ledger.location_id AND position.custody_owner_id=ledger.custody_owner_id
            AND position.custody_owner_kind=ledger.custody_owner_kind AND position.condition=ledger.condition
            AND position.legal_owner=ledger.legal_owner AND position.warehouse_admission='VERIFIED';
        IF ledger.quantity_base<0 OR balance.id IS NULL OR balance.quantity_base<>ledger.quantity_base
            OR balance.base_unit<>ledger.base_unit OR balance.base_unit<>piece.base_unit OR balance.status IS DISTINCT FROM ledger.status
            OR asset.id IS NOT NULL AND (asset.sku_id,asset.location_id,asset.custody_owner_id,asset.custody_owner_kind,
                asset.condition,asset.legal_owner,asset.status) IS DISTINCT FROM
                (ledger.sku_id,ledger.location_id,ledger.custody_owner_id,ledger.custody_owner_kind,ledger.condition,ledger.legal_owner,ledger.status) THEN
            RAISE EXCEPTION 'DISPOSITION_CURRENT_POSITION_REQUIRES_POSTING' USING ERRCODE='23514'; END IF;
    END LOOP;
    IF positions<>(SELECT count(*) FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=identity AND quantity_base>0)
        OR piece.state='ACTIVE' AND quantity<>piece.quantity_base OR piece.state<>'ACTIVE' AND quantity<>0 THEN
        RAISE EXCEPTION 'DISPOSITION_CURRENT_POSITION_REQUIRES_POSTING' USING ERRCODE='23514'; END IF;
END $function$;

CREATE FUNCTION warehouse_assert_disposition_effect(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $function$
DECLARE effect inventory_disposition_effect; request inventory_disposition_request; approval inventory_approval;
    posting inventory_operation; transition inventory_operation; movement inventory_movement;
    outgoing inventory_movement_leg; incoming inventory_movement_leg;
    body jsonb; input jsonb; dimension jsonb; previous jsonb; view jsonb; canonical text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO effect FROM inventory_disposition_effect WHERE tenant_id=scope AND request_id=target;
    SELECT * INTO request FROM inventory_disposition_request WHERE tenant_id=scope AND id=target;
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=scope AND id=effect.approval_id;
    SELECT * INTO posting FROM inventory_operation WHERE tenant_id=scope AND id=effect.posting_operation_id;
    SELECT * INTO transition FROM inventory_operation WHERE tenant_id=scope AND id=effect.return_operation_id;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=posting.id;
    SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
    SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
    body:=request.snapshot::jsonb; input:=body->'input'; dimension:=body#>'{source,dimension}'; previous:=body#>'{returned,view}';
    view:=transition.original_body::jsonb;
    SELECT canonical_payload INTO canonical FROM inventory_command_identity WHERE tenant_id=scope AND id=transition.id;
    IF effect.request_id IS NULL OR request.id IS NULL OR approval.id IS NULL OR posting.id IS NULL OR transition.id IS NULL
        OR effect.source_snapshot IS DISTINCT FROM request.source_snapshot
        OR effect.return_id<>request.source_document_id OR effect.source_return_revision IS DISTINCT FROM (input->>'expectedRevision')::bigint
        OR effect.return_revision<>effect.source_return_revision+1
        OR approval.status<>'APPROVED' OR approval.business_action IS DISTINCT FROM input->>'action'
        OR approval.source_document_id<>request.id OR approval.source_document_revision<>0
        OR approval.source_snapshot::jsonb->'disposition' IS DISTINCT FROM body
        OR posting.namespace<>'warehouse.approval.effect' OR posting.operation_key<>approval.id::text
        OR posting.resource_id<>request.id OR posting.document_id<>request.id OR posting.document_revision<>1
        OR posting.business_action IS DISTINCT FROM input->>'action' OR posting.original_status<>200
        OR posting.payload_hash<>approval.source_snapshot_hash OR posting.original_body IS DISTINCT FROM approval.terminal_body
        OR posting.cutover_epoch IS DISTINCT FROM (body->>'cutoverEpoch')::bigint
        OR NOT EXISTS(SELECT FROM inventory_command_identity WHERE tenant_id=scope AND id=posting.id AND canonical_payload::jsonb=approval.source_snapshot::jsonb)
        OR posting.resource_scope<>'approval:'||approval.id OR approval.requester_id<>request.actor_id
        OR NOT EXISTS(SELECT FROM inventory_approval_effect WHERE tenant_id=scope AND approval_id=approval.id
            AND posting_operation_id=posting.id AND source_document_id=request.id AND original_body=posting.original_body)
        OR EXISTS(SELECT FROM inventory_approval_decision WHERE tenant_id=scope AND approval_id=approval.id
            AND (approver_id=request.actor_id OR delegated_from=request.actor_id))
        OR movement.id IS NULL OR movement.state<>'APPLIED' OR movement.kind IS DISTINCT FROM input->>'action'
        OR movement.document_id<>request.id OR movement.document_revision<>1
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=posting.id)<>1
        OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
        OR outgoing.id IS NULL OR incoming.id IS NULL
        OR (outgoing.sku_id,outgoing.stock_identity_id,outgoing.lot_id,outgoing.quantity_base,outgoing.base_unit,
            outgoing.document_line_id,outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,outgoing.condition,outgoing.legal_owner,outgoing.status)
            IS DISTINCT FROM ((dimension->>'skuId')::uuid,request.stock_identity_id,(dimension->>'lotId')::uuid,(input->>'quantityBase')::bigint,
                input->>'baseUnit',request.id,(dimension->>'locationId')::uuid,(dimension->>'custodianId')::uuid,
                dimension->>'custodianKind',dimension->>'condition','ISP'::varchar,'QUARANTINE'::varchar)
        OR (incoming.sku_id,incoming.stock_identity_id,incoming.lot_id,incoming.quantity_base,incoming.base_unit,
            incoming.document_line_id,incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,incoming.condition,incoming.legal_owner,incoming.status)
            IS DISTINCT FROM (outgoing.sku_id,outgoing.stock_identity_id,outgoing.lot_id,outgoing.quantity_base,outgoing.base_unit,request.id,
                (input->>'destinationLocationId')::uuid,(input->>'destinationLocationId')::uuid,
                CASE WHEN input->>'action'='LOSS' THEN 'LOST' ELSE 'DISPOSED' END,
                CASE WHEN input->>'action'='LOSS' THEN outgoing.condition ELSE 'SCRAP' END,'ISP'::varchar,
                CASE WHEN input->>'action'='LOSS' THEN 'LOST' ELSE 'DISPOSED' END)
        OR (SELECT count(*) FROM inventory_outbox WHERE tenant_id=scope AND operation_id=posting.id)<>1
        OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=posting.id
            AND document_id=request.id AND document_revision=1 AND event_kind='DISPOSED')
        OR EXISTS(SELECT FROM inventory_customer_material_fact WHERE tenant_id=scope AND posting_id=movement.id) THEN
        RAISE EXCEPTION 'DISPOSITION_EXACT_APPROVED_POSTING_REQUIRED' USING ERRCODE='23514'; END IF;
    IF transition.namespace<>'warehouse.return.dispose' OR transition.operation_key<>approval.id::text
        OR transition.resource_id<>effect.return_id OR transition.resource_scope<>'return:'||effect.return_id
        OR transition.document_id<>effect.return_id OR transition.document_revision<>effect.return_revision
        OR transition.business_action<>'DISPOSED' OR transition.original_status<>200 OR transition.actor_id<>posting.actor_id
        OR transition.cutover_epoch<>posting.cutover_epoch OR transition.authority_epoch<>posting.authority_epoch
        OR transition.created_at IS DISTINCT FROM posting.created_at
        OR canonical IS NULL OR transition.payload_hash<>encode(sha256(convert_to(canonical,'UTF8')),'hex')
        OR canonical::jsonb IS DISTINCT FROM jsonb_build_object('returnId',effect.return_id,'requestId',request.id,
            'approvalId',approval.id,'sourceRevision',effect.source_return_revision)
        OR view-ARRAY['revision','state','locationId','condition','recordedAt'] IS DISTINCT FROM
            previous-ARRAY['revision','state','locationId','condition','recordedAt']
        OR (view->>'revision')::bigint IS DISTINCT FROM effect.return_revision
        OR view->>'state' IS DISTINCT FROM (CASE WHEN input->>'action'='LOSS' THEN 'LOST' ELSE 'SCRAP' END)
        OR view->>'locationId' IS DISTINCT FROM input->>'destinationLocationId'
        OR view->>'condition' IS DISTINCT FROM incoming.condition
        OR (view->>'recordedAt')::timestamptz IS DISTINCT FROM transition.created_at
        OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND operation_id=transition.id)
        OR EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=transition.id)
        OR NOT EXISTS(SELECT FROM inventory_document WHERE tenant_id=scope AND id=effect.return_id AND revision>=effect.return_revision)
        OR NOT EXISTS(SELECT FROM inventory_document WHERE tenant_id=scope AND id=request.id AND state='POSTED' AND revision=1) THEN
        RAISE EXCEPTION 'DISPOSITION_EXACT_RETURN_TRANSITION_REQUIRED' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_disposition_position(scope,request.stock_identity_id);
END $function$;

CREATE FUNCTION warehouse_assert_return_disposition_step(scope uuid,target uuid,target_operation uuid,previous jsonb,snapshot jsonb)
RETURNS void LANGUAGE plpgsql AS $function$
DECLARE effect inventory_disposition_effect; request inventory_disposition_request; operation inventory_operation;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO effect FROM inventory_disposition_effect WHERE tenant_id=scope AND return_operation_id=target_operation;
    SELECT * INTO request FROM inventory_disposition_request WHERE tenant_id=scope AND id=effect.request_id;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=scope AND id=target_operation;
    IF effect.request_id IS NULL OR effect.return_id<>target
        OR previous IS DISTINCT FROM request.snapshot::jsonb#>'{returned,view}'
        OR snapshot IS DISTINCT FROM operation.original_body::jsonb THEN
        RAISE EXCEPTION 'DISPOSITION_RETURN_HISTORY_REQUIRED' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_disposition_effect(scope,effect.request_id);
END $function$;

-- Extend the existing return validators without bypassing intake, inspection,
-- repair or title history. The new step is nonphysical and binds the one effect.
DO $migration$ DECLARE signature text; definition text; anchor text;
BEGIN
    FOREACH signature IN ARRAY ARRAY['warehouse_assert_return(uuid,uuid)','warehouse_assert_returned_asset(uuid,uuid)'] LOOP
        definition:=pg_get_functiondef(signature::regprocedure);
        anchor:='IF expected_revision=0 THEN';
        IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'return history entry changed'; END IF;
        definition:=replace(definition,anchor,$body$
        IF operation.namespace='warehouse.return.dispose' THEN
            PERFORM warehouse_assert_return_disposition_step(scope,target,operation.id,previous,snapshot);
            previous:=snapshot; expected_revision:=expected_revision+1; CONTINUE;
        END IF;
        IF expected_revision=0 THEN$body$);
        anchor:='WHEN snapshot->>''state''=''ACCEPTED'' THEN ''ACCEPTED''';
        IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'return terminal state changed'; END IF;
        EXECUTE replace(definition,anchor,
            'WHEN snapshot->>''state'' IN (''SCRAP'',''LOST'') THEN snapshot->>''state'' WHEN snapshot->>''state''=''ACCEPTED'' THEN ''ACCEPTED''');
    END LOOP;
    definition:=pg_get_functiondef('warehouse_assert_disposition_request(uuid,uuid)'::regprocedure);
    anchor:='OR document.state<>''DRAFT'' OR document.revision<>0 OR document.approval_disposition IS NOT NULL';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'disposition draft state changed'; END IF;
    definition:=replace(definition,anchor,$body$
        OR NOT (document.state='DRAFT' AND document.revision=0 AND document.approval_disposition IS NULL OR
            document.state='DRAFT' AND document.revision=1 AND document.approval_disposition='REWORK_REQUIRED' AND EXISTS(
                SELECT FROM inventory_approval WHERE tenant_id=scope AND source_document_id=target
                    AND source_document_revision=0 AND status='REWORK_REQUIRED') OR
            document.state='POSTED' AND document.revision=1 AND document.approval_disposition IS NULL)$body$);
    anchor:=$body$OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=target)
        OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=target)$body$;
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'disposition draft physical guard changed'; END IF;
    definition:=replace(definition,anchor,'');
    anchor:='END $function$';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'disposition function terminator changed'; END IF;
    EXECUTE replace(definition,anchor,$body$
    IF document.state='DRAFT' THEN
        IF EXISTS(SELECT FROM inventory_disposition_effect WHERE tenant_id=scope AND request_id=target)
            OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=target)
            OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=target) THEN
            RAISE EXCEPTION 'DISPOSITION_DRAFT_HAS_NO_EFFECT' USING ERRCODE='23514'; END IF;
    ELSE
        PERFORM warehouse_assert_disposition_effect(scope,target);
    END IF;
END $function$$body$);

    SELECT pg_get_expr(conbin,conrelid) INTO STRICT definition FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND conname='inventory_document_check2';
    ALTER TABLE inventory_document DROP CONSTRAINT inventory_document_check2;
    EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT inventory_document_check2 CHECK ((%s) OR (kind=''RETURN'' AND state=''LOST''))',definition);
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF position('CASE OLD.kind' IN definition)=0 OR position('(''RECEIVED_IN_INSPECTION'',''SCRAP'')' IN definition)=0 THEN
        RAISE EXCEPTION 'disposition lifecycle entry changed'; END IF;
    definition:=replace(definition,'CASE OLD.kind',
        'CASE OLD.kind WHEN ''LOSS'' THEN (OLD.state,NEW.state)=(''DRAFT'',''POSTED'') WHEN ''SCRAP'' THEN (OLD.state,NEW.state)=(''DRAFT'',''POSTED'')');
    EXECUTE replace(definition,'(''RECEIVED_IN_INSPECTION'',''SCRAP'')',
        '(''RECEIVED_IN_INSPECTION'',''SCRAP''),(''RECEIVED_IN_INSPECTION'',''LOST'')');

    definition:=pg_get_functiondef('warehouse_approval_posting_guard()'::regprocedure);
    anchor:='(approval.business_action=''RECEIPT'' AND NEW.kind=''RECEIVE'')';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'approval physical binding changed'; END IF;
    EXECUTE replace(definition,anchor,$body$(approval.business_action='RECEIPT' AND NEW.kind='RECEIVE') OR
        (approval.business_action IN ('LOSS','SCRAP') AND NEW.kind=approval.business_action AND EXISTS(
            SELECT FROM inventory_disposition_effect WHERE tenant_id=NEW.tenant_id AND request_id=NEW.document_id
                AND approval_id=approval.id AND posting_operation_id=NEW.operation_id AND created_xid=pg_current_xact_id()))$body$);
END $migration$;

CREATE OR REPLACE FUNCTION warehouse_disposition_final_guard() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE value jsonb; scope uuid; target uuid; document uuid; operation uuid; approval uuid; identity uuid; movement uuid;
BEGIN
    FOR value IN SELECT item FROM jsonb_array_elements(CASE TG_OP WHEN 'INSERT' THEN jsonb_build_array(to_jsonb(NEW))
        WHEN 'DELETE' THEN jsonb_build_array(to_jsonb(OLD)) ELSE jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW)) END) item LOOP
        scope:=(value->>'tenant_id')::uuid;
        PERFORM warehouse_assert_deferred_scope(scope);
        document:=CASE WHEN TG_TABLE_NAME IN ('inventory_disposition_request','inventory_document') THEN (value->>'id')::uuid
            ELSE (value->>'document_id')::uuid END;
        operation:=CASE WHEN TG_TABLE_NAME IN ('inventory_operation','inventory_command_identity') THEN (value->>'id')::uuid
            ELSE coalesce((value->>'operation_id')::uuid,(value->>'posting_operation_id')::uuid) END;
        approval:=CASE WHEN TG_TABLE_NAME='inventory_approval' THEN (value->>'id')::uuid ELSE (value->>'approval_id')::uuid END;
        identity:=CASE WHEN TG_TABLE_NAME IN ('inventory_segment','inventory_serialized_asset') THEN (value->>'id')::uuid
            ELSE (value->>'stock_identity_id')::uuid END;
        movement:=CASE WHEN TG_TABLE_NAME='inventory_movement' THEN (value->>'id')::uuid
            ELSE coalesce((value->>'movement_id')::uuid,(value->>'posting_id')::uuid) END;
        FOR target IN SELECT request.id FROM inventory_disposition_request request
            LEFT JOIN inventory_disposition_effect effect ON effect.tenant_id=request.tenant_id AND effect.request_id=request.id
            WHERE request.tenant_id=scope AND (request.id=document OR request.source_document_id=document
                OR request.id=(value->>'request_id')::uuid OR request.stock_identity_id=identity
                OR effect.posting_operation_id=operation OR effect.return_operation_id=operation OR effect.approval_id=approval
                OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND id=movement AND operation_id=effect.posting_operation_id)) LOOP
            PERFORM warehouse_assert_disposition_request(scope,target);
        END LOOP;
        IF TG_TABLE_NAME='inventory_document' AND value->>'kind' IN ('LOSS','SCRAP') AND value->>'state'='POSTED'
            AND NOT EXISTS(SELECT FROM inventory_disposition_request WHERE tenant_id=scope AND id=document) THEN
            RAISE EXCEPTION 'DISPOSITION_REQUEST_REQUIRED' USING ERRCODE='23514'; END IF;
    END LOOP;
    RETURN NULL;
END $function$;
DO $migration$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_disposition_effect','inventory_command_identity','inventory_movement_leg',
        'inventory_segment','inventory_serialized_asset','inventory_balance_projection','inventory_approval','inventory_approval_decision',
        'inventory_approval_effect','inventory_outbox','inventory_customer_material_fact'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_disposition_final AFTER INSERT OR UPDATE OR DELETE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_disposition_final_guard()',table_name);
    END LOOP;
END $migration$;

-- A return is historical movement; its subsequent approved disposal settles
-- the obligation exactly once and does not add another issued disposition.
CREATE OR REPLACE FUNCTION warehouse_material_settled_return_base(scope uuid,issue_line uuid) RETURNS bigint LANGUAGE plpgsql AS $function$
DECLARE amount numeric;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT coalesce(sum(residual.quantity_base::numeric),0) INTO amount
    FROM inventory_material_residual residual
    WHERE residual.tenant_id=scope AND residual.issue_line_id=$2 AND residual.purpose='RETURN'
        AND EXISTS(SELECT FROM inventory_material_residual_ack WHERE tenant_id=scope AND residual_id=residual.id)
        AND EXISTS(SELECT FROM inventory_return_case intake
            JOIN inventory_document document ON document.tenant_id=intake.tenant_id AND document.id=intake.id
            JOIN inventory_operation operation ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id
                AND operation.document_revision=document.revision
            WHERE intake.tenant_id=scope AND intake.source_document_id=residual.id AND intake.origin='MATERIAL_RESIDUAL'
                AND intake.stock_identity_id=residual.transit_identity_id AND document.kind='RETURN'
                AND operation.original_body::jsonb->>'quantityBase'=residual.quantity_base::text
                AND (document.state='ACCEPTED' AND operation.namespace='warehouse.return.inspect'
                    AND operation.original_body::jsonb->>'state'='ACCEPTED'
                    AND EXISTS(SELECT FROM inventory_movement movement JOIN inventory_movement_leg incoming
                        ON incoming.tenant_id=movement.tenant_id AND incoming.movement_id=movement.id
                        WHERE movement.tenant_id=scope AND movement.operation_id=operation.id AND movement.state='APPLIED'
                            AND movement.kind='RETURN' AND incoming.direction='IN' AND incoming.stock_identity_id=intake.stock_identity_id
                            AND incoming.quantity_base=residual.quantity_base AND incoming.base_unit=residual.base_unit
                            AND incoming.condition='SERVICEABLE' AND incoming.status='AVAILABLE' AND incoming.legal_owner='ISP')
                OR document.state IN ('SCRAP','LOST') AND operation.namespace='warehouse.return.dispose'
                    AND operation.original_body::jsonb->>'state'=document.state
                    AND EXISTS(SELECT FROM inventory_disposition_effect effect
                        JOIN inventory_movement movement ON movement.tenant_id=effect.tenant_id AND movement.operation_id=effect.posting_operation_id
                        JOIN inventory_movement_leg incoming ON incoming.tenant_id=movement.tenant_id AND incoming.movement_id=movement.id
                        WHERE effect.tenant_id=scope AND effect.return_id=intake.id AND effect.return_operation_id=operation.id
                            AND effect.return_revision=document.revision AND movement.state='APPLIED'
                            AND movement.kind=(CASE document.state WHEN 'SCRAP' THEN 'SCRAP' ELSE 'LOSS' END)
                            AND incoming.direction='IN' AND incoming.stock_identity_id=intake.stock_identity_id
                            AND incoming.quantity_base=residual.quantity_base AND incoming.base_unit=residual.base_unit AND incoming.legal_owner='ISP'
                            AND incoming.status=(CASE document.state WHEN 'SCRAP' THEN 'DISPOSED' ELSE 'LOST' END))));
    RETURN amount::bigint;
END $function$;
