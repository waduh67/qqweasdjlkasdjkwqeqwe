-- Resolution proposals are immutable evidence for later independent batch approval.
-- None of these functions grants stock posting, identity admission or cutover authority.
CREATE TABLE inventory_migration_resolution (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), batch_id uuid NOT NULL, case_id uuid NOT NULL,
    source_hash text NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'), cutover_epoch bigint NOT NULL CHECK (cutover_epoch>0),
    revision bigint NOT NULL CHECK (revision>0),
    kind text NOT NULL CHECK (kind IN ('BASELINE_STOCK','PROVENANCE_ONLY','DUPLICATE','CANCEL_PENDING')),
    reason varchar(2000) NOT NULL CHECK (btrim(reason)<>'' AND reason !~ '[[:cntrl:]]'),
    evidence_manifest jsonb NOT NULL CHECK (jsonb_typeof(evidence_manifest)='array' AND jsonb_array_length(evidence_manifest) BETWEEN 1 AND 10),
    stock jsonb, duplicate_case_id uuid, duplicate_resolution_id uuid,
    actor_id uuid NOT NULL, operation_key varchar(240) NOT NULL, canonical_payload text NOT NULL,
    payload_hash text NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'), original_body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,operation_key), UNIQUE (tenant_id,batch_id,case_id,revision),
    FOREIGN KEY (tenant_id,batch_id) REFERENCES inventory_migration_batch(tenant_id,id),
    FOREIGN KEY (tenant_id,case_id) REFERENCES inventory_provenance_case(tenant_id,id),
    FOREIGN KEY (tenant_id,duplicate_case_id) REFERENCES inventory_provenance_case(tenant_id,id),
    FOREIGN KEY (tenant_id,duplicate_resolution_id) REFERENCES inventory_migration_resolution(tenant_id,id),
    CHECK ((kind='BASELINE_STOCK')=(stock IS NOT NULL)),
    CHECK ((kind='DUPLICATE')=(duplicate_case_id IS NOT NULL) AND (kind='DUPLICATE')=(duplicate_resolution_id IS NOT NULL)),
    CHECK (duplicate_case_id IS DISTINCT FROM case_id)
);

CREATE FUNCTION warehouse_migration_stock(p_case uuid, p_sku uuid, p_unit text, p_owner text) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE source inventory_provenance_case%ROWTYPE; item inventory_sku%ROWTYPE; place inventory_location%ROWTYPE;
    data jsonb; amount numeric; unit text; raw_amount text; serial text;
