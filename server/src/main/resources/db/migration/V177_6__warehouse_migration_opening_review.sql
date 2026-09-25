-- A sealed opening proposal binds actual cutoff sources, latest resolutions and evidence.
-- Admission, approval and posting stay closed until their separately guarded implementation.
CREATE FUNCTION warehouse_migration_review_manifest(p_batch uuid) RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT jsonb_build_object('batchId',batch.id,'cutoverEpoch',batch.cutover_epoch,'watermark',batch.snapshot_watermark,
        'sourceHash',batch.source_hash,'cases',coalesce((SELECT jsonb_agg(jsonb_build_object(
            'caseId',source.id,'sourceTable',source.source_table,'sourceId',source.source_id,'sourceHash',source.source_hash,
            'sourceSnapshot',source.source_snapshot,'resolution',resolution.original_body::jsonb,
            'resolutionRequired',warehouse_migration_pending(source.source_table,source.source_snapshot) OR
                (source.source_table='inventory_serialized_asset' AND source.source_snapshot->>'status'='AVAILABLE') OR
                (source.source_table='inventory_balance_projection' AND source.source_snapshot->>'status'='AVAILABLE'
                    AND coalesce(source.source_snapshot->>'quantityBase',source.source_snapshot->>'legacyQuantity','UNKNOWN')<>'0'))
            ORDER BY source.source_table,source.source_id)
        FROM jsonb_array_elements(batch.source_manifest) member
        JOIN inventory_provenance_case source ON source.tenant_id=batch.tenant_id AND source.id=(member->>'caseId')::uuid
            AND source.source_hash=member->>'sourceHash'
        LEFT JOIN LATERAL (SELECT original_body FROM inventory_migration_resolution
            WHERE tenant_id=batch.tenant_id AND batch_id=batch.id AND case_id=source.id ORDER BY revision DESC LIMIT 1) resolution ON true),'[]'::jsonb))
    FROM inventory_migration_batch batch WHERE batch.tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid AND batch.id=p_batch
$$;

CREATE FUNCTION warehouse_migration_review_issues(manifest jsonb) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE source jsonb; resolution jsonb; peer jsonb; stock jsonb; issues jsonb:='[]'; issue text; matched uuid;
BEGIN
    FOR source IN SELECT value FROM jsonb_array_elements(manifest->'cases') LOOP
        issue:=NULL; resolution:=source->'resolution';
        IF (source->>'resolutionRequired')::boolean AND resolution='null'::jsonb THEN
            issue:='RESOLUTION_REQUIRED';
        ELSIF resolution->>'kind'='BASELINE_STOCK' THEN
            BEGIN
                stock:=warehouse_migration_stock((source->>'caseId')::uuid,(resolution->'stock'->>'skuId')::uuid,
                    resolution->'stock'->>'sourceUnit',resolution->'stock'->>'legalOwner');
                IF stock IS DISTINCT FROM resolution->'stock' THEN issue:='STOCK_REVISION_CHANGED'; END IF;
            EXCEPTION WHEN check_violation THEN issue:='STOCK_NO_LONGER_ELIGIBLE'; END;
            IF NOT EXISTS (SELECT FROM warehouse_live_provenance_source live
                WHERE live.tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid
                    AND live.source_table=source->>'sourceTable' AND live.source_id=(source->>'sourceId')::uuid
                    AND live.source_hash=source->>'sourceHash') THEN issue:='SOURCE_CHANGED_AFTER_CUTOFF'; END IF;
            IF source->>'sourceTable'='inventory_serialized_asset' THEN
                FOR peer IN SELECT value FROM jsonb_array_elements(manifest->'cases') LOOP
                    IF peer->>'caseId'=source->>'caseId' THEN CONTINUE; END IF;
                    IF (peer->>'sourceTable' IN ('inventory_serialized_asset','inventory_serial_tombstone') OR
                        (peer->>'sourceTable'='onu' AND peer->'sourceSnapshot'->>'retiredAt' IS NULL)) AND
                        (warehouse_canonical_serial(peer->'sourceSnapshot'->>'serialNumber')=warehouse_canonical_serial(source->'sourceSnapshot'->>'serialNumber')
                            OR warehouse_canonical_mac(peer->'sourceSnapshot'->>'macAddress')=warehouse_canonical_mac(source->'sourceSnapshot'->>'macAddress')) THEN
                        IF peer->'resolution'->>'kind' IS DISTINCT FROM 'DUPLICATE'
                            OR peer->'resolution'->>'duplicateCaseId' IS DISTINCT FROM source->>'caseId'
                            OR peer->'resolution'->>'duplicateResolutionId' IS DISTINCT FROM resolution->>'id' THEN
                            issue:='IDENTITY_CONFLICT_REQUIRES_REVIEW';
                        END IF;
                    END IF;
                    IF peer->>'sourceTable'='inventory_balance_projection' AND peer->'sourceSnapshot'->>'itemId'=source->>'sourceId'
                        AND peer->'sourceSnapshot'->>'status'='AVAILABLE'
                        AND coalesce(peer->'sourceSnapshot'->>'quantityBase',peer->'sourceSnapshot'->>'legacyQuantity','UNKNOWN')<>'0'
                        AND (peer->'resolution'->>'kind' IS DISTINCT FROM 'DUPLICATE'
                            OR peer->'resolution'->>'duplicateResolutionId' IS DISTINCT FROM resolution->>'id') THEN
                        issue:='ASSET_BALANCE_REQUIRES_RECONCILIATION';
                    END IF;
                END LOOP;
            END IF;
        ELSIF resolution->>'kind'='DUPLICATE' THEN
            BEGIN
                matched:=warehouse_migration_duplicate((manifest->>'batchId')::uuid,(source->>'caseId')::uuid,
                    (resolution->>'duplicateCaseId')::uuid);
                IF matched::text IS DISTINCT FROM resolution->>'duplicateResolutionId' THEN issue:='DUPLICATE_WINNER_CHANGED'; END IF;
            EXCEPTION WHEN check_violation THEN issue:='DUPLICATE_WINNER_CHANGED'; END;
        END IF;
        IF issue IS NOT NULL THEN issues:=issues||jsonb_build_array(jsonb_build_object('caseId',source->>'caseId','code',issue)); END IF;
    END LOOP;
    RETURN issues;
