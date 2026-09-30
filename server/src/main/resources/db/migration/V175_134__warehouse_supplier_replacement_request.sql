-- Capture a real vendor replacement request without changing the old asset or
-- admitting replacement stock. Physical admission requires a later exact effect.
CREATE TABLE inventory_repair_replacement_request (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, return_id uuid NOT NULL, repair_case_id uuid NOT NULL,
    receipt_id uuid NOT NULL, original_asset_id uuid NOT NULL, assignment_id uuid NOT NULL,
    customer_id uuid NOT NULL, work_order_id uuid NOT NULL, actor_id uuid NOT NULL,
    legal_owner varchar(8) NOT NULL CHECK(legal_owner IN ('ISP','CUSTOMER')),
    source_return_revision bigint NOT NULL CHECK(source_return_revision>=1),
    source_asset_revision bigint NOT NULL CHECK(source_asset_revision>=0),
    operation_key text NOT NULL CHECK(length(operation_key) BETWEEN 1 AND 240),
    payload_hash text NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'), canonical_payload text NOT NULL,
    snapshot text NOT NULL, origin_snapshot jsonb NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,receipt_id), UNIQUE(tenant_id,operation_key),
    FOREIGN KEY(tenant_id,return_id) REFERENCES inventory_return_case(tenant_id,id),
    FOREIGN KEY(tenant_id,repair_case_id) REFERENCES inventory_repair_case(tenant_id,id),
    FOREIGN KEY(tenant_id,receipt_id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,original_asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE INDEX warehouse_replacement_return_idx ON inventory_repair_replacement_request(tenant_id,return_id,created_at,id);
CREATE INDEX warehouse_replacement_repair_idx ON inventory_repair_replacement_request(tenant_id,repair_case_id);
ALTER TABLE inventory_repair_replacement_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_repair_replacement_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_repair_replacement_request
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_repair_replacement_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_repair_replacement_request TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_capture_replacement_request() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE intake inventory_return_case; returned inventory_document; repair inventory_repair_case;
    removal inventory_asset_removal; assignment inventory_asset_assignment; asset inventory_serialized_asset;
    balance inventory_balance_projection; segment inventory_segment; receipt inventory_document;
    receipt_intake inventory_receipt_intake; body jsonb; view jsonb; input jsonb; position jsonb; context jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.created_xid<>pg_current_xact_id() THEN RAISE EXCEPTION 'REPLACEMENT_REQUEST_TRANSACTION_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO STRICT intake FROM inventory_return_case WHERE tenant_id=NEW.tenant_id AND id=NEW.return_id;
    SELECT * INTO STRICT returned FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.return_id FOR UPDATE;
    SELECT * INTO STRICT repair FROM inventory_repair_case WHERE tenant_id=NEW.tenant_id AND id=NEW.repair_case_id FOR UPDATE;
    SELECT * INTO STRICT removal FROM inventory_asset_removal WHERE tenant_id=NEW.tenant_id AND id=intake.source_document_id;
    SELECT * INTO STRICT assignment FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND id=NEW.assignment_id;
    SELECT * INTO STRICT asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.original_asset_id FOR UPDATE;
    SELECT * INTO STRICT segment FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=asset.id;
    SELECT * INTO STRICT receipt FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.receipt_id;
    SELECT * INTO STRICT receipt_intake FROM inventory_receipt_intake WHERE tenant_id=NEW.tenant_id AND id=NEW.receipt_id;
    SELECT original_body::jsonb INTO view FROM inventory_operation WHERE tenant_id=NEW.tenant_id AND document_id=NEW.return_id
        AND document_revision=returned.revision;
    SELECT * INTO balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND stock_identity_id=asset.id
        AND quantity_base=1 AND base_unit='EA' AND location_id=asset.location_id AND custody_owner_id=repair.vendor_id
        AND custody_owner_kind='REPAIR' AND legal_owner=asset.legal_owner AND condition=asset.condition AND status='QUARANTINE' FOR UPDATE;
    PERFORM warehouse_assert_asset_removal(NEW.tenant_id,removal.id);
    PERFORM warehouse_assert_returned_asset(NEW.tenant_id,NEW.return_id);
    body:=NEW.snapshot::jsonb; input:=body->'input'; position:=body->'position';
    context:=jsonb_build_object('assignmentId',assignment.id,'customerId',assignment.customer_id,'workOrderId',assignment.work_order_id,
        'assetRevision',asset.revision);
    IF intake.origin<>'ASSET_REMOVAL' OR removal.assignment_id<>assignment.id OR assignment.asset_id<>asset.id
        OR intake.stock_identity_id<>asset.id OR assignment.ended_at IS NULL OR assignment.warehouse_admission<>'VERIFIED'
        OR (NEW.customer_id,NEW.work_order_id) IS DISTINCT FROM (assignment.customer_id,assignment.work_order_id)
        OR repair.return_document_id<>NEW.return_id OR repair.asset_id<>asset.id OR repair.state<>'OUTBOUND' OR repair.revision<>0
        OR repair.legal_owner<>NEW.legal_owner OR repair.receive_revision IS NOT NULL OR repair.replacement_asset_id IS NOT NULL
        OR returned.state<>'REPAIR' OR returned.revision<>NEW.source_return_revision
        OR view->>'state' IS DISTINCT FROM 'REPAIR' OR view->>'legalOwner' IS DISTINCT FROM NEW.legal_owner
        OR view#>>'{repair,id}' IS DISTINCT FROM repair.id::text OR view#>>'{repair,vendorId}' IS DISTINCT FROM repair.vendor_id::text
        OR asset.legal_owner<>NEW.legal_owner OR asset.revision<>NEW.source_asset_revision
        OR asset.custody_owner_kind<>'REPAIR' OR asset.custody_owner_id<>repair.vendor_id OR asset.status<>'QUARANTINE'
        OR asset.warehouse_admission<>'VERIFIED' OR segment.warehouse_admission<>'VERIFIED' OR segment.state<>'ACTIVE'
        OR balance.id IS NULL OR EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND asset_id=asset.id AND ended_at IS NULL)
        OR body->'context' IS DISTINCT FROM context
        OR body->'returned' IS DISTINCT FROM (intake.body::jsonb||jsonb_build_object('view',view))
        OR body->'view' IS DISTINCT FROM jsonb_build_object('id',NEW.id,'returnId',NEW.return_id,'repairCaseId',repair.id,
            'receiptId',NEW.receipt_id,'originalAssetId',asset.id,'legalOwner',NEW.legal_owner,'replacementAssetId',NULL)
        OR body->>'actorId' IS DISTINCT FROM NEW.actor_id::text
        OR position->'dimension' IS DISTINCT FROM jsonb_build_object('skuId',asset.sku_id,'stockIdentityId',asset.id,'lotId',NULL,
            'locationId',asset.location_id,'custodianId',repair.vendor_id,'custodianKind','REPAIR','condition',asset.condition,'legalOwner',asset.legal_owner)
        OR position->>'quantity' IS DISTINCT FROM '1' OR position->>'unit' IS DISTINCT FROM 'EA'
        OR position->>'tracking' IS DISTINCT FROM 'SERIAL' OR position->>'serial' IS DISTINCT FROM asset.serial_number
        OR (position->>'balanceRevision')::bigint IS DISTINCT FROM balance.revision
        OR (position->>'segmentRevision')::bigint IS DISTINCT FROM segment.revision
        OR input->>'skuId' IS DISTINCT FROM asset.sku_id::text
        OR (input->>'expectedRevision')::bigint IS DISTINCT FROM returned.revision
        OR warehouse_canonical_serial(input->>'serial') IS NOT DISTINCT FROM asset.canonical_serial
        OR length(btrim(coalesce(input->>'evidenceReference',''))) NOT BETWEEN 1 AND 500
        OR length(btrim(coalesce(input->>'externalReference',''))) NOT BETWEEN 1 AND 500
        OR input-ARRAY['expectedRevision','externalReference','sourceLocationId','inspectionLocationId','skuId','serial','evidenceReference','mac']<>'{}'::jsonb
        OR NEW.canonical_payload::jsonb IS DISTINCT FROM jsonb_build_object('id',NEW.return_id,'input',input)
        OR NEW.payload_hash<>encode(digest(NEW.canonical_payload,'sha256'),'hex')
        OR body#>>'{receipt,id}' IS DISTINCT FROM receipt.id::text OR body#>>'{receipt,state}' IS DISTINCT FROM 'DRAFT'
        OR body#>>'{receipt,revision}' IS DISTINCT FROM '0'
        OR (body#>>'{receipt,createdAt}')::timestamptz IS DISTINCT FROM receipt.created_at
        OR body#>'{receipt,intake}' IS DISTINCT FROM receipt_intake.snapshot::jsonb
        OR receipt_intake.snapshot::jsonb#>>'{supplier,id}' IS DISTINCT FROM repair.vendor_id::text
        OR receipt_intake.snapshot::jsonb->>'externalReference' IS DISTINCT FROM input->>'externalReference'
        OR receipt_intake.source_location_id IS DISTINCT FROM (input->>'sourceLocationId')::uuid
        OR receipt_intake.inspection_location_id IS DISTINCT FROM (input->>'inspectionLocationId')::uuid
        OR jsonb_array_length(receipt_intake.snapshot::jsonb->'lines')<>1
        OR receipt_intake.snapshot::jsonb#>>'{lines,0,serial}' IS DISTINCT FROM input->>'serial'
        OR receipt_intake.snapshot::jsonb#>>'{lines,0,mac}' IS DISTINCT FROM input->>'mac' THEN
        RAISE EXCEPTION 'REPLACEMENT_VERIFIED_REPAIR_SOURCE_REQUIRED' USING ERRCODE='23514'; END IF;
    NEW.origin_snapshot:=jsonb_build_object('return',intake.body::jsonb||jsonb_build_object('view',view),'repair',to_jsonb(repair),
        'asset',to_jsonb(asset),'balance',to_jsonb(balance),'assignment',to_jsonb(assignment),'receipt',to_jsonb(receipt));
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_replacement_capture BEFORE INSERT ON inventory_repair_replacement_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_replacement_request();

CREATE FUNCTION warehouse_assert_replacement_request(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE request inventory_repair_replacement_request; document inventory_document; intake inventory_receipt_intake;
    line inventory_document_line; body jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO request FROM inventory_repair_replacement_request WHERE tenant_id=scope AND id=target;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=request.receipt_id;
    SELECT * INTO intake FROM inventory_receipt_intake WHERE tenant_id=scope AND id=request.receipt_id;
    SELECT * INTO line FROM inventory_document_line WHERE tenant_id=scope AND document_id=request.receipt_id;
    body:=request.snapshot::jsonb;
    IF request.id IS NULL OR document.id IS NULL OR intake.id IS NULL OR line.id IS NULL OR request.origin_snapshot IS NULL
        OR document.kind<>'RECEIPT' OR document.state<>'DRAFT' OR document.revision<>0 OR document.approval_disposition IS NOT NULL
        OR (document.actor_id,document.supplier_id,document.customer_id,document.work_order_id,document.work_order_revision,
            document.authority_epoch,document.cutover_epoch) IS DISTINCT FROM (request.actor_id,
            (body#>>'{returned,view,repair,vendorId}')::uuid,request.customer_id,request.work_order_id,
            (body->>'workOrderRevision')::bigint,(body->>'authorityEpoch')::bigint,(body->>'cutoverEpoch')::bigint)
        OR document.source_reference IS DISTINCT FROM body#>>'{input,externalReference}'
        OR intake.snapshot::jsonb IS DISTINCT FROM body#>'{receipt,intake}'
        OR (SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=request.receipt_id)<>1
        OR (line.id,line.document_revision,line.line_number,line.sku_id,line.base_unit,line.tracking,line.quantity_base,
            line.location_id,line.destination_location_id,line.custodian_id,line.custodian_kind,line.condition,line.legal_owner)
            IS DISTINCT FROM ((body#>>'{receipt,intake,lines,0,id}')::uuid,0::bigint,1,(body#>>'{input,skuId}')::uuid,
                'EA'::varchar,'SERIAL'::varchar,1::bigint,(body#>>'{input,inspectionLocationId}')::uuid,
                (body#>>'{input,sourceLocationId}')::uuid,(body#>>'{input,inspectionLocationId}')::uuid,
                'WAREHOUSE'::varchar,'QUARANTINE'::varchar,request.legal_owner)
        OR line.stock_identity_id IS NOT NULL OR line.lot_id IS NOT NULL
        OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=request.receipt_id AND document_revision<>0)
        OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=request.receipt_id) THEN
        RAISE EXCEPTION 'REPLACEMENT_REQUEST_RECEIPT_BINDING' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_replacement_request_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE value jsonb; scope uuid; target uuid; request uuid;
BEGIN
    value:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(value->>'tenant_id')::uuid;
    PERFORM warehouse_assert_deferred_scope(scope);
    IF TG_TABLE_NAME='inventory_repair_replacement_request' THEN
        PERFORM warehouse_assert_replacement_request(scope,(value->>'id')::uuid);
    ELSE
        target:=CASE WHEN TG_TABLE_NAME IN ('inventory_document','inventory_receipt_intake') THEN (value->>'id')::uuid
            ELSE (value->>'document_id')::uuid END;
        FOR request IN SELECT id FROM inventory_repair_replacement_request WHERE tenant_id=scope AND receipt_id=target LOOP
            PERFORM warehouse_assert_replacement_request(scope,request);
        END LOOP;
    END IF;
    RETURN NULL;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_repair_replacement_request','inventory_document','inventory_document_line',
        'inventory_receipt_intake','inventory_operation','inventory_movement'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_replacement_request_final AFTER INSERT OR UPDATE OR DELETE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_replacement_request_final_guard()',table_name);
    END LOOP;
END $$;
