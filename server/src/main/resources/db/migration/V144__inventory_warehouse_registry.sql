CREATE TABLE inventory_location (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    code varchar(64) NOT NULL,
    kind varchar(24) NOT NULL,
    CONSTRAINT inventory_location_tenant_code_uq UNIQUE (tenant_id, code)
);

CREATE TABLE inventory_serial_tombstone (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    serial_number varchar(128),
    mac_address varchar(17),
    retired_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT inventory_serial_tombstone_identity_ck CHECK (serial_number IS NOT NULL OR mac_address IS NOT NULL),
    CONSTRAINT inventory_serial_tombstone_serial_uq UNIQUE (tenant_id, serial_number),
    CONSTRAINT inventory_serial_tombstone_mac_uq UNIQUE (tenant_id, mac_address)
);

CREATE TABLE inventory_serialized_asset (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    sku_id uuid NOT NULL,
    serial_number varchar(128) NOT NULL,
    mac_address varchar(17),
    status varchar(24) NOT NULL,
    location_id uuid NOT NULL REFERENCES inventory_location(id),
    custody_owner_id uuid NOT NULL,
    custody_owner_kind varchar(24) NOT NULL,
    installed_onu_id uuid,
    last_operation_key varchar(128),
    CONSTRAINT inventory_asset_serial_uq UNIQUE (tenant_id, serial_number),
    CONSTRAINT inventory_asset_mac_uq UNIQUE (tenant_id, mac_address),
    CONSTRAINT inventory_asset_operation_uq UNIQUE (tenant_id, last_operation_key),
    CONSTRAINT inventory_asset_status_ck CHECK (status IN ('AVAILABLE','RESERVED','ISSUED','IN_TRANSIT','CONSUMED','RETURNED','QUARANTINE','LOST','DISPOSED'))
);

CREATE INDEX inventory_asset_tenant_location_idx ON inventory_serialized_asset (tenant_id, location_id);
CREATE INDEX inventory_asset_tenant_status_idx ON inventory_serialized_asset (tenant_id, status);

ALTER TABLE inventory_location ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_location FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_serial_tombstone ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_serial_tombstone FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_serialized_asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_serialized_asset FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_location_tenant_policy ON inventory_location USING (tenant_id = current_setting('app.tenant_id', true)::uuid) WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY inventory_tombstone_tenant_policy ON inventory_serial_tombstone USING (tenant_id = current_setting('app.tenant_id', true)::uuid) WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE POLICY inventory_asset_tenant_policy ON inventory_serialized_asset USING (tenant_id = current_setting('app.tenant_id', true)::uuid) WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