BEGIN
    SELECT * INTO source FROM inventory_provenance_case
        WHERE tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid AND id=p_case;
    data:=source.source_snapshot;
    SELECT * INTO item FROM inventory_sku WHERE tenant_id=source.tenant_id AND id=p_sku FOR SHARE;
    SELECT * INTO place FROM inventory_location WHERE tenant_id=source.tenant_id AND id=(data->>'locationId')::uuid FOR SHARE;
    IF source.id IS NULL OR source.source_table NOT IN ('inventory_serialized_asset','inventory_balance_projection')
        OR item.id IS NULL OR item.state<>'ACTIVE' OR place.id IS NULL OR place.state<>'ACTIVE'
        OR place.kind NOT IN ('WAREHOUSE','BIN') OR p_owner IS DISTINCT FROM 'ISP' OR p_unit IS NULL OR p_unit NOT IN ('EA','MM','M')
        OR data->>'status' IS DISTINCT FROM 'AVAILABLE' OR data->>'custodyOwnerKind' IS DISTINCT FROM 'WAREHOUSE'
        OR data->>'custodyOwnerId' IS DISTINCT FROM place.id::text OR data->>'legalOwner'='CUSTOMER'
        OR coalesce(data->>'condition','SERVICEABLE')<>'SERVICEABLE'
        OR (data->>'warehouseSkuId' IS NOT NULL AND data->>'warehouseSkuId'<>p_sku::text) THEN
        RAISE EXCEPTION 'baseline requires proven ISP stock at its original active warehouse and SKU' USING ERRCODE='23514';
    END IF;
    IF source.source_table='inventory_serialized_asset' THEN
        serial:=warehouse_canonical_serial(data->>'serialNumber');
        IF item.tracking<>'SERIAL' OR p_unit<>'EA' OR serial IS NULL OR data->>'installedOnuId' IS NOT NULL
            OR (data->>'macAddress' IS NOT NULL AND warehouse_canonical_mac(data->>'macAddress') IS NULL)
            OR EXISTS (SELECT FROM inventory_provenance_case episode WHERE episode.tenant_id=source.tenant_id AND episode.source_table='onu'
                AND episode.source_snapshot->>'retiredAt' IS NULL AND (episode.source_snapshot->>'assetId'=source.source_id::text
                    OR warehouse_canonical_serial(episode.source_snapshot->>'serialNumber')=serial)) THEN
            RAISE EXCEPTION 'installed, malformed or nonserialized source cannot become available serialized stock' USING ERRCODE='23514';
        END IF;
        amount:=1; unit:='EA';
    ELSE
        IF item.tracking='SERIAL' OR EXISTS (SELECT FROM inventory_provenance_case asset
            WHERE asset.tenant_id=source.tenant_id AND asset.source_table='inventory_serialized_asset'
                AND asset.source_id::text=data->>'itemId') THEN
            RAISE EXCEPTION 'serialized balance must reconcile its existing physical asset without counting it twice' USING ERRCODE='23514';
        END IF;
        IF data->>'baseUnit' IS NOT NULL THEN
            IF data->>'baseUnit'<>p_unit THEN
                RAISE EXCEPTION 'known base unit cannot be reinterpreted' USING ERRCODE='23514';
            END IF;
            raw_amount:=data->>'quantityBase';
        ELSE raw_amount:=data->>'legacyQuantity'; END IF;
        IF raw_amount IS NULL OR raw_amount !~ '^[0-9]+$' THEN
            RAISE EXCEPTION 'baseline quantity must preserve a nonnegative source integer' USING ERRCODE='23514';
        END IF;
        amount:=raw_amount::numeric*CASE WHEN p_unit='M' THEN 1000 ELSE 1 END;
        unit:=CASE WHEN p_unit='M' THEN 'MM' ELSE p_unit END;
    END IF;
    IF amount<1 OR amount>9223372036854775807 OR item.base_unit<>unit THEN
        RAISE EXCEPTION 'baseline quantity or SKU unit is invalid' USING ERRCODE='23514';
    END IF;
    RETURN jsonb_build_object('skuId',item.id,'skuRevision',item.revision,'locationId',place.id,'locationRevision',place.revision,
        'tracking',item.tracking,'sourceUnit',p_unit,'quantityBase',amount::bigint::text,'baseUnit',unit,'legalOwner','ISP');
END $$;

CREATE FUNCTION warehouse_migration_duplicate(p_batch uuid, p_case uuid, p_target uuid) RETURNS uuid LANGUAGE plpgsql AS $$
DECLARE source inventory_provenance_case%ROWTYPE; target inventory_provenance_case%ROWTYPE;
    winner inventory_migration_resolution%ROWTYPE; serial text;
