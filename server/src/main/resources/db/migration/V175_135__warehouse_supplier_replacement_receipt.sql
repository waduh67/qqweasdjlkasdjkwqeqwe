CREATE TABLE inventory_repair_replacement_receipt (
    tenant_id uuid NOT NULL, request_id uuid NOT NULL, repair_case_id uuid NOT NULL, receipt_id uuid NOT NULL,
    replacement_asset_id uuid NOT NULL, operation_id uuid NOT NULL, source_snapshot jsonb NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,request_id), UNIQUE(tenant_id,repair_case_id), UNIQUE(tenant_id,receipt_id),
    UNIQUE(tenant_id,replacement_asset_id), UNIQUE(tenant_id,operation_id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_repair_replacement_request(tenant_id,id),
    FOREIGN KEY(tenant_id,repair_case_id) REFERENCES inventory_repair_case(tenant_id,id),
    FOREIGN KEY(tenant_id,receipt_id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,replacement_asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE inventory_repair_replacement_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_repair_replacement_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_repair_replacement_receipt
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_repair_replacement_receipt
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_repair_replacement_receipt TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_capture_replacement_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE request inventory_repair_replacement_request; returned inventory_document; repair inventory_repair_case;
    old_asset inventory_serialized_asset; new_asset inventory_serialized_asset; receipt inventory_document;
    source_view jsonb; work_revision bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT request FROM inventory_repair_replacement_request WHERE tenant_id=NEW.tenant_id AND id=NEW.request_id;
    SELECT warehouse_revision INTO work_revision FROM work_order WHERE tenant_id=NEW.tenant_id AND id=request.work_order_id FOR SHARE;
    SELECT * INTO STRICT returned FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=request.return_id FOR UPDATE;
    SELECT * INTO STRICT repair FROM inventory_repair_case WHERE tenant_id=NEW.tenant_id AND id=request.repair_case_id FOR UPDATE;
    SELECT * INTO STRICT old_asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=request.original_asset_id FOR UPDATE;
    SELECT * INTO STRICT new_asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.replacement_asset_id;
    SELECT * INTO STRICT receipt FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.receipt_id;
    SELECT original_body::jsonb INTO source_view FROM inventory_operation WHERE tenant_id=NEW.tenant_id
        AND document_id=request.return_id AND document_revision=returned.revision;
    IF NEW.created_xid<>pg_current_xact_id() OR (NEW.repair_case_id,NEW.receipt_id) IS DISTINCT FROM (request.repair_case_id,request.receipt_id)
        OR NEW.replacement_asset_id=request.original_asset_id
        OR work_revision IS DISTINCT FROM (request.snapshot::jsonb->>'workOrderRevision')::bigint
        OR returned.state<>'REPAIR' OR returned.revision<>request.source_return_revision
        OR source_view IS DISTINCT FROM request.snapshot::jsonb#>'{returned,view}'
        OR to_jsonb(repair) IS DISTINCT FROM request.origin_snapshot->'repair'
        OR to_jsonb(old_asset) IS DISTINCT FROM request.origin_snapshot->'asset'
        OR old_asset.legal_owner<>request.legal_owner OR old_asset.custody_owner_kind<>'REPAIR' OR old_asset.custody_owner_id<>repair.vendor_id
        OR receipt.kind<>'RECEIPT' OR receipt.state<>'DRAFT' OR receipt.revision<>0
        OR new_asset.legal_owner<>request.legal_owner OR new_asset.revision<>0 OR new_asset.warehouse_admission<>'VERIFIED'
        OR new_asset.sku_id<>old_asset.sku_id OR new_asset.condition<>'QUARANTINE' OR new_asset.status<>'QUARANTINE'
        OR new_asset.custody_owner_kind<>'WAREHOUSE' OR new_asset.custody_owner_id<>new_asset.location_id
        OR new_asset.location_id IS DISTINCT FROM (request.snapshot::jsonb#>>'{input,inspectionLocationId}')::uuid
        OR NOT EXISTS(SELECT FROM inventory_document_line WHERE tenant_id=NEW.tenant_id AND document_id=receipt.id
            AND id=new_asset.origin_document_line_id AND stock_identity_id=new_asset.id AND legal_owner=request.legal_owner) THEN
        RAISE EXCEPTION 'REPLACEMENT_CURRENT_SOURCE_REQUIRED' USING ERRCODE='23514'; END IF;
    NEW.source_snapshot:=jsonb_build_object('asset',to_jsonb(old_asset),'repair',to_jsonb(repair),'returnView',source_view,
        'newAsset',to_jsonb(new_asset),'workOrderRevision',work_revision);
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_replacement_receipt_capture BEFORE INSERT ON inventory_repair_replacement_receipt
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_replacement_receipt();

CREATE FUNCTION warehouse_assert_replacement_receipt(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE effect inventory_repair_replacement_receipt; request inventory_repair_replacement_request; operation inventory_operation;
    asset inventory_serialized_asset; movement inventory_movement; outgoing inventory_movement_leg; incoming inventory_movement_leg;
    approval inventory_approval; body jsonb; initial inventory_serialized_asset;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO effect FROM inventory_repair_replacement_receipt WHERE tenant_id=scope AND request_id=target;
    SELECT * INTO request FROM inventory_repair_replacement_request WHERE tenant_id=scope AND id=target;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=scope AND id=effect.operation_id;
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=scope AND id=effect.replacement_asset_id;
    body:=request.snapshot::jsonb;
    initial:=jsonb_populate_record(NULL::inventory_serialized_asset,effect.source_snapshot->'newAsset');
    IF effect.request_id IS NULL OR request.id IS NULL OR operation.id IS NULL OR asset.id IS NULL OR initial.id IS NULL
        OR (effect.repair_case_id,effect.receipt_id) IS DISTINCT FROM (request.repair_case_id,request.receipt_id)
        OR asset.id=request.original_asset_id OR initial.id<>asset.id OR initial.legal_owner<>request.legal_owner
        OR initial.origin_document_line_id<>asset.origin_document_line_id OR initial.sku_id<>asset.sku_id
        OR initial.canonical_serial<>asset.canonical_serial OR initial.canonical_mac IS DISTINCT FROM asset.canonical_mac
        OR asset.origin_document_line_id IS DISTINCT FROM (body#>>'{receipt,intake,lines,0,id}')::uuid
        OR effect.source_snapshot->'asset' IS DISTINCT FROM request.origin_snapshot->'asset'
        OR effect.source_snapshot->'repair' IS DISTINCT FROM request.origin_snapshot->'repair'
        OR effect.source_snapshot->'returnView' IS DISTINCT FROM body#>'{returned,view}'
        OR (effect.source_snapshot->>'workOrderRevision')::bigint IS DISTINCT FROM (body->>'workOrderRevision')::bigint
        OR operation.document_id<>request.receipt_id OR operation.document_revision<>1 OR operation.resource_id<>request.receipt_id
        OR operation.namespace NOT IN ('warehouse.receipt.receive','warehouse.approval.effect') OR operation.business_action<>'RECEIVE'
        OR operation.cutover_epoch IS DISTINCT FROM (body->>'cutoverEpoch')::bigint OR operation.original_status<>200 THEN
        RAISE EXCEPTION 'REPLACEMENT_RECEIPT_EXACT_BINDING' USING ERRCODE='23514'; END IF;
    IF operation.namespace='warehouse.approval.effect' THEN
        SELECT * INTO approval FROM inventory_approval WHERE tenant_id=scope AND id::text=operation.operation_key;
        IF approval.id IS NULL OR approval.status<>'APPROVED' OR approval.business_action<>'RECEIPT'
            OR approval.source_document_id<>request.receipt_id OR approval.source_document_revision<>0
            OR approval.source_snapshot::jsonb->'replacement' IS DISTINCT FROM body
            OR operation.payload_hash<>approval.source_snapshot_hash OR operation.original_body IS DISTINCT FROM approval.terminal_body
            OR NOT EXISTS(SELECT FROM inventory_approval_effect WHERE tenant_id=scope AND approval_id=approval.id AND posting_operation_id=operation.id) THEN
            RAISE EXCEPTION 'REPLACEMENT_RECEIPT_APPROVAL_BINDING' USING ERRCODE='23514'; END IF;
    ELSIF NOT EXISTS(SELECT FROM inventory_command_identity WHERE tenant_id=scope AND id=operation.id AND canonical_payload::jsonb=body#>'{receipt,intake}') THEN
        RAISE EXCEPTION 'REPLACEMENT_RECEIPT_COMMAND_BINDING' USING ERRCODE='23514'; END IF;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id;
    SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
    SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
    IF movement.id IS NULL OR movement.kind<>'RECEIVE' OR movement.state<>'APPLIED'
        OR movement.document_id<>request.receipt_id OR movement.document_revision<>1
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id)<>1
        OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
        OR incoming.id IS NULL OR outgoing.id IS NULL
        OR (incoming.stock_identity_id,incoming.sku_id,incoming.lot_id,incoming.document_line_id,incoming.quantity_base,incoming.base_unit,
            incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,incoming.condition,incoming.status,incoming.legal_owner)
            IS DISTINCT FROM (asset.id,asset.sku_id,NULL::uuid,asset.origin_document_line_id,1::bigint,'EA'::varchar,
                (body#>>'{input,inspectionLocationId}')::uuid,(body#>>'{input,inspectionLocationId}')::uuid,'WAREHOUSE'::varchar,
                'QUARANTINE'::varchar,'QUARANTINE'::varchar,request.legal_owner)
        OR (outgoing.stock_identity_id,outgoing.sku_id,outgoing.lot_id,outgoing.document_line_id,outgoing.quantity_base,outgoing.base_unit,
            outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,outgoing.condition,outgoing.status,outgoing.legal_owner)
            IS DISTINCT FROM (asset.id,asset.sku_id,NULL::uuid,asset.origin_document_line_id,1::bigint,'EA'::varchar,
                (body#>>'{input,sourceLocationId}')::uuid,(body#>>'{input,sourceLocationId}')::uuid,'TRANSIT'::varchar,
                'QUARANTINE'::varchar,'RECEIPT_SOURCE'::varchar,request.legal_owner)
        OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=operation.id
            AND document_id=request.receipt_id AND document_revision=1 AND event_kind='RECEIVED')
        OR EXISTS(SELECT FROM inventory_customer_material_fact WHERE tenant_id=scope AND posting_id=movement.id) THEN
        RAISE EXCEPTION 'REPLACEMENT_RECEIPT_EXACT_PHYSICAL_POSTING' USING ERRCODE='23514'; END IF;
END $$;

-- Preserve every request/header/intake binding. Only the received lifecycle and
-- the admitted identity are extended, backed by the exact immutable effect.
DO $$ DECLARE definition text; old_clause text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_replacement_request(uuid,uuid)'::regprocedure);
    old_clause:='document.kind<>''RECEIPT'' OR document.state<>''DRAFT'' OR document.revision<>0 OR document.approval_disposition IS NOT NULL';
    IF position(old_clause IN definition)=0 THEN RAISE EXCEPTION 'replacement draft lifecycle clause changed'; END IF;
    definition:=replace(definition,old_clause,'document.kind<>''RECEIPT'' OR NOT (
        document.state=''DRAFT'' AND document.revision=0 OR
        document.state IN (''RECEIVED_IN_INSPECTION'',''PUTAWAY'',''CLOSED'') AND document.revision>=1)
        OR document.approval_disposition IS NOT NULL');
    old_clause:=$clause$OR line.stock_identity_id IS NOT NULL OR line.lot_id IS NOT NULL
        OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=request.receipt_id AND document_revision<>0)
        OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=request.receipt_id)$clause$;
    IF position(old_clause IN definition)=0 THEN RAISE EXCEPTION 'replacement draft identity clause changed'; END IF;
    definition:=replace(definition,old_clause,'OR line.lot_id IS NOT NULL');
    old_clause:='END $function$';
    IF position(old_clause IN definition)=0 THEN RAISE EXCEPTION 'replacement validator terminator changed'; END IF;
    definition:=replace(definition,old_clause,$patch$
    IF document.state='DRAFT' THEN
        IF line.stock_identity_id IS NOT NULL OR EXISTS(SELECT FROM inventory_repair_replacement_receipt WHERE tenant_id=scope AND request_id=target)
            OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=request.receipt_id AND document_revision<>0)
            OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=request.receipt_id) THEN
            RAISE EXCEPTION 'REPLACEMENT_DRAFT_HAS_NO_PHYSICAL_EFFECT' USING ERRCODE='23514'; END IF;
    ELSE
        IF NOT EXISTS(SELECT FROM inventory_repair_replacement_receipt WHERE tenant_id=scope AND request_id=target AND replacement_asset_id=line.stock_identity_id) THEN
            RAISE EXCEPTION 'REPLACEMENT_POSTED_IDENTITY_REQUIRED' USING ERRCODE='23514'; END IF;
        PERFORM warehouse_assert_replacement_receipt(scope,target);
    END IF;
END $function$
$patch$);
    EXECUTE definition;
END $$;

CREATE FUNCTION warehouse_replacement_receipt_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE value jsonb; scope uuid; target_id uuid; operation uuid; document uuid; movement uuid; approval uuid; request uuid;
BEGIN
    FOR value IN SELECT item FROM jsonb_array_elements(CASE TG_OP WHEN 'INSERT' THEN jsonb_build_array(to_jsonb(NEW))
        WHEN 'DELETE' THEN jsonb_build_array(to_jsonb(OLD)) ELSE jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW)) END) item LOOP
        scope:=(value->>'tenant_id')::uuid;
        PERFORM warehouse_assert_deferred_scope(scope);
        target_id:=(value->>'id')::uuid;
        operation:=CASE WHEN TG_TABLE_NAME IN ('inventory_operation','inventory_command_identity') THEN target_id ELSE (value->>'operation_id')::uuid END;
        document:=CASE WHEN TG_TABLE_NAME='inventory_document' THEN target_id ELSE (value->>'document_id')::uuid END;
        movement:=CASE WHEN TG_TABLE_NAME='inventory_movement' THEN target_id ELSE coalesce((value->>'movement_id')::uuid,(value->>'posting_id')::uuid) END;
        approval:=CASE WHEN TG_TABLE_NAME='inventory_approval' THEN target_id ELSE (value->>'approval_id')::uuid END;
        FOR request IN SELECT effect.request_id FROM inventory_repair_replacement_receipt effect
            WHERE effect.tenant_id=scope AND (effect.request_id=(value->>'request_id')::uuid OR effect.receipt_id=document OR effect.operation_id=operation
                OR TG_TABLE_NAME='inventory_serialized_asset' AND effect.replacement_asset_id=target_id
                OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND id=movement AND operation_id=effect.operation_id)
                OR EXISTS(SELECT FROM inventory_approval WHERE tenant_id=scope AND id=approval AND source_document_id=effect.receipt_id)) LOOP
            PERFORM warehouse_assert_replacement_request(scope,request);
        END LOOP;
    END LOOP;
    RETURN NULL;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_repair_replacement_receipt','inventory_document','inventory_document_line',
        'inventory_operation','inventory_command_identity','inventory_movement','inventory_movement_leg','inventory_serialized_asset',
        'inventory_approval','inventory_approval_effect','inventory_outbox','inventory_customer_material_fact'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_replacement_receipt_final AFTER INSERT OR UPDATE OR DELETE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_replacement_receipt_final_guard()',table_name);
    END LOOP;
END $$;
