CREATE TABLE inventory_asset_loss_request (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, assignment_id uuid NOT NULL, handover_id uuid NOT NULL,
    asset_id uuid NOT NULL, actor_id uuid NOT NULL,
    operation_key text NOT NULL CHECK(length(operation_key) BETWEEN 1 AND 200),
    payload_hash text NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    canonical_payload text NOT NULL, snapshot text NOT NULL, source_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,operation_key),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,handover_id) REFERENCES inventory_asset_handover(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE INDEX warehouse_asset_loss_assignment_idx ON inventory_asset_loss_request(tenant_id,assignment_id,created_at,id);
ALTER TABLE inventory_asset_loss_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_asset_loss_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_asset_loss_request
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_asset_loss_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $migration$ DECLARE item record;
BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_asset_loss_request TO warehouse_app;
    END IF;
    FOR item IN SELECT conname,pg_get_constraintdef(oid) definition FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND conname IN ('inventory_document_kind_check','inventory_document_check2') LOOP
        EXECUTE format('ALTER TABLE inventory_document DROP CONSTRAINT %I',item.conname);
        EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT %I CHECK ((%s) OR (kind=''ASSET_LOSS'' AND state IN (''DRAFT'',''POSTED'')))',
            item.conname,substring(item.definition FROM 8 FOR length(item.definition)-8));
    END LOOP;
END $migration$;

