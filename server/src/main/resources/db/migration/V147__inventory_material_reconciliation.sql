CREATE TABLE inventory_cycle_count (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    location_id uuid NOT NULL,
    item_id uuid NOT NULL,
    sku_id uuid NOT NULL,
    prior_quantity integer NOT NULL CHECK (prior_quantity >= 0),
    observed_quantity integer NOT NULL CHECK (observed_quantity >= 0),
    reason varchar(500) NOT NULL,
    evidence_reference varchar(500) NOT NULL,
    custodian_id uuid NOT NULL,
    approver_id uuid,
    operation_key varchar(240) NOT NULL,
    operation_hash varchar(128) NOT NULL,
    discrepancy_state varchar(24) NOT NULL,
    created_at timestamptz NOT NULL,
    closed_at timestamptz,
    CONSTRAINT inventory_cycle_count_approval_ck CHECK (approver_id IS NULL OR approver_id <> custodian_id),
    CONSTRAINT inventory_cycle_count_operation_uq UNIQUE (tenant_id, operation_key)
);
CREATE INDEX inventory_cycle_count_tenant_idx ON inventory_cycle_count (tenant_id, created_at, id);
ALTER TABLE inventory_cycle_count ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_cycle_count FORCE ROW LEVEL SECURITY;
CREATE POLICY inventory_cycle_count_tenant_policy ON inventory_cycle_count
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE inventory_customer_material_fact (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    item_category varchar(120) NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    installed boolean NOT NULL,
    returned boolean NOT NULL,
    recorded_at timestamptz NOT NULL,
    operation_key varchar(240) NOT NULL,
    payload_hash varchar(128) NOT NULL,
    CONSTRAINT inventory_customer_material_operation_uq UNIQUE (tenant_id, operation_key)
);
ALTER TABLE inventory_customer_material_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_customer_material_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY inventory_customer_material_tenant_policy ON inventory_customer_material_fact
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
