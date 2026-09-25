-- Live owner projections are read-only, tenant-RLS-invoking contracts. They never post stock.
-- JSON field lists match the boot snapshots, so an unchanged source reuses its old case ID.
CREATE VIEW inventory_live_provenance_source WITH (security_invoker=true) AS
SELECT tenant_id,source_table,source_id,source_snapshot,
    encode(sha256(convert_to(source_snapshot::text,'UTF8')),'hex') source_hash
FROM (
SELECT tenant_id,'inventory_serialized_asset',id,jsonb_build_object(
    'id',id,'tenantId',tenant_id,'legacySkuId',sku_id,'serialNumber',serial_number,'macAddress',mac_address,
    'canonicalSerialCandidate',canonical_serial_candidate,'canonicalMacCandidate',canonical_mac_candidate,
    'baseUnitCandidate',base_unit_candidate,'status',status,'locationId',location_id,
    'custodyOwnerId',custody_owner_id,'custodyOwnerKind',custody_owner_kind,'installedOnuId',installed_onu_id,
    'warehouseSkuId',warehouse_sku_id,'baseUnit',base_unit,'quantityBase',quantity_base::text,
    'condition',condition,'legalOwner',legal_owner,'createdAt',created_at)
FROM inventory_serialized_asset WHERE warehouse_admission='LEGACY_UNRESOLVED'
UNION ALL
SELECT tenant_id,'inventory_balance_projection',id,jsonb_build_object(
    'id',id,'tenantId',tenant_id,'itemId',item_id,'legacySkuId',sku_id,'locationId',location_id,
    'custodyOwnerId',custody_owner_id,'custodyOwnerKind',custody_owner_kind,'status',status,
    'legacyQuantity',quantity::text,'quantityBase',quantity_base::text,'baseUnit',base_unit,
    'stockIdentityId',stock_identity_id,'lotId',lot_id,'condition',condition,'legalOwner',legal_owner,
    'rebuiltAt',rebuilt_at)
FROM inventory_balance_projection WHERE warehouse_admission='LEGACY_UNRESOLVED'
UNION ALL
SELECT tenant_id,'inventory_serial_tombstone',id,jsonb_build_object(
    'id',id,'tenantId',tenant_id,'serialNumber',serial_number,'macAddress',mac_address,'retiredAt',retired_at)
FROM inventory_serial_tombstone
UNION ALL
SELECT tenant_id,'inventory_movement',id,jsonb_build_object(
    'id',id,'tenantId',tenant_id,'namespace',operation_namespace,'operationKey',operation_key,
    'payloadHash',payload_hash,'actorId',actor_id,'kind',kind,'state',state,
    'serverReceivedAt',server_received_at,'compensatesMovementId',compensates_movement_id)
FROM inventory_movement WHERE warehouse_admission='LEGACY_UNRESOLVED'
UNION ALL
SELECT tenant_id,'inventory_movement_leg',id,jsonb_build_object(
    'id',id,'tenantId',tenant_id,'movementId',movement_id,'direction',direction,'itemId',item_id,
    'legacySkuId',sku_id,'locationId',location_id,'legacyQuantity',quantity::text,'serialized',serialized,
    'quantityBase',quantity_base::text,'baseUnit',base_unit,'custodyOwnerId',custody_owner_id,
    'custodyOwnerKind',custody_owner_kind,'status',status)
FROM inventory_movement_leg WHERE warehouse_admission='LEGACY_UNRESOLVED'
UNION ALL
SELECT tenant_id,'inventory_fulfillment_effect',id,jsonb_build_object(
    'id',id,'tenantId',tenant_id,'targetId',target_id,'workOrderId',work_order_id,'customerId',customer_id,
    'namespace',namespace,'operationKey',operation_key,'payloadHash',payload_hash,'itemCategory',item_category,
    'legacyQuantity',quantity::text,'quantityBase',quantity_base::text,'baseUnit',base_unit,
    'installed',installed,'returned',returned,'recordedAt',recorded_at)
FROM inventory_fulfillment_effect WHERE warehouse_admission='LEGACY_UNRESOLVED'
UNION ALL
SELECT tenant_id,'inventory_customer_material_fact',id,jsonb_build_object(
    'id',id,'tenantId',tenant_id,'workOrderId',work_order_id,'customerId',customer_id,
    'operationKey',operation_key,'payloadHash',payload_hash,'itemCategory',item_category,
    'legacyQuantity',quantity::text,'quantityBase',quantity_base::text,'baseUnit',base_unit,
    'installed',installed,'returned',returned,'recordedAt',recorded_at)
FROM inventory_customer_material_fact WHERE warehouse_admission='LEGACY_UNRESOLVED'
) raw(tenant_id,source_table,source_id,source_snapshot);

