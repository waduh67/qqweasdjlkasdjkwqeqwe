CREATE FUNCTION warehouse_revision_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.id IS DISTINCT FROM OLD.id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'warehouse identity and tenant are immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.revision <> OLD.revision + 1 THEN
        RAISE EXCEPTION 'warehouse stale revision' USING ERRCODE = '40001';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'warehouse history is append-only' USING ERRCODE = '23514';
END $$;

CREATE FUNCTION warehouse_admission_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF current_user <> pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid=TG_RELID)) THEN
        IF (TG_OP = 'INSERT' AND NEW.warehouse_admission <> 'VERIFIED') OR
           (TG_OP = 'UPDATE' AND NEW.warehouse_admission IS DISTINCT FROM OLD.warehouse_admission) THEN
            RAISE EXCEPTION 'warehouse admission requires migration owner' USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_canonical_mac(raw_value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT CASE WHEN btrim(raw_value) ~ '^([0-9A-Fa-f]{12}|[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}|[0-9A-Fa-f]{4}(\.[0-9A-Fa-f]{4}){2})$'
        THEN upper(translate(btrim(raw_value), ':-.', '')) END
$$;

CREATE TABLE inventory_sku (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
    code varchar(64) NOT NULL CHECK (btrim(code) <> ''),
    name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    tracking varchar(8) NOT NULL CHECK (tracking IN ('SERIAL','LOT','BULK')),
    base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')),
    allowed_ownership_modes text[] NOT NULL DEFAULT ARRAY['LOAN','SALE']::text[],
    inspection_required boolean NOT NULL DEFAULT true,
    state varchar(8) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','ARCHIVED')),
    UNIQUE (tenant_id, code), UNIQUE (tenant_id, id), UNIQUE (tenant_id, id, base_unit),
    CHECK (tracking <> 'SERIAL' OR base_unit = 'EA'),
    CHECK (cardinality(allowed_ownership_modes)>0 AND allowed_ownership_modes <@ ARRAY['LOAN','SALE']::text[])
);

CREATE TABLE inventory_uom_conversion (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL,
    package_unit varchar(40) NOT NULL CHECK (btrim(package_unit) <> ''),
    numerator bigint NOT NULL CHECK (numerator > 0), denominator bigint NOT NULL CHECK (denominator > 0),
    state varchar(8) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','ARCHIVED')),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,sku_id,package_unit),
    FOREIGN KEY (tenant_id,sku_id) REFERENCES inventory_sku(tenant_id,id)
);

CREATE TABLE inventory_supplier (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
    code varchar(64) NOT NULL CHECK (btrim(code) <> ''), name varchar(200) NOT NULL CHECK (btrim(name) <> ''),
    contact_reference varchar(500), state varchar(8) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','ARCHIVED')),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,code)
);

CREATE TABLE inventory_tenant_cutover (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL UNIQUE,
    state varchar(10) NOT NULL DEFAULT 'LEGACY' CHECK (state IN ('LEGACY','VALIDATING','ENFORCED')),
    epoch bigint NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    activation_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    initialization_kind varchar(20) NOT NULL DEFAULT 'EXISTING' CHECK (initialization_kind IN ('EXISTING','NEW_EMPTY')),
    migration_batch_id uuid, snapshot_watermark text, pending_legacy_effect_ids uuid[] NOT NULL DEFAULT '{}',
    UNIQUE (tenant_id,id), CHECK (state <> 'VALIDATING' OR (migration_batch_id IS NOT NULL AND snapshot_watermark IS NOT NULL)),
    CHECK (state <> 'ENFORCED' OR initialization_kind = 'NEW_EMPTY' OR snapshot_watermark IS NOT NULL)
);

DO $$ BEGIN
    EXECUTE format('CREATE FUNCTION warehouse_activation_at() RETURNS timestamptz LANGUAGE sql IMMUTABLE AS %L',
        format('SELECT %L::timestamptz',transaction_timestamp()));
END $$;

CREATE TABLE iam_authorization_epoch (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL UNIQUE,
    epoch bigint NOT NULL DEFAULT 0 CHECK (epoch >= 0), UNIQUE (tenant_id,id)
);

ALTER TABLE inventory_location
    ADD COLUMN name varchar(200),
    ADD COLUMN parent_location_id uuid,
    ADD COLUMN area_id uuid,
    ADD COLUMN issue_eligible boolean NOT NULL DEFAULT false,
    ADD COLUMN state varchar(8) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','ARCHIVED')),
    ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    ADD CONSTRAINT inventory_location_tenant_id_uq UNIQUE (tenant_id,id),
    ADD CONSTRAINT inventory_location_parent_fk FOREIGN KEY (tenant_id,parent_location_id) REFERENCES inventory_location(tenant_id,id),
    ADD CONSTRAINT inventory_location_parent_ck CHECK (parent_location_id IS NULL OR parent_location_id <> id);

ALTER TABLE inventory_serialized_asset
    ADD COLUMN warehouse_admission varchar(20) NOT NULL DEFAULT 'LEGACY_UNRESOLVED' CHECK (warehouse_admission IN ('LEGACY_UNRESOLVED','VERIFIED')),
    ADD COLUMN canonical_serial_candidate text,
    ADD COLUMN canonical_mac_candidate varchar(12),
    ADD COLUMN base_unit_candidate varchar(2) CHECK (base_unit_candidate IN ('EA','MM')),
    ADD COLUMN warehouse_sku_id uuid,
    ADD COLUMN canonical_serial text,
    ADD COLUMN canonical_mac varchar(12),
    ADD COLUMN quantity_base bigint CHECK (quantity_base = 1),
    ADD COLUMN base_unit varchar(2) CHECK (base_unit = 'EA'),
    ADD COLUMN condition varchar(12) CHECK (condition IN ('SERVICEABLE','QUARANTINE','DAMAGED','SCRAP')),
    ADD COLUMN legal_owner varchar(8) CHECK (legal_owner IN ('ISP','CUSTOMER','UNKNOWN')),
    ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    ADD CONSTRAINT inventory_asset_tenant_id_uq UNIQUE (tenant_id,id),
    ADD CONSTRAINT inventory_asset_warehouse_sku_fk FOREIGN KEY (tenant_id,warehouse_sku_id,base_unit) REFERENCES inventory_sku(tenant_id,id,base_unit),
    ADD CONSTRAINT inventory_asset_location_tenant_fk FOREIGN KEY (tenant_id,location_id) REFERENCES inventory_location(tenant_id,id) NOT VALID,
    ADD CONSTRAINT inventory_asset_verified_ck CHECK (warehouse_admission <> 'VERIFIED' OR
        (warehouse_sku_id IS NOT NULL AND canonical_serial IS NOT NULL AND canonical_serial <> '' AND
         base_unit IS NOT NULL AND quantity_base IS NOT NULL AND condition IS NOT NULL AND legal_owner IS NOT NULL));

UPDATE inventory_serialized_asset SET canonical_serial_candidate=NULLIF(upper(btrim(serial_number)),''),
    canonical_mac_candidate=warehouse_canonical_mac(mac_address), base_unit_candidate='EA';
ALTER TABLE inventory_serialized_asset ALTER COLUMN warehouse_admission SET DEFAULT 'VERIFIED';

CREATE TABLE inventory_identity_claim (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
    identity_type varchar(6) NOT NULL CHECK (identity_type IN ('SERIAL','MAC')),
    canonical_value text NOT NULL CHECK (canonical_value <> '' AND canonical_value = upper(btrim(canonical_value))),
    state varchar(16) NOT NULL CHECK (state IN ('LEGACY_RESERVED','CONFLICT','ADMITTED','RETIRED')),
    admitted_asset_id uuid,
    CONSTRAINT inventory_identity_claim_key_uq UNIQUE (tenant_id,identity_type,canonical_value),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,admitted_asset_id) REFERENCES inventory_serialized_asset(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    CHECK (identity_type <> 'MAC' OR canonical_value ~ '^[0-9A-F]{12}$'),
    CHECK ((state = 'ADMITTED' AND admitted_asset_id IS NOT NULL) OR
           (state IN ('LEGACY_RESERVED','CONFLICT') AND admitted_asset_id IS NULL) OR state = 'RETIRED')
);

CREATE TABLE inventory_identity_candidate (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, claim_id uuid,
    identity_type varchar(6) NOT NULL CHECK (identity_type IN ('SERIAL','MAC')),
    source_table varchar(64) NOT NULL CHECK (source_table IN ('inventory_serialized_asset','inventory_serial_tombstone','onu')),
    source_id uuid NOT NULL, raw_value text NOT NULL, canonical_value text,
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,source_table,source_id,identity_type),
    FOREIGN KEY (tenant_id,claim_id) REFERENCES inventory_identity_claim(tenant_id,id)
);

