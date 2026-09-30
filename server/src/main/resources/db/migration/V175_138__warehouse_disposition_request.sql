CREATE TABLE inventory_disposition_request (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, source_document_id uuid NOT NULL,
    stock_identity_id uuid NOT NULL, actor_id uuid NOT NULL,
    operation_key text NOT NULL CHECK(length(operation_key) BETWEEN 1 AND 200),
    payload_hash text NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    canonical_payload text NOT NULL, snapshot text NOT NULL,
    source_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,operation_key),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,source_document_id) REFERENCES inventory_return_case(tenant_id,id),
    FOREIGN KEY(tenant_id,stock_identity_id) REFERENCES inventory_segment(tenant_id,id)
);
CREATE INDEX warehouse_disposition_source_idx ON inventory_disposition_request(tenant_id,source_document_id,created_at,id);
ALTER TABLE inventory_disposition_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_disposition_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_disposition_request
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_disposition_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $migration$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_disposition_request TO warehouse_app;
    END IF;
END $migration$;

CREATE FUNCTION warehouse_capture_disposition_request() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE body jsonb; input jsonb; view jsonb; source inventory_document; returned inventory_return_case;
    balance inventory_balance_projection; segment inventory_segment; asset inventory_serialized_asset;
    sku inventory_sku; target inventory_location; work_order_id uuid; work_revision bigint;
    source_cost jsonb; total bigint; basis bigint; currency text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    body:=NEW.snapshot::jsonb; input:=body->'input';
    SELECT * INTO STRICT returned FROM inventory_return_case WHERE tenant_id=NEW.tenant_id AND id=NEW.source_document_id;
    SELECT coalesce(residual.work_order_id,removal.work_order_id) INTO work_order_id
        FROM inventory_return_case r
        LEFT JOIN inventory_material_residual residual ON residual.tenant_id=r.tenant_id
            AND residual.id=r.source_document_id AND r.origin='MATERIAL_RESIDUAL'
        LEFT JOIN inventory_asset_removal removal ON removal.tenant_id=r.tenant_id
            AND removal.id=r.source_document_id AND r.origin='ASSET_REMOVAL'
        WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.source_document_id;
    SELECT warehouse_revision INTO work_revision FROM work_order
        WHERE tenant_id=NEW.tenant_id AND id=work_order_id FOR SHARE;
    SELECT * INTO STRICT source FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.source_document_id FOR UPDATE;
    SELECT original_body::jsonb INTO view FROM inventory_operation
        WHERE tenant_id=NEW.tenant_id AND document_id=source.id AND document_revision=source.revision;
    PERFORM warehouse_assert_return(NEW.tenant_id,source.id);
    SELECT * INTO segment FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=NEW.stock_identity_id FOR UPDATE;
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.stock_identity_id FOR UPDATE;
    SELECT * INTO sku FROM inventory_sku WHERE tenant_id=NEW.tenant_id AND id=segment.sku_id;
    SELECT * INTO balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id
        AND stock_identity_id=NEW.stock_identity_id AND location_id=(view->>'locationId')::uuid
        AND custody_owner_id=(view->>'locationId')::uuid AND custody_owner_kind='WAREHOUSE'
        AND condition=view->>'condition' AND legal_owner='ISP' AND quantity_base>0 FOR UPDATE;
    SELECT * INTO target FROM inventory_location WHERE tenant_id=NEW.tenant_id AND id=(input->>'destinationLocationId')::uuid;
    SELECT coalesce(origin.cost_total_minor,lot.cost_total_minor),coalesce(origin.cost_basis_quantity_base,lot.cost_basis_quantity_base),
        coalesce(origin.currency,lot.currency) INTO total,basis,currency
        FROM inventory_segment piece
        LEFT JOIN inventory_lot lot ON lot.tenant_id=piece.tenant_id AND lot.id=piece.lot_id
        LEFT JOIN inventory_serialized_asset physical ON physical.tenant_id=piece.tenant_id AND physical.id=piece.asset_id
        LEFT JOIN inventory_document_line origin ON origin.tenant_id=physical.tenant_id AND origin.id=physical.origin_document_line_id
        WHERE piece.tenant_id=NEW.tenant_id AND piece.id=NEW.stock_identity_id;
    source_cost:=CASE WHEN total IS NULL THEN 'null'::jsonb ELSE
        jsonb_build_object('totalMinor',total::text,'currency',currency,'costBasisQuantityBase',basis::text) END;
    IF NEW.created_xid<>pg_current_xact_id() OR NEW.payload_hash<>encode(sha256(convert_to(NEW.canonical_payload,'UTF8')),'hex')
        OR NEW.canonical_payload::jsonb IS DISTINCT FROM input
        OR input-ARRAY['sourceDocumentId','expectedRevision','stockIdentityId','quantityBase','baseUnit',
            'destinationLocationId','action','reason','evidenceReference']<>'{}'::jsonb
        OR (body->>'id')::uuid IS DISTINCT FROM NEW.id OR (body->>'actorId')::uuid IS DISTINCT FROM NEW.actor_id
        OR (input->>'sourceDocumentId')::uuid IS DISTINCT FROM source.id
        OR (input->>'stockIdentityId')::uuid IS DISTINCT FROM NEW.stock_identity_id
        OR source.kind<>'RETURN' OR source.state<>'RECEIVED_IN_INSPECTION'
        OR (input->>'expectedRevision')::bigint IS DISTINCT FROM source.revision
        OR view IS NULL OR view->>'state'<>'RECEIVED_IN_INSPECTION' OR view->>'legalOwner'<>'ISP'
        OR (view->>'stockIdentityId')::uuid IS DISTINCT FROM NEW.stock_identity_id
        OR returned.stock_identity_id<>NEW.stock_identity_id
        OR body->'returned' IS DISTINCT FROM (returned.body::jsonb||jsonb_build_object('view',view))
        OR input->>'action' NOT IN ('LOSS','SCRAP')
        OR input->>'action'='SCRAP' AND view->>'condition'<>'DAMAGED'
        OR nullif(btrim(input->>'reason'),'') IS NULL OR length(input->>'reason')>1000
        OR nullif(btrim(input->>'evidenceReference'),'') IS NULL OR length(input->>'evidenceReference')>500
        OR balance.id IS NULL OR balance.warehouse_admission<>'VERIFIED' OR balance.status<>'QUARANTINE'
        OR segment.id IS NULL OR segment.state<>'ACTIVE' OR segment.warehouse_admission<>'VERIFIED'
        OR sku.id IS NULL OR sku.state<>'ACTIVE'
        OR balance.sku_id<>segment.sku_id OR balance.lot_id IS DISTINCT FROM segment.lot_id
        OR balance.quantity_base::text IS DISTINCT FROM input->>'quantityBase'
        OR balance.quantity_base::text IS DISTINCT FROM view->>'quantityBase'
        OR balance.base_unit IS DISTINCT FROM input->>'baseUnit' OR balance.base_unit IS DISTINCT FROM view->>'baseUnit'
        OR target.id IS NULL OR target.state<>'ACTIVE' OR target.issue_eligible
        OR target.kind<>(CASE WHEN input->>'action'='LOSS' THEN 'LOST' ELSE 'DISPOSED' END)
        OR work_order_id IS NULL OR work_revision IS NULL
        OR body->'context' IS DISTINCT FROM jsonb_build_object('workOrderId',work_order_id,'workOrderRevision',work_revision,'assetRevision',asset.revision)
        OR body->'cost' IS DISTINCT FROM source_cost
        OR body->'source' IS DISTINCT FROM jsonb_build_object('dimension',jsonb_build_object('skuId',balance.sku_id,
            'stockIdentityId',balance.stock_identity_id,'lotId',balance.lot_id,'locationId',balance.location_id,
            'custodianId',balance.custody_owner_id,'custodianKind',balance.custody_owner_kind,'condition',balance.condition,'legalOwner',balance.legal_owner),
            'quantity',balance.quantity_base,'unit',balance.base_unit,'tracking',sku.tracking,
            'balanceRevision',balance.revision,'segmentRevision',segment.revision,'serial',asset.serial_number)
        OR sku.tracking='SERIAL' AND (asset.id IS NULL OR asset.legal_owner<>'ISP' OR asset.warehouse_admission<>'VERIFIED')
        OR EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND asset_id=NEW.stock_identity_id AND ended_at IS NULL)
        OR EXISTS(SELECT FROM inventory_reservation WHERE tenant_id=NEW.tenant_id AND stock_identity_id=NEW.stock_identity_id
            AND state='OPEN' AND (reserved_unpicked_base>0 OR reserved_picked_base>0)) THEN
        RAISE EXCEPTION 'DISPOSITION_VERIFIED_RETURN_SOURCE_REQUIRED' USING ERRCODE='23514'; END IF;
    IF sku.tracking='SERIAL' THEN PERFORM warehouse_assert_recovered_position(NEW.tenant_id,asset.id); END IF;
    NEW.source_snapshot:=jsonb_build_object('document',to_jsonb(source),'returnView',view,'balance',to_jsonb(balance),
        'segment',to_jsonb(segment),'asset',CASE WHEN asset.id IS NULL THEN 'null'::jsonb ELSE to_jsonb(asset) END,
        'cost',source_cost,'workOrderId',work_order_id,'workOrderRevision',work_revision);
    RETURN NEW;
