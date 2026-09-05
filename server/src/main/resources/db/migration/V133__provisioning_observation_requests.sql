CREATE TABLE provisioning_observation_request (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    baseline_snapshot_id uuid NOT NULL,
    plan_id uuid NOT NULL,
    plan_revision integer NOT NULL,
    step_id uuid NOT NULL,
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    operation_class varchar(120) NOT NULL,
    baseline_hash varchar(64) NOT NULL,
    deadline timestamptz NOT NULL,
    status varchar(20) NOT NULL,
    collector_id uuid,
    state_hash varchar(64),
    normalized_state jsonb,
    error_code varchar(80),
    observed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_observation_baseline FOREIGN KEY (baseline_snapshot_id, tenant_id)
        REFERENCES provisioning_device_snapshot (id, tenant_id),
    CONSTRAINT fk_provisioning_observation_plan FOREIGN KEY (plan_id, tenant_id)
        REFERENCES provisioning_plan (id, tenant_id),
    CONSTRAINT fk_provisioning_observation_step FOREIGN KEY (step_id, tenant_id)
        REFERENCES provisioning_step (id, tenant_id),
    CONSTRAINT fk_provisioning_observation_collector FOREIGN KEY (collector_id, tenant_id)
        REFERENCES collector (id, tenant_id),
    CONSTRAINT uq_provisioning_observation_request_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_observation_request_revision CHECK (plan_revision > 0),
    CONSTRAINT ck_provisioning_observation_request_device CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_observation_request_hash CHECK (baseline_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_provisioning_observation_request_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'CONSUMED')),
    CONSTRAINT ck_provisioning_observation_request_result CHECK (
        (status = 'PENDING' AND state_hash IS NULL AND normalized_state IS NULL AND error_code IS NULL AND observed_at IS NULL)
        OR (status = 'SUCCEEDED' AND state_hash ~ '^[a-f0-9]{64}$' AND normalized_state IS NOT NULL AND error_code IS NULL AND observed_at IS NOT NULL)
        OR (status = 'FAILED' AND state_hash IS NULL AND normalized_state IS NULL AND error_code IS NOT NULL)
        OR status = 'CONSUMED'
    ),
    CONSTRAINT ck_provisioning_observation_request_state CHECK (
        normalized_state IS NULL OR (jsonb_typeof(normalized_state) = 'object' AND NOT provisioning_json_has_sensitive_key(normalized_state))
    )
);

CREATE UNIQUE INDEX uq_provisioning_observation_pending
    ON provisioning_observation_request (tenant_id, baseline_snapshot_id) WHERE status = 'PENDING';
CREATE INDEX ix_provisioning_observation_dispatch
    ON provisioning_observation_request (tenant_id, status, deadline);

ALTER TABLE provisioning_observation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE provisioning_observation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provisioning_observation_request
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