INSERT INTO inventory_identity_candidate(id,tenant_id,identity_type,source_table,source_id,raw_value,canonical_value)
SELECT gen_random_uuid(),tenant_id,'SERIAL','inventory_serialized_asset',id,serial_number,canonical_serial_candidate FROM inventory_serialized_asset
UNION ALL SELECT gen_random_uuid(),tenant_id,'MAC','inventory_serialized_asset',id,mac_address,canonical_mac_candidate FROM inventory_serialized_asset WHERE mac_address IS NOT NULL
UNION ALL SELECT gen_random_uuid(),tenant_id,'SERIAL','inventory_serial_tombstone',id,serial_number,NULLIF(upper(btrim(serial_number)),'') FROM inventory_serial_tombstone WHERE serial_number IS NOT NULL
UNION ALL SELECT gen_random_uuid(),tenant_id,'MAC','inventory_serial_tombstone',id,mac_address,warehouse_canonical_mac(mac_address) FROM inventory_serial_tombstone WHERE mac_address IS NOT NULL
UNION ALL SELECT gen_random_uuid(),tenant_id,'SERIAL','onu',id,serial_number,NULLIF(upper(btrim(serial_number)),'') FROM onu;

INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state)
SELECT gen_random_uuid(),tenant_id,identity_type,canonical_value,CASE WHEN count(*) > 1 THEN 'CONFLICT' ELSE 'LEGACY_RESERVED' END
FROM inventory_identity_candidate WHERE canonical_value IS NOT NULL GROUP BY tenant_id,identity_type,canonical_value;
UPDATE inventory_identity_candidate candidate SET claim_id=claim.id FROM inventory_identity_claim claim
WHERE claim.tenant_id=candidate.tenant_id AND claim.identity_type=candidate.identity_type AND claim.canonical_value=candidate.canonical_value;
ALTER TABLE inventory_identity_candidate ADD CHECK ((claim_id IS NULL) = (canonical_value IS NULL));
SET CONSTRAINTS ALL IMMEDIATE;

