CREATE TABLE provisioning_execution (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    plan_id uuid NOT NULL,
    idempotency_key varchar(160) NOT NULL,
    status varchar(30) NOT NULL,
    detail varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_execution_plan FOREIGN KEY (plan_id, tenant_id)
        REFERENCES provisioning_plan (id, tenant_id),
    CONSTRAINT uq_provisioning_execution_key UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT uq_provisioning_execution_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_execution_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'VERIFYING', 'SUCCEEDED', 'ROLLING_BACK', 'ROLLED_BACK',
        'FAILED', 'MANUAL_RECONCILIATION'
    ))
);

CREATE TABLE provisioning_device_snapshot (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    normalized_state jsonb NOT NULL,
    captured_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_snapshot_plan FOREIGN KEY (plan_id, tenant_id)
        REFERENCES provisioning_plan (id, tenant_id),
    CONSTRAINT uq_provisioning_snapshot_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_snapshot_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_snapshot_object CHECK (jsonb_typeof(normalized_state) = 'object')
);

CREATE TABLE provisioning_device_observation (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    normalized_state jsonb NOT NULL,
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_provisioning_observation_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_observation_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_observation_object CHECK (jsonb_typeof(normalized_state) = 'object')
);

CREATE OR REPLACE FUNCTION provisioning_json_has_sensitive_key(value jsonb) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE item record;
BEGIN
    IF jsonb_typeof(value) = 'object' THEN
        FOR item IN SELECT key, val FROM jsonb_each(value) AS entry(key, val) LOOP
            IF lower(regexp_replace(item.key, '[^a-zA-Z0-9]', '', 'g')) ~ '(password|secret|credential|token|rawcli|command|script)'
                OR provisioning_json_has_sensitive_key(item.val) THEN
                RETURN true;
            END IF;
        END LOOP;
    ELSIF jsonb_typeof(value) = 'array' THEN
        FOR item IN SELECT val FROM jsonb_array_elements(value) AS entry(val) LOOP
            IF provisioning_json_has_sensitive_key(item.val) THEN RETURN true; END IF;
        END LOOP;
    END IF;
    RETURN false;
END;
$$;

ALTER TABLE provisioning_device_snapshot ADD CONSTRAINT ck_provisioning_snapshot_no_secrets
    CHECK (NOT provisioning_json_has_sensitive_key(normalized_state));
ALTER TABLE provisioning_device_observation ADD CONSTRAINT ck_provisioning_observation_no_secrets
    CHECK (NOT provisioning_json_has_sensitive_key(normalized_state));

CREATE TABLE provisioning_drift_record (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    observation_id uuid NOT NULL,
    status varchar(20) NOT NULL,
    recorded_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_drift_snapshot FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES provisioning_device_snapshot (id, tenant_id),
    CONSTRAINT fk_provisioning_drift_observation FOREIGN KEY (observation_id, tenant_id)
        REFERENCES provisioning_device_observation (id, tenant_id),
    CONSTRAINT ck_provisioning_drift_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_drift_status CHECK (status IN ('NONE', 'BENIGN', 'CONFLICTING', 'UNKNOWN'))
);

CREATE TABLE provisioning_adapter_certification (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    model varchar(120) NOT NULL,
    firmware varchar(120) NOT NULL,
    transport varchar(120) NOT NULL,
    operation_class varchar(120) NOT NULL,
    certified_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_provisioning_certification_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS'))
);

CREATE UNIQUE INDEX uq_provisioning_active_certification
    ON provisioning_adapter_certification (
        tenant_id, device_kind, device_id, model, firmware, transport, operation_class
    ) WHERE revoked_at IS NULL;

CREATE INDEX ix_provisioning_execution_plan ON provisioning_execution (tenant_id, plan_id);
CREATE INDEX ix_provisioning_snapshot_device ON provisioning_device_snapshot (tenant_id, device_kind, device_id);
CREATE INDEX ix_provisioning_observation_device ON provisioning_device_observation (tenant_id, device_kind, device_id);
CREATE INDEX ix_provisioning_drift_device ON provisioning_drift_record (tenant_id, device_kind, device_id, recorded_at);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'provisioning_execution', 'provisioning_device_snapshot', 'provisioning_device_observation',
        'provisioning_drift_record', 'provisioning_adapter_certification'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    END LOOP;
END $$;