END $$;

CREATE TABLE inventory_migration_opening_request (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), batch_id uuid NOT NULL,
    review_hash text NOT NULL CHECK (review_hash ~ '^[0-9a-f]{64}$'), review_manifest jsonb NOT NULL,
    review_location_id uuid NOT NULL, review_location_revision bigint NOT NULL CHECK (review_location_revision>=0),
    actor_id uuid NOT NULL, authority_epoch bigint NOT NULL CHECK (authority_epoch>=0), cutover_epoch bigint NOT NULL CHECK (cutover_epoch>0),
    operation_key varchar(240) NOT NULL CHECK (btrim(operation_key)<>''), canonical_payload text NOT NULL,
    payload_hash text NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'), original_body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,operation_key),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY (tenant_id,batch_id) REFERENCES inventory_migration_batch(tenant_id,id),
    FOREIGN KEY (tenant_id,review_location_id) REFERENCES inventory_location(tenant_id,id)
);

CREATE INDEX inventory_migration_opening_batch_idx ON inventory_migration_opening_request(tenant_id,batch_id,created_at);

CREATE FUNCTION warehouse_migration_opening_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE manifest jsonb; request jsonb; body jsonb; place inventory_location%ROWTYPE; document inventory_document%ROWTYPE;
    actual_lines jsonb; expected_lines jsonb;
