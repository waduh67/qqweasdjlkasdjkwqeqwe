CREATE TABLE workorder_fulfillment_result (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    work_order_id uuid NOT NULL,
    namespace varchar(120) NOT NULL,
    operation_key varchar(240) NOT NULL,
    payload_hash varchar(128) NOT NULL,
    source varchar(40) NOT NULL,
    result varchar(500) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, namespace, operation_key)
);
CREATE INDEX workorder_fulfillment_result_target_idx ON workorder_fulfillment_result (tenant_id, work_order_id);

CREATE TABLE inventory_fulfillment_effect (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    target_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    namespace varchar(120) NOT NULL,
    operation_key varchar(240) NOT NULL,
    payload_hash varchar(128) NOT NULL,
    item_category varchar(160) NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    installed boolean NOT NULL,
    returned boolean NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, namespace, operation_key)
);
CREATE INDEX inventory_fulfillment_effect_target_idx ON inventory_fulfillment_effect (tenant_id, target_id);

CREATE TABLE fieldservice_visit (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    order_id uuid NOT NULL,
    work_order_id uuid NOT NULL,
    technician_id uuid NOT NULL,
    state varchar(32) NOT NULL,
    revision bigint NOT NULL CHECK (revision >= 0),
    assignment_active boolean NOT NULL,
    attendance_decision varchar(32),
    attendance_reason varchar(500),
    attendance_received_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX fieldservice_visit_tenant_workorder_idx ON fieldservice_visit (tenant_id, work_order_id);

CREATE TABLE fieldservice_work_session (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    visit_id uuid NOT NULL REFERENCES fieldservice_visit(id),
    work_order_id uuid NOT NULL,
    technician_id uuid NOT NULL,
    started_at timestamptz,
    ended_at timestamptz,
    submitted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, visit_id)
);
CREATE INDEX fieldservice_session_tenant_workorder_idx ON fieldservice_work_session (tenant_id, work_order_id);

CREATE TABLE fieldservice_visit_operation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    visit_id uuid NOT NULL,
    namespace varchar(120) NOT NULL,
    operation_key varchar(240) NOT NULL,
    payload_hash varchar(128) NOT NULL,
    result varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, namespace, operation_key)
);
CREATE INDEX fieldservice_visit_operation_target_idx ON fieldservice_visit_operation (tenant_id, visit_id);

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['workorder_fulfillment_result','inventory_fulfillment_effect','fieldservice_visit','fieldservice_work_session','fieldservice_visit_operation'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)', table_name);
    END LOOP;
END $$;
