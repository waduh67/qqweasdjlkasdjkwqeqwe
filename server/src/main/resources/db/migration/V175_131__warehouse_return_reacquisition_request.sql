CREATE TABLE inventory_return_title_request (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, return_id uuid NOT NULL, source_return_revision bigint NOT NULL CHECK(source_return_revision>=1),
    asset_id uuid NOT NULL, assignment_id uuid NOT NULL, customer_id uuid NOT NULL, work_order_id uuid NOT NULL, actor_id uuid NOT NULL,
    source_asset_revision bigint NOT NULL CHECK(source_asset_revision>=0), evidence_id uuid NOT NULL,
    evidence_digest text NOT NULL CHECK(evidence_digest ~ '^[0-9a-f]{64}$'),
    operation_key text NOT NULL CHECK(length(operation_key) BETWEEN 1 AND 200), payload_hash text NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    canonical_payload text NOT NULL, snapshot text NOT NULL, origin_snapshot jsonb NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,operation_key),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,return_id) REFERENCES inventory_return_case(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,evidence_id) REFERENCES evidence_object_registry(tenant_id,revision_id)
);
ALTER TABLE inventory_return_title_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_return_title_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_return_title_request
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_return_title_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

DO $$ DECLARE item record; definition text;
BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_return_title_request TO warehouse_app;
    END IF;
    FOR item IN SELECT conname,pg_get_constraintdef(oid) definition FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND conname IN ('inventory_document_kind_check','inventory_document_check2') LOOP
        EXECUTE format('ALTER TABLE inventory_document DROP CONSTRAINT %I',item.conname);
        EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT %I CHECK ((%s) OR (kind=''RETURN_TITLE'' AND state IN (''DRAFT'',''POSTED'')))',
            item.conname,substring(item.definition FROM 8 FOR length(item.definition)-8));
    END LOOP;
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF position('CASE OLD.kind' IN definition)=0 THEN RAISE EXCEPTION 'return title lifecycle entry changed'; END IF;
    EXECUTE replace(definition,'CASE OLD.kind','CASE OLD.kind WHEN ''RETURN_TITLE'' THEN (OLD.state,NEW.state)=(''DRAFT'',''POSTED'')');
END $$;