CREATE TABLE inventory_lot (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL, code varchar(128) NOT NULL,
    base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')),
    received_quantity_base bigint NOT NULL CHECK (received_quantity_base > 0),
    supplier_id uuid, received_at timestamptz NOT NULL,
    cost_total_minor bigint CHECK (cost_total_minor >= 0), cost_basis_quantity_base bigint CHECK (cost_basis_quantity_base > 0), currency varchar(3),
    warehouse_admission varchar(20) NOT NULL DEFAULT 'VERIFIED' CHECK (warehouse_admission IN ('LEGACY_UNRESOLVED','VERIFIED')),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,sku_id,code), UNIQUE (tenant_id,id,sku_id,base_unit),
    FOREIGN KEY (tenant_id,sku_id,base_unit) REFERENCES inventory_sku(tenant_id,id,base_unit),
    FOREIGN KEY (tenant_id,supplier_id) REFERENCES inventory_supplier(tenant_id,id),
    CHECK ((cost_total_minor IS NULL AND cost_basis_quantity_base IS NULL AND currency IS NULL) OR
           (cost_total_minor IS NOT NULL AND cost_basis_quantity_base IS NOT NULL AND currency IS NOT NULL AND currency ~ '^[A-Z]{3}$'))
);

CREATE TABLE inventory_segment (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL, lot_id uuid, asset_id uuid,
    parent_segment_id uuid,
    kind varchar(8) NOT NULL CHECK (kind IN ('SERIAL','REEL','CUT','REMNANT','BULK')),
    base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')),
    quantity_base bigint NOT NULL CHECK (quantity_base > 0),
    state varchar(8) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','SPLIT','RETIRED')),
    warehouse_admission varchar(20) NOT NULL DEFAULT 'VERIFIED' CHECK (warehouse_admission IN ('LEGACY_UNRESOLVED','VERIFIED')),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,id,sku_id,base_unit), UNIQUE (tenant_id,asset_id),
    FOREIGN KEY (tenant_id,sku_id,base_unit) REFERENCES inventory_sku(tenant_id,id,base_unit),
    FOREIGN KEY (tenant_id,lot_id,sku_id,base_unit) REFERENCES inventory_lot(tenant_id,id,sku_id,base_unit),
    FOREIGN KEY (tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,parent_segment_id,sku_id,base_unit) REFERENCES inventory_segment(tenant_id,id,sku_id,base_unit),
    CHECK (parent_segment_id IS NULL OR parent_segment_id <> id),
    CHECK ((kind='SERIAL' AND asset_id=id AND asset_id IS NOT NULL AND base_unit='EA' AND quantity_base=1) OR
           (kind<>'SERIAL' AND asset_id IS NULL AND lot_id IS NOT NULL)),
    CHECK (kind NOT IN ('REEL','CUT','REMNANT') OR base_unit='MM')
);

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_sku','inventory_uom_conversion','inventory_supplier','inventory_tenant_cutover',
        'iam_authorization_epoch','inventory_identity_claim','inventory_identity_candidate','inventory_lot','inventory_segment'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0), ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now()',table_name);
        EXECUTE format('ALTER TABLE %I ADD FOREIGN KEY (tenant_id) REFERENCES tenant(id)',table_name);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_revision BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard()',table_name);
        IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