CREATE FUNCTION warehouse_asset_loss_current_source(scope uuid, assignment_id uuid, handover_id uuid, evidence_id uuid, destination_id uuid)
RETURNS jsonb LANGUAGE plpgsql SET TimeZone='UTC' AS $function$
DECLARE assignment inventory_asset_assignment; asset inventory_serialized_asset; segment inventory_segment;
    balance inventory_balance_projection; handover inventory_asset_handover; signature wo_signature;
    installation customer_asset_installation; target inventory_location; work_revision bigint; episode jsonb;
    cost jsonb; total bigint; basis bigint; currency text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=scope AND id=$2 FOR UPDATE;
    SELECT warehouse_revision INTO work_revision FROM work_order WHERE tenant_id=scope AND id=assignment.work_order_id FOR SHARE;
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=scope AND id=assignment.asset_id FOR UPDATE;
    SELECT * INTO segment FROM inventory_segment WHERE tenant_id=scope AND id=assignment.asset_id FOR UPDATE;
    SELECT * INTO handover FROM inventory_asset_handover WHERE tenant_id=scope AND id=$3;
    SELECT * INTO installation FROM customer_asset_installation WHERE tenant_id=scope AND customer_asset_installation.assignment_id=$2;
    SELECT * INTO balance FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=asset.id AND quantity_base>0 FOR UPDATE;
    SELECT * INTO target FROM inventory_location WHERE tenant_id=scope AND id=$5 FOR SHARE;
    SELECT signed.* INTO signature FROM wo_signature signed JOIN evidence_object_registry registry
        ON registry.tenant_id=signed.tenant_id AND registry.revision_id=signed.id
        WHERE signed.tenant_id=scope AND signed.id=$4 AND signed.work_order_id=assignment.work_order_id
        AND signed.revision_state='COMMITTED' AND signed.purge_state='ACTIVE' AND registry.state='COMMITTED'
        AND registry.purge_state='ACTIVE' AND registry.expected_sha256=signed.sha256 AND registry.object_key=signed.storage_key
        FOR SHARE OF signed,registry;
    IF assignment.id IS NULL OR assignment.warehouse_admission<>'VERIFIED' OR assignment.ended_at IS NOT NULL
        OR assignment.ownership_mode<>'LOAN' OR assignment.legal_owner<>'ISP'
        OR asset.id IS NULL OR asset.warehouse_admission<>'VERIFIED' OR asset.status<>'CUSTOMER_INSTALLED'
        OR asset.condition<>'SERVICEABLE' OR asset.legal_owner<>'ISP' OR asset.custody_owner_kind<>'CUSTOMER'
        OR asset.custody_owner_id<>assignment.customer_id OR handover.id IS NULL OR handover.assignment_id<>assignment.id
        OR handover.ownership_mode<>'LOAN' OR handover.asset_id<>asset.id
        OR segment.id IS NULL OR segment.state<>'ACTIVE' OR segment.warehouse_admission<>'VERIFIED'
        OR segment.asset_id<>asset.id OR segment.base_unit<>'EA' OR segment.quantity_base<>1
        OR balance.id IS NULL OR balance.warehouse_admission<>'VERIFIED'
        OR (balance.stock_identity_id,balance.sku_id,balance.location_id,balance.custody_owner_id,balance.custody_owner_kind,
            balance.condition,balance.legal_owner,balance.status,balance.quantity_base,balance.base_unit)
            IS DISTINCT FROM (asset.id,asset.sku_id,asset.location_id,assignment.customer_id,'CUSTOMER'::text,
                'SERVICEABLE'::text,'ISP'::text,'CUSTOMER_INSTALLED'::text,1::bigint,'EA'::text)
        OR (SELECT count(*) FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=asset.id AND quantity_base>0)<>1
        OR target.id IS NULL OR target.state<>'ACTIVE' OR target.kind<>'LOST' OR target.issue_eligible
        OR work_revision IS NULL OR signature.id IS NULL OR installation.id IS NULL
        OR NOT EXISTS(SELECT FROM inventory_asset_recovery_obligation WHERE tenant_id=scope
            AND inventory_asset_recovery_obligation.assignment_id=assignment.id AND inventory_asset_recovery_obligation.handover_id=handover.id)
        OR EXISTS(SELECT FROM inventory_reservation WHERE tenant_id=scope AND stock_identity_id=asset.id
            AND state='OPEN' AND (reserved_unpicked_base>0 OR reserved_picked_base>0)) THEN
        RAISE EXCEPTION 'ASSET_LOSS_ACTIVE_ISP_LOAN_REQUIRED' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_current_asset_title(scope,assignment.id);
    IF installation.onu_id IS NOT NULL THEN
        SELECT jsonb_build_object('id',onu.id,'assignmentId',onu.assignment_id,'customerId',onu.customer_id,
            'assetId',onu.asset_id,'episodeRevision',onu.episode_revision,'retiredAt',onu.retired_at) INTO episode
            FROM onu WHERE tenant_id=scope AND id=installation.onu_id AND retired_at IS NULL FOR UPDATE;
        IF episode IS NULL THEN RAISE EXCEPTION 'ASSET_LOSS_ACTIVE_EPISODE_REQUIRED' USING ERRCODE='23514'; END IF;
    END IF;
    SELECT cost_total_minor,cost_basis_quantity_base,origin.currency INTO total,basis,currency
        FROM inventory_document_line origin WHERE tenant_id=scope AND id=asset.origin_document_line_id;
    cost:=CASE WHEN total IS NULL THEN 'null'::jsonb ELSE jsonb_build_object('totalMinor',total::text,
        'currency',currency,'costBasisQuantityBase',basis::text) END;
    RETURN jsonb_build_object('assignment',to_jsonb(assignment),'asset',to_jsonb(asset),'segment',to_jsonb(segment),
        'balance',to_jsonb(balance),'handover',to_jsonb(handover),'installation',to_jsonb(installation),'episode',episode,
        'evidence',to_jsonb(signature),'workOrderId',assignment.work_order_id,'workOrderRevision',work_revision,'cost',cost);
END $function$;