END $function$;
CREATE TRIGGER warehouse_disposition_capture BEFORE INSERT ON inventory_disposition_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_disposition_request();

CREATE FUNCTION warehouse_assert_disposition_request(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $function$
DECLARE request inventory_disposition_request; document inventory_document; line inventory_document_line; body jsonb; input jsonb; dimension jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO request FROM inventory_disposition_request WHERE tenant_id=scope AND id=target;
    IF NOT FOUND THEN RETURN; END IF;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    body:=request.snapshot::jsonb; input:=body->'input'; dimension:=body#>'{source,dimension}';
    IF document.id IS NULL OR document.kind IS DISTINCT FROM input->>'action'
        OR document.code IS DISTINCT FROM body->>'code' OR document.actor_id<>request.actor_id
        OR document.work_order_id IS DISTINCT FROM (body#>>'{context,workOrderId}')::uuid
        OR document.source_document_id IS DISTINCT FROM request.source_document_id
        OR document.source_revision IS DISTINCT FROM (input->>'expectedRevision')::bigint
        OR document.authority_epoch IS DISTINCT FROM (body->>'authorityEpoch')::bigint
        OR document.cutover_epoch IS DISTINCT FROM (body->>'cutoverEpoch')::bigint
        OR document.reason IS DISTINCT FROM input->>'reason'
        OR document.state<>'DRAFT' OR document.revision<>0 OR document.approval_disposition IS NOT NULL
        OR line.id IS NULL OR (SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=target)<>1
        OR line.id<>target OR line.document_revision<>0 OR line.line_number<>1
        OR line.stock_identity_id<>request.stock_identity_id OR line.sku_id IS DISTINCT FROM (dimension->>'skuId')::uuid
        OR line.lot_id IS DISTINCT FROM (dimension->>'lotId')::uuid OR line.source_line_id IS DISTINCT FROM request.source_document_id
        OR line.base_unit IS DISTINCT FROM input->>'baseUnit' OR line.tracking IS DISTINCT FROM body#>>'{source,tracking}'
        OR line.quantity_base IS DISTINCT FROM (input->>'quantityBase')::bigint
        OR line.location_id IS DISTINCT FROM (dimension->>'locationId')::uuid
        OR line.destination_location_id IS DISTINCT FROM (input->>'destinationLocationId')::uuid
        OR line.custodian_id IS DISTINCT FROM (dimension->>'custodianId')::uuid
        OR line.custodian_kind IS DISTINCT FROM dimension->>'custodianKind'
        OR line.condition IS DISTINCT FROM dimension->>'condition' OR line.legal_owner IS DISTINCT FROM dimension->>'legalOwner'
        OR (line.cost_total_minor,line.cost_basis_quantity_base,line.currency) IS DISTINCT FROM
            ((body#>>'{cost,totalMinor}')::bigint,(body#>>'{cost,costBasisQuantityBase}')::bigint,body#>>'{cost,currency}')
        OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=target)
        OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=target) THEN
        RAISE EXCEPTION 'DISPOSITION_DRAFT_BINDING' USING ERRCODE='23514'; END IF;
END $function$;

CREATE FUNCTION warehouse_disposition_final_guard() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE value jsonb; scope uuid; target uuid;
BEGIN
    FOR value IN SELECT item FROM jsonb_array_elements(CASE TG_OP WHEN 'INSERT' THEN jsonb_build_array(to_jsonb(NEW))
        WHEN 'DELETE' THEN jsonb_build_array(to_jsonb(OLD)) ELSE jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW)) END) item LOOP
        scope:=(value->>'tenant_id')::uuid;
        PERFORM warehouse_assert_deferred_scope(scope);
        target:=CASE WHEN TG_TABLE_NAME IN ('inventory_disposition_request','inventory_document') THEN (value->>'id')::uuid
            ELSE (value->>'document_id')::uuid END;
        IF target IS NOT NULL THEN PERFORM warehouse_assert_disposition_request(scope,target); END IF;
    END LOOP;
    RETURN NULL;
END $function$;
DO $migration$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_disposition_request','inventory_document','inventory_document_line','inventory_operation','inventory_movement'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_disposition_final AFTER INSERT OR UPDATE OR DELETE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_disposition_final_guard()',table_name);
    END LOOP;
END $migration$;