END $$;

INSERT INTO inventory_tenant_cutover(id,tenant_id) SELECT gen_random_uuid(),id FROM tenant;
INSERT INTO iam_authorization_epoch(id,tenant_id) SELECT gen_random_uuid(),id FROM tenant;

CREATE FUNCTION warehouse_cutover_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' AND NEW.state='ENFORCED' THEN
        IF NEW.initialization_kind<>'NEW_EMPTY' OR NOT EXISTS
            (SELECT FROM tenant WHERE id=NEW.tenant_id AND created_at>warehouse_activation_at()) OR
            EXISTS (SELECT FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id) OR
            EXISTS (SELECT FROM inventory_identity_candidate WHERE tenant_id=NEW.tenant_id) OR
            EXISTS (SELECT FROM inventory_lot WHERE tenant_id=NEW.tenant_id) THEN
            RAISE EXCEPTION 'existing or nonempty tenant requires validated cutover' USING ERRCODE='23514';
        END IF;
    ELSIF TG_OP='INSERT' AND NEW.state<>'LEGACY' THEN
        RAISE EXCEPTION 'cutover must start LEGACY' USING ERRCODE='23514';
    ELSIF TG_OP='UPDATE' THEN
        IF NEW.epoch<>OLD.epoch+1 OR NEW.activation_at<>OLD.activation_at OR NEW.initialization_kind<>OLD.initialization_kind THEN
            RAISE EXCEPTION 'invalid cutover epoch or origin' USING ERRCODE='23514';
        END IF;
        IF (OLD.state,NEW.state) NOT IN (('LEGACY','VALIDATING'),('VALIDATING','ENFORCED')) THEN
            RAISE EXCEPTION 'invalid cutover lifecycle' USING ERRCODE='23514';
        END IF;
        IF NEW.state='ENFORCED' THEN
            RAISE EXCEPTION 'independent migration approval unavailable until approval owner is installed' USING ERRCODE='42501';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_cutover_lifecycle BEFORE INSERT OR UPDATE ON inventory_tenant_cutover FOR EACH ROW EXECUTE FUNCTION warehouse_cutover_guard();