CREATE FUNCTION warehouse_capture_return_title_request() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE intake inventory_return_case; returned inventory_document; previous inventory_operation;
    removal inventory_asset_removal; assignment inventory_asset_assignment; handover inventory_asset_handover;
    asset inventory_serialized_asset; balance inventory_balance_projection; segment inventory_segment;
    signature wo_signature; body jsonb; source jsonb; view jsonb; request jsonb; context jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.created_xid<>pg_current_xact_id() THEN RAISE EXCEPTION 'RETURN_TITLE_ORIGIN_TRANSACTION_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO STRICT intake FROM inventory_return_case WHERE tenant_id=NEW.tenant_id AND id=NEW.return_id;
    SELECT * INTO STRICT returned FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.return_id FOR UPDATE;
    SELECT * INTO STRICT previous FROM inventory_operation WHERE tenant_id=NEW.tenant_id AND document_id=NEW.return_id AND document_revision=returned.revision;
    SELECT * INTO STRICT removal FROM inventory_asset_removal WHERE tenant_id=NEW.tenant_id AND id=intake.source_document_id;
    SELECT * INTO STRICT assignment FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND id=NEW.assignment_id;
    SELECT * INTO STRICT handover FROM inventory_asset_handover WHERE tenant_id=NEW.tenant_id AND assignment_id=NEW.assignment_id;
    SELECT * INTO STRICT asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id FOR UPDATE;
    SELECT * INTO STRICT segment FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id;
    SELECT * INTO balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND stock_identity_id=NEW.asset_id
        AND quantity_base=1 AND base_unit='EA' AND location_id=asset.location_id AND custody_owner_id=asset.location_id
        AND custody_owner_kind='WAREHOUSE' AND legal_owner='CUSTOMER' AND condition=asset.condition AND status='QUARANTINE' FOR UPDATE;
    PERFORM warehouse_assert_asset_removal(NEW.tenant_id,removal.id);
    body:=NEW.snapshot::jsonb; source:=body->'source'; view:=previous.original_body::jsonb; request:=body->'request';
    context:=jsonb_build_object('assignmentId',assignment.id,'customerId',assignment.customer_id,'workOrderId',assignment.work_order_id,
        'handoverId',handover.id,'handoverActorId',handover.actor_id,'removalActorId',removal.actor_id,'assignmentRevision',assignment.revision);
    IF intake.origin<>'ASSET_REMOVAL' OR intake.stock_identity_id<>asset.id OR removal.assignment_id<>assignment.id
        OR removal.legal_owner<>'CUSTOMER' OR assignment.legal_owner<>'CUSTOMER' OR assignment.ownership_mode<>'SALE'
        OR assignment.ended_at IS NULL OR assignment.warehouse_admission<>'VERIFIED'
        OR (NEW.customer_id,NEW.work_order_id) IS DISTINCT FROM (assignment.customer_id,assignment.work_order_id)
        OR returned.state<>'RECEIVED_IN_INSPECTION' OR returned.revision<>NEW.source_return_revision
        OR view->>'legalOwner' IS DISTINCT FROM 'CUSTOMER' OR view->>'stockIdentityId' IS DISTINCT FROM asset.id::text
        OR view->>'locationId' IS DISTINCT FROM asset.location_id::text OR view->>'quantityBase' IS DISTINCT FROM '1'
        OR asset.revision<>NEW.source_asset_revision OR asset.legal_owner<>'CUSTOMER' OR asset.status<>'QUARANTINE'
        OR asset.custody_owner_kind<>'WAREHOUSE' OR asset.custody_owner_id<>asset.location_id
        OR asset.warehouse_admission<>'VERIFIED' OR balance.id IS NULL OR segment.state<>'ACTIVE' OR segment.kind<>'SERIAL'
        OR EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND asset_id=asset.id AND ended_at IS NULL)
        OR EXISTS(SELECT FROM inventory_repair_case WHERE tenant_id=NEW.tenant_id AND return_document_id=NEW.return_id AND state<>'CLOSED')
        OR NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=NEW.tenant_id AND id=asset.location_id AND kind='QUARANTINE' AND state='ACTIVE')
        OR body->>'id' IS DISTINCT FROM NEW.id::text OR body->>'actorId' IS DISTINCT FROM NEW.actor_id::text
        OR body->'context' IS DISTINCT FROM context
        OR body->'returned' IS DISTINCT FROM (intake.body::jsonb||jsonb_build_object('view',view))
        OR (body->>'assetRevision')::bigint IS DISTINCT FROM asset.revision
        OR source->'dimension' IS DISTINCT FROM jsonb_build_object('skuId',asset.sku_id,'stockIdentityId',asset.id,'lotId',NULL,
            'locationId',asset.location_id,'custodianId',asset.location_id,'custodianKind','WAREHOUSE','condition',asset.condition,'legalOwner','CUSTOMER')
        OR source->>'quantity' IS DISTINCT FROM '1' OR source->>'unit' IS DISTINCT FROM 'EA' OR source->>'tracking' IS DISTINCT FROM 'SERIAL'
        OR (source->>'balanceRevision')::bigint IS DISTINCT FROM balance.revision
        OR (source->>'segmentRevision')::bigint IS DISTINCT FROM segment.revision OR source->>'serial' IS DISTINCT FROM asset.serial_number
        OR (request->>'expectedRevision')::bigint IS DISTINCT FROM returned.revision
        OR request->>'evidenceId' IS DISTINCT FROM NEW.evidence_id::text
        OR length(btrim(coalesce(request->>'reason',''))) NOT BETWEEN 1 AND 500
        OR length(btrim(coalesce(request->>'titleTransferReference',''))) NOT BETWEEN 1 AND 500
        OR request-ARRAY['expectedRevision','reason','titleTransferReference','evidenceId']<>'{}'::jsonb
        OR NEW.canonical_payload::jsonb IS DISTINCT FROM jsonb_build_object('id',NEW.return_id,'request',request)
        OR NEW.payload_hash<>encode(digest(NEW.canonical_payload,'sha256'),'hex') THEN
        RAISE EXCEPTION 'RETURN_TITLE_VERIFIED_CUSTOMER_SOURCE_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT signed.* INTO signature FROM wo_signature signed JOIN evidence_object_registry registry
        ON registry.tenant_id=signed.tenant_id AND registry.revision_id=signed.id
        WHERE signed.tenant_id=NEW.tenant_id AND signed.id=NEW.evidence_id AND signed.work_order_id=NEW.work_order_id
            AND signed.revision_state='COMMITTED' AND signed.purge_state='ACTIVE' AND registry.state='COMMITTED' AND registry.purge_state='ACTIVE'
            AND signed.sha256=NEW.evidence_digest AND registry.expected_sha256=signed.sha256 AND registry.object_key=signed.storage_key
            FOR SHARE OF signed,registry;
    IF signature.id IS NULL OR body#>>'{signature,id}' IS DISTINCT FROM signature.id::text
        OR body#>>'{signature,digest}' IS DISTINCT FROM signature.sha256 OR body#>>'{signature,reference}' IS DISTINCT FROM signature.storage_key
        OR body#>>'{signature,receiverLabel}' IS DISTINCT FROM signature.signer_name
        OR (body#>>'{signature,receivedAt}')::timestamptz IS DISTINCT FROM signature.receipt_at THEN
        RAISE EXCEPTION 'RETURN_TITLE_CUSTOMER_EVIDENCE_REQUIRED' USING ERRCODE='23514'; END IF;
    NEW.origin_snapshot:=jsonb_build_object('asset',to_jsonb(asset),'balance',to_jsonb(balance),'assignment',to_jsonb(assignment),
        'return',intake.body::jsonb||jsonb_build_object('view',view),'handover',to_jsonb(handover),'signature',to_jsonb(signature));
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_return_title_capture BEFORE INSERT ON inventory_return_title_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_return_title_request();

CREATE FUNCTION warehouse_assert_return_title_request(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE request inventory_return_title_request; document inventory_document; line inventory_document_line; body jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO request FROM inventory_return_title_request WHERE tenant_id=scope AND id=target;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    body:=request.snapshot::jsonb;
    IF request.id IS NULL OR document.id IS NULL OR line.id IS NULL OR request.origin_snapshot IS NULL
        OR document.kind<>'RETURN_TITLE' OR document.state<>'DRAFT' OR document.revision<>0
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
        OR line.lot_id IS NOT NULL OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=target) THEN
        RAISE EXCEPTION 'RETURN_TITLE_REQUEST_DOCUMENT_BINDING' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_return_title_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE value jsonb; target uuid; scope uuid;
BEGIN
    value:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(value->>'tenant_id')::uuid;
    target:=CASE WHEN TG_TABLE_NAME='inventory_document_line' THEN (value->>'document_id')::uuid ELSE (value->>'id')::uuid END;
    IF TG_TABLE_NAME='inventory_return_title_request' OR EXISTS(SELECT FROM inventory_return_title_request WHERE tenant_id=scope AND id=target)
        OR (TG_TABLE_NAME='inventory_document' AND value->>'kind'='RETURN_TITLE') THEN
        PERFORM warehouse_assert_deferred_scope(scope);
        PERFORM warehouse_assert_return_title_request(scope,target);
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_return_title_final AFTER INSERT ON inventory_return_title_request
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_return_title_final_guard();
CREATE CONSTRAINT TRIGGER warehouse_return_title_final AFTER INSERT OR UPDATE OR DELETE ON inventory_document
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_return_title_final_guard();
CREATE CONSTRAINT TRIGGER warehouse_return_title_final AFTER INSERT OR UPDATE OR DELETE ON inventory_document_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_return_title_final_guard();