CREATE VIEW customer_live_provenance_source WITH (security_invoker=true) AS
SELECT tenant_id,source_table,source_id,source_snapshot,
    encode(sha256(convert_to(source_snapshot::text,'UTF8')),'hex') source_hash
FROM (
SELECT tenant_id,'onu',id,jsonb_build_object(
    'id',id,'tenantId',tenant_id,'customerId',customer_id,'serialNumber',serial_number,'model',model,
    'canonicalSerialCandidate',canonical_serial_candidate,'assetId',asset_id,'assignmentId',assignment_id,
    'installedAt',installed_at,'startedAt',started_at,'retiredAt',retired_at,'provenance',provenance,
    'odpId',odp_id,'portNumber',odp_port_number,'originalCustomerId',original_customer_id,
    'customerLabel',(SELECT name FROM customer WHERE tenant_id=onu.tenant_id AND id=onu.customer_id),
    'areaId',(SELECT area_id FROM customer WHERE tenant_id=onu.tenant_id AND id=onu.customer_id))
FROM onu WHERE warehouse_admission='LEGACY_UNRESOLVED'
) raw(tenant_id,source_table,source_id,source_snapshot);

-- Database admission checks use these public owner projections, not client-supplied snapshots.
CREATE VIEW warehouse_live_provenance_source WITH (security_invoker=true) AS
    SELECT * FROM inventory_live_provenance_source UNION ALL SELECT * FROM customer_live_provenance_source;

DO $$ DECLARE constraint_name text; BEGIN
    SELECT conname INTO STRICT constraint_name FROM pg_constraint
        WHERE conrelid='inventory_provenance_case'::regclass AND contype='u' AND
            (SELECT array_agg(attribute.attname::text ORDER BY key.ordinality) FROM unnest(conkey) WITH ORDINALITY key(num,ordinality)
                JOIN pg_attribute attribute ON attribute.attrelid=conrelid AND attribute.attnum=key.num)
                =ARRAY['tenant_id','source_table','source_id'];
    EXECUTE format('ALTER TABLE inventory_provenance_case DROP CONSTRAINT %I',constraint_name);
END $$;
ALTER TABLE inventory_provenance_case ADD UNIQUE(tenant_id,source_table,source_id,source_hash);

CREATE FUNCTION warehouse_provenance_case_id(scope uuid, source_kind text, source uuid, hash text) RETURNS uuid LANGUAGE sql STABLE AS $$
    SELECT coalesce((SELECT id FROM inventory_provenance_case WHERE tenant_id=scope AND source_table=source_kind
        AND source_id=source AND source_hash=hash),
        substr(encode(sha256(convert_to(scope::text||'|'||source_kind||'|'||source::text||'|'||hash,'UTF8')),'hex'),1,32)::uuid)
$$;

-- Before a batch exists, preview current owner data without writing from GET.
-- Afterwards, reports use exactly the frozen manifest, retaining old versions in the base table.
CREATE VIEW warehouse_report_provenance_case WITH (security_invoker=true) AS
    SELECT warehouse_provenance_case_id(live.tenant_id,live.source_table,live.source_id,live.source_hash) id,
        live.tenant_id,live.source_table,live.source_id,live.source_snapshot,live.source_hash,NULL::timestamptz captured_at
    FROM warehouse_live_provenance_source live
    WHERE NOT EXISTS (SELECT FROM inventory_migration_batch batch JOIN inventory_tenant_cutover cutover
        ON cutover.tenant_id=batch.tenant_id AND cutover.migration_batch_id=batch.id WHERE batch.tenant_id=live.tenant_id)
    UNION ALL
    SELECT source.id,source.tenant_id,source.source_table,source.source_id,source.source_snapshot,source.source_hash,source.captured_at
    FROM inventory_provenance_case source JOIN inventory_tenant_cutover cutover ON cutover.tenant_id=source.tenant_id
        JOIN inventory_migration_batch batch ON batch.tenant_id=cutover.tenant_id AND batch.id=cutover.migration_batch_id
    WHERE EXISTS (SELECT FROM jsonb_array_elements(batch.source_manifest) member
        WHERE member->>'caseId'=source.id::text AND member->>'sourceHash'=source.source_hash);

