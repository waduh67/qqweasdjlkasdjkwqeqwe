CREATE TABLE inventory_movement (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    operation_namespace varchar(120) NOT NULL,
    operation_key varchar(240) NOT NULL,
    payload_hash varchar(128) NOT NULL,
    actor_id uuid NOT NULL,
    reason varchar(500) NOT NULL,
    server_received_at timestamptz NOT NULL,
    kind varchar(32) NOT NULL,
    state varchar(32) NOT NULL,
    compensates_movement_id uuid,
    CONSTRAINT inventory_movement_operation_uq UNIQUE (tenant_id, operation_namespace, operation_key),
    CONSTRAINT inventory_movement_state_ck CHECK (state IN ('APPLIED','PENDING_APPROVAL','FAILED_PERMANENT','REQUIRES_MANUAL_REPAIR'))
);

CREATE TABLE inventory_movement_leg (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    movement_id uuid NOT NULL REFERENCES inventory_movement(id),
    direction varchar(3) NOT NULL CHECK (direction IN ('IN','OUT')),
    item_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    location_id uuid NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    serialized boolean NOT NULL,
    custody_owner_id uuid NOT NULL,
    custody_owner_kind varchar(24) NOT NULL,
    status varchar(24) NOT NULL,
    CONSTRAINT inventory_leg_serial_quantity_ck CHECK (NOT serialized OR quantity = 1)
);

CREATE TABLE inventory_balance_projection (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    item_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    location_id uuid NOT NULL,
    custody_owner_id uuid NOT NULL,
    custody_owner_kind varchar(24) NOT NULL,
    status varchar(24) NOT NULL,
    quantity integer NOT NULL CHECK (quantity >= 0),
    rebuilt_at timestamptz NOT NULL,
    CONSTRAINT inventory_balance_dimension_uq UNIQUE (tenant_id, item_id, location_id, custody_owner_id, custody_owner_kind, status)
);

CREATE INDEX inventory_movement_tenant_received_idx ON inventory_movement (tenant_id, server_received_at, id);
CREATE INDEX inventory_leg_tenant_item_idx ON inventory_movement_leg (tenant_id, item_id, location_id);
CREATE INDEX inventory_balance_tenant_sku_idx ON inventory_balance_projection (tenant_id, sku_id, location_id);

ALTER TABLE inventory_movement ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_movement FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_movement_leg ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_movement_leg FORCE ROW LEVEL SECURITY;
ALTER TABLE inventory_balance_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_balance_projection FORCE ROW LEVEL SECURITY;

CREATE POLICY inventory_movement_tenant_policy ON inventory_movement USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY inventory_leg_tenant_policy ON inventory_movement_leg USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY inventory_balance_tenant_policy ON inventory_balance_projection USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