BEGIN
    IF warehouse_lock_migration_batch(NEW.tenant_id,NEW.batch_id)<>NEW.cutover_epoch THEN
        RAISE EXCEPTION 'opening requires the current validating batch' USING ERRCODE='23514';
    END IF;
    manifest:=warehouse_migration_review_manifest(NEW.batch_id);
    IF NEW.review_manifest IS DISTINCT FROM manifest OR NEW.review_hash IS DISTINCT FROM encode(sha256(convert_to(manifest::text,'UTF8')),'hex')
        OR warehouse_migration_review_issues(manifest)<>'[]'::jsonb THEN
        RAISE EXCEPTION 'opening requires the complete current resolved review' USING ERRCODE='23514';
    END IF;
    SELECT * INTO place FROM inventory_location WHERE tenant_id=NEW.tenant_id AND id=NEW.review_location_id FOR SHARE;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.id FOR UPDATE;
    request:=NEW.canonical_payload::jsonb->'input'; body:=NEW.original_body::jsonb;
    IF place.id IS NULL OR place.state<>'ACTIVE' OR place.kind NOT IN ('WAREHOUSE','BIN') OR place.revision<>NEW.review_location_revision
        OR document.id IS NULL OR document.kind<>'OPENING_BALANCE' OR document.state<>'DRAFT' OR document.revision<>0
        OR document.migration_batch_id IS DISTINCT FROM NEW.batch_id OR document.actor_id<>NEW.actor_id
        OR document.cutover_epoch<>NEW.cutover_epoch OR document.authority_epoch<>NEW.authority_epoch
        OR document.supplier_id IS NOT NULL OR document.customer_id IS NOT NULL OR document.work_order_id IS NOT NULL
        OR document.source_document_id IS NOT NULL
        OR NEW.canonical_payload::jsonb->>'batchId' IS DISTINCT FROM NEW.batch_id::text
        OR NEW.payload_hash<>encode(sha256(convert_to(NEW.canonical_payload,'UTF8')),'hex')
        OR (request->>'expectedEpoch')::bigint IS DISTINCT FROM NEW.cutover_epoch
        OR request->>'expectedReviewHash' IS DISTINCT FROM NEW.review_hash
        OR request->>'reviewLocationId' IS DISTINCT FROM NEW.review_location_id::text
        OR (request->>'expectedReviewLocationRevision')::bigint IS DISTINCT FROM NEW.review_location_revision
        OR request->>'migrationReference' IS DISTINCT FROM document.source_reference OR request->>'reason' IS DISTINCT FROM document.reason THEN
        RAISE EXCEPTION 'opening requires its actual source document and review location' USING ERRCODE='23514';
    END IF;
    SELECT coalesce(jsonb_agg(jsonb_build_object('skuId',sku_id,'baseUnit',base_unit,'tracking',tracking,'quantityBase',quantity_base::text,
        'locationId',location_id,'custodianId',custodian_id,'custodianKind',custodian_kind,'condition',condition,'legalOwner',legal_owner)
        ORDER BY line_number),'[]'::jsonb) INTO actual_lines FROM inventory_document_line WHERE tenant_id=NEW.tenant_id AND document_id=NEW.id;
    SELECT coalesce(jsonb_agg(jsonb_build_object('skuId',entry->'resolution'->'stock'->>'skuId','baseUnit',entry->'resolution'->'stock'->>'baseUnit',
        'tracking',entry->'resolution'->'stock'->>'tracking','quantityBase',entry->'resolution'->'stock'->>'quantityBase',
        'locationId',entry->'resolution'->'stock'->>'locationId','custodianId',entry->'resolution'->'stock'->>'locationId',
        'custodianKind','WAREHOUSE','condition','SERVICEABLE','legalOwner','ISP') ORDER BY position),'[]'::jsonb)
        INTO expected_lines FROM jsonb_array_elements(manifest->'cases') WITH ORDINALITY cases(entry,position)
        WHERE entry->'resolution'->>'kind'='BASELINE_STOCK';
    IF actual_lines IS DISTINCT FROM expected_lines OR EXISTS (SELECT FROM inventory_document_line WHERE tenant_id=NEW.tenant_id AND document_id=NEW.id
        AND (stock_identity_id IS NOT NULL OR lot_id IS NOT NULL OR source_line_id IS NOT NULL OR destination_location_id IS NOT NULL
            OR cost_total_minor IS NOT NULL OR cost_basis_quantity_base IS NOT NULL OR currency IS NOT NULL OR accepted_base<>0
            OR rejected_base<>0 OR missing_base<>0 OR conversion_numerator IS NOT NULL OR document_revision<>0)) THEN
        RAISE EXCEPTION 'opening lines must exactly preserve resolved stock without invented origin or valuation' USING ERRCODE='23514';
    END IF;
    IF body->>'id' IS DISTINCT FROM NEW.id::text OR body->>'code' IS DISTINCT FROM document.code
        OR body->>'batchId' IS DISTINCT FROM NEW.batch_id::text OR body->>'reviewHash' IS DISTINCT FROM NEW.review_hash
        OR body->'manifest' IS DISTINCT FROM NEW.review_manifest
        OR body->'reviewLocation' IS DISTINCT FROM jsonb_build_object('id',place.id,'revision',place.revision,'areaId',place.area_id)
        OR body->>'requestedBy' IS DISTINCT FROM NEW.actor_id::text OR (body->>'authorityEpoch')::bigint IS DISTINCT FROM NEW.authority_epoch
        OR (body->>'cutoverEpoch')::bigint IS DISTINCT FROM NEW.cutover_epoch
        OR body->>'migrationReference' IS DISTINCT FROM document.source_reference OR body->>'reason' IS DISTINCT FROM document.reason
        OR (body->>'createdAt')::timestamptz IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'opening requires its exact original response' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_migration_opening_guard BEFORE INSERT ON inventory_migration_opening_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_migration_opening_guard();
CREATE TRIGGER warehouse_migration_opening_immutable BEFORE UPDATE OR DELETE ON inventory_migration_opening_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

-- A sealed draft cannot be edited through generic document commands or direct application SQL.
CREATE FUNCTION warehouse_migration_opening_document_seal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; scope uuid;
BEGIN
    IF TG_TABLE_NAME='inventory_document' THEN target:=OLD.id; scope:=OLD.tenant_id;
    ELSIF TG_OP='INSERT' THEN target:=NEW.document_id; scope:=NEW.tenant_id;
    ELSE target:=OLD.document_id; scope:=OLD.tenant_id; END IF;
    IF EXISTS (SELECT FROM inventory_migration_opening_request WHERE tenant_id=scope AND id=target) THEN
        RAISE EXCEPTION 'sealed opening requires independent approval before any mutation' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER warehouse_migration_opening_document_seal BEFORE UPDATE OR DELETE ON inventory_document
    FOR EACH ROW EXECUTE FUNCTION warehouse_migration_opening_document_seal();
CREATE TRIGGER warehouse_migration_opening_line_seal BEFORE INSERT OR UPDATE OR DELETE ON inventory_document_line
    FOR EACH ROW EXECUTE FUNCTION warehouse_migration_opening_document_seal();

ALTER TABLE inventory_migration_opening_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_migration_opening_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_migration_opening_request
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE UPDATE,DELETE ON inventory_migration_opening_request FROM warehouse_app;
        GRANT SELECT,INSERT ON inventory_migration_opening_request TO warehouse_app;
    END IF;
END $$;