CREATE OR REPLACE FUNCTION warehouse_provenance_case_capture() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cutover inventory_tenant_cutover%ROWTYPE;
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM NULLIF(current_setting('app.tenant_id',true),'')::uuid THEN
        RAISE EXCEPTION 'source capture requires the current tenant' USING ERRCODE='42501';
    END IF;
    SELECT * INTO cutover FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id FOR UPDATE;
    IF cutover.id IS NULL OR cutover.state<>'VALIDATING' OR cutover.migration_batch_id IS NULL
        OR EXISTS (SELECT FROM inventory_migration_batch WHERE tenant_id=NEW.tenant_id AND id=cutover.migration_batch_id) THEN
        RAISE EXCEPTION 'source capture requires the exclusive initial validating cutoff' USING ERRCODE='42501';
    END IF;
    NEW.source_hash:=encode(sha256(convert_to(NEW.source_snapshot::text,'UTF8')),'hex');
    IF NOT EXISTS (SELECT FROM warehouse_live_provenance_source actual WHERE actual.tenant_id=NEW.tenant_id
        AND actual.source_table=NEW.source_table AND actual.source_id=NEW.source_id AND actual.source_hash=NEW.source_hash
        AND actual.source_snapshot=NEW.source_snapshot)
        OR NEW.id IS DISTINCT FROM warehouse_provenance_case_id(NEW.tenant_id,NEW.source_table,NEW.source_id,NEW.source_hash) THEN
        RAISE EXCEPTION 'source capture must match the actual owner data and stable case identity' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION warehouse_migration_batch_capture() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cutover inventory_tenant_cutover%ROWTYPE; actual_count bigint;
BEGIN
    SELECT * INTO cutover FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id FOR UPDATE;
    IF cutover.id IS NULL OR cutover.state<>'VALIDATING' OR cutover.epoch<>NEW.cutover_epoch
        OR cutover.migration_batch_id IS DISTINCT FROM NEW.id OR cutover.snapshot_watermark IS DISTINCT FROM NEW.snapshot_watermark THEN
        RAISE EXCEPTION 'migration batch requires the exclusive validating cutover fence' USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO actual_count FROM warehouse_live_provenance_source WHERE tenant_id=NEW.tenant_id;
    SELECT coalesce(jsonb_agg(jsonb_build_object('caseId',source.id,'sourceTable',source.source_table,
        'sourceId',source.source_id,'sourceHash',source.source_hash) ORDER BY source.source_table,source.source_id),'[]'::jsonb)
        INTO NEW.source_manifest FROM warehouse_live_provenance_source actual JOIN inventory_provenance_case source
            ON source.tenant_id=actual.tenant_id AND source.source_table=actual.source_table AND source.source_id=actual.source_id
                AND source.source_hash=actual.source_hash AND source.source_snapshot=actual.source_snapshot
        WHERE actual.tenant_id=NEW.tenant_id;
    IF jsonb_array_length(NEW.source_manifest)<>actual_count THEN
        RAISE EXCEPTION 'every current source must be captured at the cutoff' USING ERRCODE='23514';
    END IF;
    NEW.source_hash:=encode(sha256(convert_to(NEW.source_manifest::text,'UTF8')),'hex');
    RETURN NEW;
END $$;

DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT ON inventory_live_provenance_source,customer_live_provenance_source,
            warehouse_live_provenance_source,warehouse_report_provenance_case TO warehouse_app;
        GRANT INSERT ON inventory_provenance_case TO warehouse_app;
    END IF;
END $$;

-- Eligibility checks use the captured generation, never an obsolete historical version.
CREATE OR REPLACE FUNCTION warehouse_migration_stock(p_case uuid, p_sku uuid, p_unit text, p_owner text) RETURNS jsonb LANGUAGE plpgsql AS $$
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
            OR EXISTS (SELECT FROM warehouse_report_provenance_case episode WHERE episode.tenant_id=source.tenant_id AND episode.source_table='onu'
                AND episode.source_snapshot->>'retiredAt' IS NULL AND (episode.source_snapshot->>'assetId'=source.source_id::text
                    OR warehouse_canonical_serial(episode.source_snapshot->>'serialNumber')=serial)) THEN
            RAISE EXCEPTION 'installed, malformed or nonserialized source cannot become available serialized stock' USING ERRCODE='23514';
        END IF;
        amount:=1; unit:='EA';
    ELSE
        IF item.tracking='SERIAL' OR EXISTS (SELECT FROM warehouse_report_provenance_case asset
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