CREATE TRIGGER warehouse_cutover_append_only BEFORE DELETE ON inventory_tenant_cutover FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE INDEX inventory_candidate_claim_idx ON inventory_identity_candidate(tenant_id,claim_id);
CREATE INDEX inventory_segment_parent_idx ON inventory_segment(tenant_id,parent_segment_id);
CREATE INDEX inventory_segment_lot_idx ON inventory_segment(tenant_id,lot_id);
CREATE UNIQUE INDEX inventory_asset_verified_serial_uq ON inventory_serialized_asset(tenant_id,canonical_serial) WHERE warehouse_admission='VERIFIED';
CREATE UNIQUE INDEX inventory_asset_verified_mac_uq ON inventory_serialized_asset(tenant_id,canonical_mac) WHERE warehouse_admission='VERIFIED';

CREATE FUNCTION warehouse_claim_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' AND (NEW.identity_type,NEW.canonical_value) IS DISTINCT FROM (OLD.identity_type,OLD.canonical_value) THEN
        RAISE EXCEPTION 'canonical claim key is immutable' USING ERRCODE='23514';
    END IF;
    IF current_user <> pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid=TG_RELID)) AND
       ((TG_OP='INSERT' AND NEW.state<>'ADMITTED') OR
        (TG_OP='UPDATE' AND (OLD.state IN ('LEGACY_RESERVED','CONFLICT','RETIRED') OR NEW.admitted_asset_id IS DISTINCT FROM OLD.admitted_asset_id))) THEN
        RAISE EXCEPTION 'reserved identity requires approved reconciliation' USING ERRCODE='42501';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_asset_claim_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.warehouse_admission='VERIFIED' THEN
        IF NEW.canonical_serial IS DISTINCT FROM NULLIF(upper(btrim(NEW.serial_number)),'') OR
           NEW.canonical_mac IS DISTINCT FROM warehouse_canonical_mac(NEW.mac_address) THEN
            RAISE EXCEPTION 'canonical identity does not match raw identity' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS (SELECT FROM inventory_identity_claim WHERE tenant_id=NEW.tenant_id AND identity_type='SERIAL'
            AND canonical_value=NEW.canonical_serial AND state IN ('ADMITTED','RETIRED') AND admitted_asset_id=NEW.id) OR
           (NEW.mac_address IS NOT NULL AND (NEW.canonical_mac IS NULL OR NOT EXISTS (SELECT FROM inventory_identity_claim
            WHERE tenant_id=NEW.tenant_id AND identity_type='MAC' AND canonical_value=NEW.canonical_mac AND state IN ('ADMITTED','RETIRED') AND admitted_asset_id=NEW.id))) THEN
            RAISE EXCEPTION 'identity is not admitted to this asset' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER warehouse_claim BEFORE INSERT OR UPDATE ON inventory_identity_claim FOR EACH ROW EXECUTE FUNCTION warehouse_claim_guard();