BEGIN
    SELECT * INTO source FROM inventory_provenance_case
        WHERE tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid AND id=p_case;
    SELECT * INTO target FROM inventory_provenance_case WHERE tenant_id=source.tenant_id AND id=p_target;
    SELECT * INTO winner FROM inventory_migration_resolution WHERE tenant_id=source.tenant_id AND batch_id=p_batch AND case_id=p_target
        ORDER BY revision DESC LIMIT 1;
    IF source.id IS NULL OR target.id IS NULL OR source.id=target.id OR target.source_table<>'inventory_serialized_asset'
        OR winner.id IS NULL OR winner.kind<>'BASELINE_STOCK' OR source.source_snapshot->>'status' IS DISTINCT FROM 'AVAILABLE'
        OR source.source_snapshot->>'locationId' IS DISTINCT FROM target.source_snapshot->>'locationId' THEN
        RAISE EXCEPTION 'duplicate requires an existing baseline proposal for the same physical source' USING ERRCODE='23514';
    END IF;
    IF source.source_table='inventory_serialized_asset' THEN
        serial:=warehouse_canonical_serial(source.source_snapshot->>'serialNumber');
        IF serial IS NULL OR serial IS DISTINCT FROM warehouse_canonical_serial(target.source_snapshot->>'serialNumber')
            OR source.source_snapshot->>'installedOnuId' IS NOT NULL
            OR (source.source_snapshot->>'macAddress' IS NOT NULL AND target.source_snapshot->>'macAddress' IS NOT NULL
                AND warehouse_canonical_mac(source.source_snapshot->>'macAddress') IS DISTINCT FROM warehouse_canonical_mac(target.source_snapshot->>'macAddress')) THEN
            RAISE EXCEPTION 'duplicate identities must agree without rewriting original values' USING ERRCODE='23514';
        END IF;
    ELSIF source.source_table='inventory_balance_projection' THEN
        IF source.source_snapshot->>'itemId' IS DISTINCT FROM target.source_id::text
            OR source.source_snapshot->>'legacyQuantity' IS DISTINCT FROM '1' THEN
            RAISE EXCEPTION 'duplicate balance requires exact existing asset and one unit' USING ERRCODE='23514';
        END IF;
    ELSE RAISE EXCEPTION 'historical or installed source is not duplicate available stock' USING ERRCODE='23514'; END IF;
    RETURN winner.id;
END $$;

