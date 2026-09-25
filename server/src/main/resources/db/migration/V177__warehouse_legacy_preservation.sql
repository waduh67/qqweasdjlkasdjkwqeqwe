-- M05: immutable evidence only. No winner, stock admission, unit conversion or posting.
CREATE TABLE inventory_provenance_case (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    source_table text NOT NULL CHECK (source_table IN (
        'inventory_serialized_asset','inventory_balance_projection','onu','inventory_serial_tombstone',
        'inventory_movement','inventory_movement_leg','inventory_fulfillment_effect','inventory_customer_material_fact')),
    source_id uuid NOT NULL,
    source_snapshot jsonb NOT NULL CHECK (jsonb_typeof(source_snapshot)='object'),
    source_hash text NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    captured_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,source_table,source_id),
    CHECK (source_snapshot->>'id'=source_id::text AND source_snapshot->>'tenantId'=tenant_id::text
        AND source_snapshot ?& ARRAY['id','tenantId'])
);

CREATE FUNCTION warehouse_provenance_case_capture() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.source_hash:=encode(sha256(convert_to(NEW.source_snapshot::text,'UTF8')),'hex');
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_provenance_case_capture BEFORE INSERT ON inventory_provenance_case
    FOR EACH ROW EXECUTE FUNCTION warehouse_provenance_case_capture();

-- Deliberate field lists avoid copying customer contacts, credentials or telemetry.
-- Quantities remain decimal strings with their original unit uncertainty.
INSERT INTO inventory_provenance_case(tenant_id,source_table,source_id,source_snapshot)
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
SELECT tenant_id,'onu',id,jsonb_build_object(
    'id',id,'tenantId',tenant_id,'customerId',customer_id,'serialNumber',serial_number,'model',model,
    'canonicalSerialCandidate',canonical_serial_candidate,'assetId',asset_id,'assignmentId',assignment_id,
    'installedAt',installed_at,'startedAt',started_at,'retiredAt',retired_at,'provenance',provenance,
    'odpId',odp_id,'portNumber',odp_port_number,'originalCustomerId',original_customer_id,
    'customerLabel',(SELECT name FROM customer WHERE tenant_id=onu.tenant_id AND id=onu.customer_id),
    'areaId',(SELECT area_id FROM customer WHERE tenant_id=onu.tenant_id AND id=onu.customer_id))
FROM onu WHERE warehouse_admission='LEGACY_UNRESOLVED'
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
FROM inventory_customer_material_fact WHERE warehouse_admission='LEGACY_UNRESOLVED';

ALTER TABLE inventory_provenance_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_provenance_case FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_provenance_case
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_provenance_case_immutable BEFORE UPDATE OR DELETE ON inventory_provenance_case
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE TABLE inventory_migration_batch (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    cutover_epoch bigint NOT NULL CHECK (cutover_epoch>0),
    snapshot_watermark text NOT NULL CHECK (btrim(snapshot_watermark)<>''),
    requested_by uuid NOT NULL,
    source_manifest jsonb NOT NULL CHECK (jsonb_typeof(source_manifest)='array'),
    source_count bigint GENERATED ALWAYS AS (jsonb_array_length(source_manifest)) STORED,
    source_hash text NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,cutover_epoch)
);

CREATE FUNCTION warehouse_migration_batch_capture() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cutover inventory_tenant_cutover%ROWTYPE;
BEGIN
    SELECT * INTO cutover FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id FOR UPDATE;
    IF cutover.id IS NULL OR cutover.state<>'VALIDATING' OR cutover.epoch<>NEW.cutover_epoch
        OR cutover.migration_batch_id IS DISTINCT FROM NEW.id
        OR cutover.snapshot_watermark IS DISTINCT FROM NEW.snapshot_watermark THEN
        RAISE EXCEPTION 'migration batch requires the exclusive validating cutover fence' USING ERRCODE='23514';
    END IF;
    SELECT coalesce(jsonb_agg(jsonb_build_object('caseId',id,'sourceTable',source_table,
        'sourceId',source_id,'sourceHash',source_hash) ORDER BY source_table,source_id),'[]'::jsonb)
        INTO NEW.source_manifest FROM inventory_provenance_case WHERE tenant_id=NEW.tenant_id;
    NEW.source_hash:=encode(sha256(convert_to(NEW.source_manifest::text,'UTF8')),'hex');
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_migration_batch_capture BEFORE INSERT ON inventory_migration_batch
    FOR EACH ROW EXECUTE FUNCTION warehouse_migration_batch_capture();
CREATE TRIGGER warehouse_migration_batch_immutable BEFORE UPDATE OR DELETE ON inventory_migration_batch
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
ALTER TABLE inventory_migration_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_migration_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_migration_batch
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);

DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE INSERT,UPDATE,DELETE ON inventory_provenance_case FROM warehouse_app;
        REVOKE UPDATE,DELETE ON inventory_migration_batch FROM warehouse_app;
        GRANT SELECT ON inventory_provenance_case TO warehouse_app;
        GRANT SELECT,INSERT ON inventory_migration_batch TO warehouse_app;
    END IF;
END $$;
