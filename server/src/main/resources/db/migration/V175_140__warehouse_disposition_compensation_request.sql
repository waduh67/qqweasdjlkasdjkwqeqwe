CREATE TABLE inventory_compensation_request (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, original_request_id uuid NOT NULL, original_posting_id uuid NOT NULL,
    return_id uuid NOT NULL, stock_identity_id uuid NOT NULL, actor_id uuid NOT NULL,
    operation_key text NOT NULL CHECK(length(operation_key) BETWEEN 1 AND 200),
    payload_hash text NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'), canonical_payload text NOT NULL, snapshot text NOT NULL,
    source_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,operation_key),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,original_request_id) REFERENCES inventory_disposition_request(tenant_id,id),
    FOREIGN KEY(tenant_id,original_posting_id) REFERENCES inventory_movement(tenant_id,id),
    FOREIGN KEY(tenant_id,return_id) REFERENCES inventory_return_case(tenant_id,id),
    FOREIGN KEY(tenant_id,stock_identity_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE INDEX inventory_compensation_original ON inventory_compensation_request(tenant_id,original_request_id,created_at DESC,id);
ALTER TABLE inventory_compensation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_compensation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_compensation_request
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_compensation_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $migration$ DECLARE item record;
BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_compensation_request TO warehouse_app;
    END IF;
    FOR item IN SELECT conname,pg_get_expr(conbin,conrelid) definition FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND conname IN ('inventory_document_kind_check','inventory_document_check2') LOOP
        EXECUTE format('ALTER TABLE inventory_document DROP CONSTRAINT %I',item.conname);
        EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT %I CHECK ((%s) OR (kind=''DISPOSITION_REVERSAL'' AND state IN (''DRAFT'',''POSTED'')))',item.conname,item.definition);
    END LOOP;
END $migration$;

CREATE FUNCTION warehouse_capture_compensation_request() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE original inventory_disposition_request; effect inventory_disposition_effect; document inventory_document;
    returned inventory_document; intake inventory_return_case; incoming inventory_movement_leg;
    balance inventory_balance_projection; segment inventory_segment; asset inventory_serialized_asset;
    lifecycle inventory_material_lifecycle; destination inventory_location; sku inventory_sku;
    body jsonb; input jsonb; view jsonb; dimension jsonb; source_work_order uuid; work_revision bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    body:=NEW.snapshot::jsonb; input:=body->'input';
    SELECT * INTO STRICT original FROM inventory_disposition_request WHERE tenant_id=NEW.tenant_id AND id=NEW.original_request_id;
    source_work_order:=(original.snapshot::jsonb#>>'{context,workOrderId}')::uuid;
    SELECT warehouse_revision INTO work_revision FROM work_order WHERE tenant_id=NEW.tenant_id AND id=source_work_order FOR SHARE;
    SELECT * INTO effect FROM inventory_disposition_effect WHERE tenant_id=NEW.tenant_id AND request_id=original.id;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=original.id FOR UPDATE;
    SELECT * INTO returned FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.return_id FOR UPDATE;
    SELECT * INTO intake FROM inventory_return_case WHERE tenant_id=NEW.tenant_id AND id=NEW.return_id;
    SELECT original_body::jsonb INTO view FROM inventory_operation WHERE tenant_id=NEW.tenant_id
        AND document_id=returned.id AND document_revision=returned.revision;
    SELECT * INTO lifecycle FROM inventory_material_lifecycle WHERE tenant_id=NEW.tenant_id AND work_order_id=source_work_order
        ORDER BY revision DESC LIMIT 1;
    SELECT leg.* INTO incoming FROM inventory_movement_leg leg JOIN inventory_movement movement
        ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
        WHERE leg.tenant_id=NEW.tenant_id AND leg.movement_id=NEW.original_posting_id AND leg.direction='IN'
            AND movement.operation_id=effect.posting_operation_id AND movement.state='APPLIED';
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.stock_identity_id FOR UPDATE;
    SELECT * INTO segment FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=NEW.stock_identity_id FOR UPDATE;
    SELECT * INTO sku FROM inventory_sku WHERE tenant_id=NEW.tenant_id AND id=segment.sku_id;
    SELECT * INTO balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND stock_identity_id=NEW.stock_identity_id
        AND quantity_base>0 ORDER BY id LIMIT 1 FOR UPDATE;
    SELECT * INTO destination FROM inventory_location WHERE tenant_id=NEW.tenant_id AND id=(input->>'destinationLocationId')::uuid;
    dimension:=jsonb_build_object('skuId',balance.sku_id,'stockIdentityId',balance.stock_identity_id,'lotId',balance.lot_id,
        'locationId',balance.location_id,'custodianId',balance.custody_owner_id,'custodianKind',balance.custody_owner_kind,
        'condition',balance.condition,'legalOwner',balance.legal_owner);
    IF NEW.created_xid<>pg_current_xact_id() OR (body->>'id')::uuid IS DISTINCT FROM NEW.id
        OR (body->>'actorId')::uuid IS DISTINCT FROM NEW.actor_id OR body->'original' IS DISTINCT FROM original.snapshot::jsonb
        OR (body->>'originalPostingId')::uuid IS DISTINCT FROM NEW.original_posting_id
        OR NEW.return_id<>original.source_document_id OR NEW.stock_identity_id<>original.stock_identity_id
        OR NEW.payload_hash<>encode(sha256(convert_to(NEW.canonical_payload,'UTF8')),'hex')
        OR NEW.canonical_payload::jsonb IS DISTINCT FROM jsonb_build_object('originalDispositionId',original.id,'request',input)
        OR (input-ARRAY['expectedRevision','expectedReturnRevision','destinationLocationId','reason','evidenceReference'])<>'{}'::jsonb
        OR nullif(btrim(input->>'reason'),'') IS NULL OR length(input->>'reason')>1000
        OR nullif(btrim(input->>'evidenceReference'),'') IS NULL OR length(input->>'evidenceReference')>500
        OR document.id IS NULL OR document.state<>'POSTED' OR document.revision<>1
        OR (input->>'expectedRevision')::bigint IS DISTINCT FROM document.revision
        OR effect.request_id IS NULL OR incoming.id IS NULL OR incoming.stock_identity_id<>NEW.stock_identity_id
        OR returned.id IS NULL OR returned.state NOT IN ('SCRAP','LOST') OR returned.revision<>effect.return_revision
        OR (input->>'expectedReturnRevision')::bigint IS DISTINCT FROM returned.revision
        OR body->'returned' IS DISTINCT FROM (intake.body::jsonb || jsonb_build_object('view',view))
        OR view->>'state' IS DISTINCT FROM returned.state OR view->>'legalOwner' IS DISTINCT FROM 'ISP'
        OR balance.id IS NULL OR (balance.sku_id,balance.stock_identity_id,balance.lot_id,balance.quantity_base,balance.base_unit,
            balance.location_id,balance.custody_owner_id,balance.custody_owner_kind,balance.condition,balance.legal_owner,balance.status)
            IS DISTINCT FROM (incoming.sku_id,incoming.stock_identity_id,incoming.lot_id,incoming.quantity_base,incoming.base_unit,
                incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,incoming.condition,incoming.legal_owner,incoming.status)
        OR balance.legal_owner<>'ISP' OR balance.status NOT IN ('LOST','DISPOSED') OR balance.warehouse_admission<>'VERIFIED'
        OR segment.id IS NULL OR segment.state<>'ACTIVE' OR segment.warehouse_admission<>'VERIFIED'
        OR segment.quantity_base<>balance.quantity_base OR sku.state<>'ACTIVE'
        OR body#>'{source,dimension}' IS DISTINCT FROM dimension
        OR (body#>>'{source,quantity}')::bigint IS DISTINCT FROM balance.quantity_base
        OR body#>>'{source,unit}' IS DISTINCT FROM balance.base_unit OR body#>>'{source,tracking}' IS DISTINCT FROM sku.tracking
        OR (body#>>'{source,balanceRevision}')::bigint IS DISTINCT FROM balance.revision
        OR (body#>>'{source,segmentRevision}')::bigint IS DISTINCT FROM segment.revision
        OR body#>>'{source,serial}' IS DISTINCT FROM asset.serial_number
        OR (body#>>'{context,workOrderId}')::uuid IS DISTINCT FROM source_work_order
        OR (body#>>'{context,workOrderRevision}')::bigint IS DISTINCT FROM work_revision
        OR (body#>>'{context,assetRevision}')::bigint IS DISTINCT FROM asset.revision
        OR (body#>>'{context,materialRevision}')::bigint IS DISTINCT FROM lifecycle.revision
        OR lifecycle.material_state='CLOSED' OR destination.id IS NULL OR destination.state<>'ACTIVE'
        OR destination.kind<>'QUARANTINE' OR destination.issue_eligible
        OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=NEW.tenant_id AND compensates_movement_id=NEW.original_posting_id)
        OR EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND asset_id=NEW.stock_identity_id AND ended_at IS NULL)
        OR EXISTS(SELECT FROM inventory_reservation WHERE tenant_id=NEW.tenant_id AND stock_identity_id=NEW.stock_identity_id
            AND state='OPEN' AND (reserved_unpicked_base>0 OR reserved_picked_base>0)) THEN
        RAISE EXCEPTION 'COMPENSATION_ORIGINAL_DISPOSITION_SOURCE_REQUIRED' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_disposition_effect(NEW.tenant_id,original.id);
    NEW.source_snapshot:=jsonb_build_object('originalDocument',to_jsonb(document),'originalEffect',to_jsonb(effect),
        'returnDocument',to_jsonb(returned),'returnView',view,'balance',to_jsonb(balance),'segment',to_jsonb(segment),
        'asset',CASE WHEN asset.id IS NULL THEN 'null'::jsonb ELSE to_jsonb(asset) END,
        'materialLifecycle',CASE WHEN lifecycle.id IS NULL THEN 'null'::jsonb ELSE to_jsonb(lifecycle) END,
        'workOrderId',source_work_order,'workOrderRevision',work_revision);
    RETURN NEW;
END $function$;
CREATE TRIGGER warehouse_compensation_capture BEFORE INSERT ON inventory_compensation_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_compensation_request();

CREATE FUNCTION warehouse_assert_compensation_request(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $function$
DECLARE request inventory_compensation_request; document inventory_document; line inventory_document_line; body jsonb; input jsonb; dimension jsonb; cost jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO request FROM inventory_compensation_request WHERE tenant_id=scope AND id=target;
    IF NOT FOUND THEN RETURN; END IF;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    body:=request.snapshot::jsonb; input:=body->'input'; dimension:=body#>'{source,dimension}'; cost:=body#>'{original,cost}';
    IF document.id IS NULL OR document.kind<>'DISPOSITION_REVERSAL' OR document.code IS DISTINCT FROM body->>'code'
        OR document.actor_id<>request.actor_id OR document.work_order_id IS DISTINCT FROM (body#>>'{context,workOrderId}')::uuid
        OR document.source_document_id IS DISTINCT FROM request.original_request_id
        OR document.source_revision IS DISTINCT FROM (input->>'expectedRevision')::bigint
        OR document.authority_epoch IS DISTINCT FROM (body->>'authorityEpoch')::bigint
        OR document.cutover_epoch IS DISTINCT FROM (body->>'cutoverEpoch')::bigint OR document.reason IS DISTINCT FROM input->>'reason'
        OR document.state<>'DRAFT' OR document.revision<>0 OR document.approval_disposition IS NOT NULL
        OR line.id IS NULL OR (SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=target)<>1
        OR line.id<>target OR line.document_revision<>0 OR line.line_number<>1
        OR line.stock_identity_id<>request.stock_identity_id OR line.source_line_id IS DISTINCT FROM request.original_request_id
        OR line.sku_id IS DISTINCT FROM (dimension->>'skuId')::uuid OR line.lot_id IS DISTINCT FROM (dimension->>'lotId')::uuid
        OR line.base_unit IS DISTINCT FROM body#>>'{source,unit}' OR line.tracking IS DISTINCT FROM body#>>'{source,tracking}'
        OR line.quantity_base IS DISTINCT FROM (body#>>'{source,quantity}')::bigint
        OR line.location_id IS DISTINCT FROM (dimension->>'locationId')::uuid
        OR line.destination_location_id IS DISTINCT FROM (input->>'destinationLocationId')::uuid
        OR line.custodian_id IS DISTINCT FROM (dimension->>'custodianId')::uuid OR line.custodian_kind IS DISTINCT FROM dimension->>'custodianKind'
        OR line.condition IS DISTINCT FROM dimension->>'condition' OR line.legal_owner IS DISTINCT FROM dimension->>'legalOwner'
        OR (line.cost_total_minor,line.cost_basis_quantity_base,line.currency) IS DISTINCT FROM
            ((cost->>'totalMinor')::bigint,(cost->>'costBasisQuantityBase')::bigint,cost->>'currency')
        OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=target)
        OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=target) THEN
        RAISE EXCEPTION 'COMPENSATION_DRAFT_BINDING' USING ERRCODE='23514'; END IF;
END $function$;
CREATE FUNCTION warehouse_compensation_final_guard() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE value jsonb; scope uuid; target uuid;
BEGIN
    FOR value IN SELECT item FROM jsonb_array_elements(CASE TG_OP WHEN 'INSERT' THEN jsonb_build_array(to_jsonb(NEW))
        WHEN 'DELETE' THEN jsonb_build_array(to_jsonb(OLD)) ELSE jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW)) END) item LOOP
        scope:=(value->>'tenant_id')::uuid;
        PERFORM warehouse_assert_deferred_scope(scope);
        target:=CASE WHEN TG_TABLE_NAME IN ('inventory_compensation_request','inventory_document') THEN (value->>'id')::uuid
            ELSE (value->>'document_id')::uuid END;
        IF target IS NOT NULL THEN PERFORM warehouse_assert_compensation_request(scope,target); END IF;
    END LOOP;
    RETURN NULL;
END $function$;
DO $migration$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_compensation_request','inventory_document','inventory_document_line','inventory_operation','inventory_movement'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_compensation_final AFTER INSERT OR UPDATE OR DELETE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_compensation_final_guard()',table_name);
    END LOOP;
END $migration$;