CREATE FUNCTION warehouse_migration_resolution_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source inventory_provenance_case%ROWTYPE; payload jsonb; request jsonb; body jsonb; evidence jsonb; latest bigint;
BEGIN
    IF warehouse_lock_migration_batch(NEW.tenant_id,NEW.batch_id)<>NEW.cutover_epoch THEN
        RAISE EXCEPTION 'resolution requires the current validating batch' USING ERRCODE='23514';
    END IF;
    SELECT * INTO source FROM inventory_provenance_case WHERE tenant_id=NEW.tenant_id AND id=NEW.case_id AND source_hash=NEW.source_hash;
    IF source.id IS NULL OR NOT EXISTS (SELECT FROM inventory_migration_batch batch, jsonb_array_elements(batch.source_manifest) member
        WHERE batch.tenant_id=NEW.tenant_id AND batch.id=NEW.batch_id AND member->>'caseId'=NEW.case_id::text AND member->>'sourceHash'=NEW.source_hash) THEN
        RAISE EXCEPTION 'resolution requires the captured case hash' USING ERRCODE='23514';
    END IF;
    SELECT coalesce(max(revision),0) INTO latest FROM inventory_migration_resolution WHERE tenant_id=NEW.tenant_id AND batch_id=NEW.batch_id AND case_id=NEW.case_id;
    payload:=NEW.canonical_payload::jsonb; request:=payload->'input'; body:=NEW.original_body::jsonb;
    SELECT jsonb_agg(jsonb_build_object('id',file.id,'sourceHash',file.source_hash,'sha256',file.sha256,'uploadedBy',file.actor_id) ORDER BY requested.ordinality)
        INTO evidence FROM jsonb_array_elements_text(request->'evidenceIds') WITH ORDINALITY requested(id,ordinality)
        JOIN inventory_migration_evidence file ON file.tenant_id=NEW.tenant_id AND file.batch_id=NEW.batch_id AND file.case_id=NEW.case_id
            AND file.id=requested.id::uuid AND file.source_hash=NEW.source_hash;
    IF NEW.revision<>latest+1 OR (request->>'expectedResolutionRevision')::bigint IS DISTINCT FROM latest
        OR NEW.payload_hash<>encode(sha256(convert_to(NEW.canonical_payload,'UTF8')),'hex')
        OR payload->>'batchId' IS DISTINCT FROM NEW.batch_id::text OR payload->>'caseId' IS DISTINCT FROM NEW.case_id::text
        OR (request->>'expectedEpoch')::bigint IS DISTINCT FROM NEW.cutover_epoch OR request->>'expectedCaseHash' IS DISTINCT FROM NEW.source_hash
        OR request->>'kind' IS DISTINCT FROM NEW.kind OR request->>'reason' IS DISTINCT FROM NEW.reason
        OR request->>'duplicateCaseId' IS DISTINCT FROM NEW.duplicate_case_id::text
        OR NEW.evidence_manifest IS DISTINCT FROM evidence OR jsonb_array_length(request->'evidenceIds') IS DISTINCT FROM jsonb_array_length(evidence)
        OR (SELECT count(DISTINCT value) FROM jsonb_array_elements_text(request->'evidenceIds'))<>jsonb_array_length(evidence) THEN
        RAISE EXCEPTION 'resolution requires exact command, revision and case-bound evidence' USING ERRCODE='23514';
    END IF;
    IF NEW.kind='BASELINE_STOCK' THEN
        IF NEW.stock IS DISTINCT FROM warehouse_migration_stock(NEW.case_id,(request->'stock'->>'skuId')::uuid,
            request->'stock'->>'sourceUnit',request->'stock'->>'legalOwner') THEN
            RAISE EXCEPTION 'resolution stock must be derived from the preserved source' USING ERRCODE='23514';
        END IF;
    ELSIF request->>'stock' IS NOT NULL THEN
        RAISE EXCEPTION 'non-stock resolution cannot declare stock' USING ERRCODE='23514';
    END IF;
    IF NEW.kind='DUPLICATE' AND NEW.duplicate_resolution_id IS DISTINCT FROM warehouse_migration_duplicate(NEW.batch_id,NEW.case_id,NEW.duplicate_case_id) THEN
        RAISE EXCEPTION 'duplicate requires the current winner revision' USING ERRCODE='23514';
    END IF;
    IF (NEW.kind='CANCEL_PENDING') IS DISTINCT FROM (source.source_table='inventory_movement' AND source.source_snapshot->>'state'<>'APPLIED') THEN
        RAISE EXCEPTION 'pending legacy effect requires explicit cancellation review' USING ERRCODE='23514';
    END IF;
    IF body->>'id' IS DISTINCT FROM NEW.id::text OR body->>'batchId' IS DISTINCT FROM NEW.batch_id::text
        OR body->>'caseId' IS DISTINCT FROM NEW.case_id::text OR body->>'sourceHash' IS DISTINCT FROM NEW.source_hash
        OR (body->>'revision')::bigint IS DISTINCT FROM NEW.revision OR body->>'kind' IS DISTINCT FROM NEW.kind OR body->>'reason' IS DISTINCT FROM NEW.reason
        OR body->'evidence' IS DISTINCT FROM NEW.evidence_manifest OR body->'stock' IS DISTINCT FROM coalesce(NEW.stock,'null'::jsonb)
        OR body->>'duplicateCaseId' IS DISTINCT FROM NEW.duplicate_case_id::text OR body->>'duplicateResolutionId' IS DISTINCT FROM NEW.duplicate_resolution_id::text
        OR body->>'resolvedBy' IS DISTINCT FROM NEW.actor_id::text OR (body->>'createdAt')::timestamptz IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'resolution requires its exact original response' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER warehouse_migration_resolution_guard BEFORE INSERT ON inventory_migration_resolution
    FOR EACH ROW EXECUTE FUNCTION warehouse_migration_resolution_guard();
CREATE TRIGGER warehouse_migration_resolution_immutable BEFORE UPDATE OR DELETE ON inventory_migration_resolution
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
ALTER TABLE inventory_migration_resolution ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_migration_resolution FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_migration_resolution
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE UPDATE,DELETE ON inventory_migration_resolution FROM warehouse_app;
        GRANT SELECT,INSERT ON inventory_migration_resolution TO warehouse_app;
    END IF;
END $$;