CREATE TRIGGER warehouse_claim_append_only BEFORE DELETE ON inventory_identity_claim FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE TRIGGER warehouse_candidate_append_only BEFORE UPDATE OR DELETE ON inventory_identity_candidate FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE CONSTRAINT TRIGGER warehouse_asset_claim AFTER INSERT OR UPDATE ON inventory_serialized_asset DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_asset_claim_guard();
CREATE TRIGGER warehouse_asset_admission BEFORE INSERT OR UPDATE ON inventory_serialized_asset FOR EACH ROW EXECUTE FUNCTION warehouse_admission_guard();
CREATE TRIGGER warehouse_lot_admission BEFORE INSERT OR UPDATE ON inventory_lot FOR EACH ROW EXECUTE FUNCTION warehouse_admission_guard();
CREATE TRIGGER warehouse_segment_admission BEFORE INSERT OR UPDATE ON inventory_segment FOR EACH ROW EXECUTE FUNCTION warehouse_admission_guard();
CREATE TRIGGER warehouse_location_revision BEFORE UPDATE ON inventory_location FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard();
CREATE TRIGGER warehouse_asset_revision BEFORE UPDATE ON inventory_serialized_asset FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard();

CREATE FUNCTION warehouse_asset_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.warehouse_admission='VERIFIED' AND
       (NEW.serial_number,NEW.mac_address,NEW.canonical_serial,NEW.canonical_mac,NEW.warehouse_sku_id,NEW.base_unit,NEW.quantity_base)
       IS DISTINCT FROM (OLD.serial_number,OLD.mac_address,OLD.canonical_serial,OLD.canonical_mac,OLD.warehouse_sku_id,OLD.base_unit,OLD.quantity_base) THEN
        RAISE EXCEPTION 'admitted asset identity and units are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_asset_identity BEFORE UPDATE ON inventory_serialized_asset FOR EACH ROW EXECUTE FUNCTION warehouse_asset_identity_guard();
CREATE TRIGGER warehouse_asset_append_only BEFORE DELETE ON inventory_serialized_asset FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE FUNCTION warehouse_segment_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='UPDATE' THEN
        IF (NEW.sku_id,NEW.lot_id,NEW.asset_id,NEW.parent_segment_id,NEW.kind,NEW.base_unit,NEW.quantity_base) IS DISTINCT FROM
           (OLD.sku_id,OLD.lot_id,OLD.asset_id,OLD.parent_segment_id,OLD.kind,OLD.base_unit,OLD.quantity_base) OR
           (NEW.state<>OLD.state AND (OLD.state<>'ACTIVE' OR NEW.state NOT IN ('SPLIT','RETIRED'))) THEN
            RAISE EXCEPTION 'segment identity and quantity are immutable' USING ERRCODE='23514';
        END IF;
    END IF;
    IF NEW.parent_segment_id IS NOT NULL THEN
        PERFORM id FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=NEW.parent_segment_id FOR UPDATE;
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_segment_conservation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_id uuid; parent_quantity bigint; parent_state text; child_quantity numeric;
BEGIN
    FOREACH target_id IN ARRAY ARRAY[NEW.id,NEW.parent_segment_id] LOOP
        IF target_id IS NULL THEN CONTINUE; END IF;
        SELECT quantity_base,state INTO parent_quantity,parent_state FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=target_id;
        SELECT sum(quantity_base::numeric) INTO child_quantity FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND parent_segment_id=target_id;
        IF (parent_state='SPLIT' AND child_quantity IS DISTINCT FROM parent_quantity::numeric) OR
           (parent_state<>'SPLIT' AND child_quantity IS NOT NULL) THEN
            RAISE EXCEPTION 'segment split violates conservation' USING ERRCODE='23514';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_segment_identity BEFORE INSERT OR UPDATE ON inventory_segment FOR EACH ROW EXECUTE FUNCTION warehouse_segment_guard();
CREATE CONSTRAINT TRIGGER warehouse_segment_conservation AFTER INSERT OR UPDATE ON inventory_segment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_segment_conservation();
CREATE TRIGGER warehouse_segment_append_only BEFORE DELETE ON inventory_segment FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