CREATE FUNCTION warehouse_capture_asset_loss_request() RETURNS trigger LANGUAGE plpgsql SET TimeZone='UTC' AS $function$
DECLARE body jsonb; input jsonb; source jsonb; assignment inventory_asset_assignment; asset inventory_serialized_asset;
    balance inventory_balance_projection; segment inventory_segment; signature wo_signature; dimension jsonb;
    latest_transfer uuid; title_revision bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    body:=NEW.snapshot::jsonb; input:=body->'input';
    source:=warehouse_asset_loss_current_source(NEW.tenant_id,NEW.assignment_id,NEW.handover_id,
        (input->>'evidenceId')::uuid,(input->>'destinationLocationId')::uuid);
    assignment:=jsonb_populate_record(NULL::inventory_asset_assignment,source->'assignment');
    asset:=jsonb_populate_record(NULL::inventory_serialized_asset,source->'asset');
    segment:=jsonb_populate_record(NULL::inventory_segment,source->'segment');
    balance:=jsonb_populate_record(NULL::inventory_balance_projection,source->'balance');
    signature:=jsonb_populate_record(NULL::wo_signature,source->'evidence');
    SELECT transfer.id INTO latest_transfer FROM inventory_asset_title_transfer transfer WHERE transfer.tenant_id=NEW.tenant_id
        AND transfer.assignment_id=NEW.assignment_id ORDER BY transfer.title_revision DESC LIMIT 1;
    SELECT count(*) INTO title_revision FROM inventory_asset_title_transfer WHERE tenant_id=NEW.tenant_id AND assignment_id=NEW.assignment_id;
    dimension:=jsonb_build_object('skuId',asset.sku_id,'stockIdentityId',asset.id,'lotId',NULL,'locationId',asset.location_id,
        'custodianId',assignment.customer_id,'custodianKind','CUSTOMER','condition','SERVICEABLE','legalOwner','ISP');
    IF NEW.created_xid<>pg_current_xact_id() OR NEW.payload_hash<>encode(sha256(convert_to(NEW.canonical_payload,'UTF8')),'hex')
        OR NEW.canonical_payload::jsonb IS DISTINCT FROM input
        OR input-ARRAY['assignmentId','sourceHandoverId','expectedRevision','expectedTitleRevision','expectedWorkOrderRevision',
            'destinationLocationId','reason','evidenceId']<>'{}'::jsonb
        OR (body->>'id')::uuid IS DISTINCT FROM NEW.id OR (body->>'actorId')::uuid IS DISTINCT FROM NEW.actor_id
        OR (input->>'assignmentId')::uuid IS DISTINCT FROM NEW.assignment_id
        OR (input->>'sourceHandoverId')::uuid IS DISTINCT FROM NEW.handover_id OR NEW.asset_id<>asset.id
        OR (input->>'expectedRevision')::bigint IS DISTINCT FROM assignment.revision
        OR (input->>'expectedTitleRevision')::bigint IS DISTINCT FROM title_revision
        OR (input->>'expectedWorkOrderRevision')::bigint IS DISTINCT FROM (source->>'workOrderRevision')::bigint
        OR (body->>'workOrderRevision')::bigint IS DISTINCT FROM (source->>'workOrderRevision')::bigint
        OR nullif(btrim(input->>'reason'),'') IS NULL OR length(input->>'reason')>1000
        OR body->'ownership' IS DISTINCT FROM jsonb_build_object('assignmentId',assignment.id,'assetId',asset.id,
            'customerId',assignment.customer_id,'workOrderId',assignment.work_order_id,'ownershipMode','LOAN','legalOwner','ISP',
            'assignmentRevision',assignment.revision,'titleRevision',title_revision,'handoverId',NEW.handover_id,
            'latestTransferId',latest_transfer,'recoveryRequired',true,'serviceCeased',false,'recoveryDue',false,'positionStatus','CUSTOMER_INSTALLED')
        OR body->'position' IS DISTINCT FROM jsonb_build_object('dimension',dimension,'assetRevision',asset.revision)
        OR body->'source' IS DISTINCT FROM jsonb_build_object('dimension',dimension,'quantity',1,'unit','EA','tracking','SERIAL',
            'status','CUSTOMER_INSTALLED','revision',segment.revision,'cost',source->'cost')
        OR body->'evidence'-'receivedAt' IS DISTINCT FROM jsonb_build_object('id',signature.id,'reference',signature.storage_key,
            'digest',signature.sha256,'receiverLabel',signature.signer_name)
        OR (body#>>'{evidence,receivedAt}')::timestamptz IS DISTINCT FROM signature.receipt_at THEN
        RAISE EXCEPTION 'ASSET_LOSS_REQUEST_SOURCE_BINDING' USING ERRCODE='23514'; END IF;
    NEW.source_snapshot:=source;
    RETURN NEW;
END $function$;
CREATE TRIGGER warehouse_asset_loss_capture BEFORE INSERT ON inventory_asset_loss_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_asset_loss_request();

CREATE FUNCTION warehouse_assert_asset_loss_request(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $function$
DECLARE request inventory_asset_loss_request; document inventory_document; line inventory_document_line; body jsonb; input jsonb; dimension jsonb; cost jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO request FROM inventory_asset_loss_request WHERE tenant_id=scope AND id=target;
    IF NOT FOUND THEN RETURN; END IF;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    body:=request.snapshot::jsonb; input:=body->'input'; dimension:=body#>'{position,dimension}'; cost:=body#>'{source,cost}';
    IF document.id IS NULL OR document.kind<>'ASSET_LOSS' OR document.code IS DISTINCT FROM body->>'code'
        OR document.actor_id<>request.actor_id OR document.work_order_id IS DISTINCT FROM (body#>>'{ownership,workOrderId}')::uuid
        OR document.work_order_revision IS DISTINCT FROM (body->>'workOrderRevision')::bigint
        OR document.source_document_id IS DISTINCT FROM request.handover_id OR document.source_revision IS DISTINCT FROM 1::bigint
        OR document.authority_epoch IS DISTINCT FROM (body->>'authorityEpoch')::bigint
        OR document.cutover_epoch IS DISTINCT FROM (body->>'cutoverEpoch')::bigint OR document.reason IS DISTINCT FROM input->>'reason'
        OR document.state<>'DRAFT' OR document.revision<>0 OR document.approval_disposition IS NOT NULL
        OR line.id IS NULL OR (SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=target)<>1
        OR line.id<>target OR line.document_revision<>0 OR line.line_number<>1
        OR line.stock_identity_id<>request.asset_id OR line.source_line_id IS DISTINCT FROM request.assignment_id
        OR line.sku_id IS DISTINCT FROM (dimension->>'skuId')::uuid OR line.lot_id IS NOT NULL
        OR line.base_unit<>'EA' OR line.tracking<>'SERIAL' OR line.quantity_base<>1
        OR line.location_id IS DISTINCT FROM (dimension->>'locationId')::uuid
        OR line.destination_location_id IS DISTINCT FROM (input->>'destinationLocationId')::uuid
        OR line.custodian_id IS DISTINCT FROM (dimension->>'custodianId')::uuid OR line.custodian_kind<>'CUSTOMER'
        OR line.condition<>'SERVICEABLE' OR line.legal_owner<>'ISP'
        OR (line.cost_total_minor,line.cost_basis_quantity_base,line.currency) IS DISTINCT FROM
            ((cost->>'totalMinor')::bigint,(cost->>'costBasisQuantityBase')::bigint,cost->>'currency')
        OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=target)
        OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=target) THEN
        RAISE EXCEPTION 'ASSET_LOSS_DRAFT_BINDING' USING ERRCODE='23514'; END IF;
END $function$;
CREATE FUNCTION warehouse_asset_loss_request_final_guard() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE value jsonb; scope uuid; target uuid;
BEGIN
    FOR value IN SELECT item FROM jsonb_array_elements(CASE TG_OP WHEN 'INSERT' THEN jsonb_build_array(to_jsonb(NEW))
        WHEN 'DELETE' THEN jsonb_build_array(to_jsonb(OLD)) ELSE jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW)) END) item LOOP
        scope:=(value->>'tenant_id')::uuid;
        PERFORM warehouse_assert_deferred_scope(scope);
        target:=CASE WHEN TG_TABLE_NAME IN ('inventory_asset_loss_request','inventory_document') THEN (value->>'id')::uuid
            ELSE (value->>'document_id')::uuid END;
        IF target IS NOT NULL THEN PERFORM warehouse_assert_asset_loss_request(scope,target); END IF;
    END LOOP;
    RETURN NULL;
END $function$;
DO $migration$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_loss_request','inventory_document','inventory_document_line','inventory_operation','inventory_movement'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_asset_loss_request_final AFTER INSERT OR UPDATE OR DELETE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_asset_loss_request_final_guard()',table_name);
    END LOOP;
END $migration$;
