ALTER TABLE provisioning_execution ADD COLUMN intent_id uuid;

UPDATE provisioning_execution execution
SET intent_id = plan.intent_id
FROM provisioning_plan plan
WHERE plan.id = execution.plan_id AND plan.tenant_id = execution.tenant_id;

CREATE OR REPLACE FUNCTION provisioning_execution_resolve_intent() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.intent_id IS NULL THEN
        SELECT intent_id INTO NEW.intent_id
        FROM provisioning_plan
        WHERE id = NEW.plan_id AND tenant_id = NEW.tenant_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_provisioning_execution_resolve_intent
BEFORE INSERT ON provisioning_execution
FOR EACH ROW EXECUTE FUNCTION provisioning_execution_resolve_intent();

ALTER TABLE provisioning_execution ADD CONSTRAINT fk_provisioning_execution_intent
    FOREIGN KEY (intent_id, tenant_id) REFERENCES provisioning_service_intent (id, tenant_id);

CREATE UNIQUE INDEX uq_provisioning_active_execution_intent
    ON provisioning_execution (tenant_id, intent_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'VERIFYING', 'ROLLING_BACK');

ALTER TABLE provisioning_step DROP CONSTRAINT ck_provisioning_step_operation;
ALTER TABLE provisioning_step ADD CONSTRAINT ck_provisioning_step_operation CHECK (
    operation IN (
        'ENSURE_TAGGED_VLAN', 'ENSURE_ACCESS_PORT', 'ENSURE_PPPOE_TERMINATION', 'VERIFY_STATE',
        'BLOCK_PPPOE_SESSIONS', 'REMOVE_ACCESS_PORT', 'REMOVE_TAGGED_VLAN', 'REMOVE_PPPOE_TERMINATION'
    )
);

CREATE TABLE provisioning_device_lease (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    execution_id uuid NOT NULL,
    owner_id varchar(120) NOT NULL,
    fencing_token bigint NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_lease_execution FOREIGN KEY (execution_id, tenant_id)
        REFERENCES provisioning_execution (id, tenant_id),
    CONSTRAINT uq_provisioning_device_lease UNIQUE (tenant_id, device_kind, device_id),
    CONSTRAINT ck_provisioning_lease_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_lease_fencing CHECK (fencing_token > 0)
);

CREATE TABLE provisioning_execution_step (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    execution_id uuid NOT NULL,
    plan_step_id uuid NOT NULL,
    step_order integer NOT NULL,
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    status varchar(30) NOT NULL,
    before_hash char(64),
    after_hash char(64),
    last_error varchar(120),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_execution_step_execution FOREIGN KEY (execution_id, tenant_id)
        REFERENCES provisioning_execution (id, tenant_id),
    CONSTRAINT fk_provisioning_execution_step_plan_step FOREIGN KEY (plan_step_id, tenant_id)
        REFERENCES provisioning_step (id, tenant_id),
    CONSTRAINT uq_provisioning_execution_step UNIQUE (tenant_id, execution_id, plan_step_id),
    CONSTRAINT uq_provisioning_execution_step_order UNIQUE (tenant_id, execution_id, step_order),
    CONSTRAINT uq_provisioning_execution_step_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_execution_step_order CHECK (step_order > 0),
    CONSTRAINT ck_provisioning_execution_step_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_execution_step_status CHECK (status IN (
        'PENDING', 'PREFLIGHTED', 'APPLY_DISPATCHED', 'APPLIED', 'VERIFIED', 'COMPENSATING', 'COMPENSATED', 'FAILED'
    )),
    CONSTRAINT ck_provisioning_execution_step_before_hash CHECK (before_hash IS NULL OR before_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_provisioning_execution_step_after_hash CHECK (after_hash IS NULL OR after_hash ~ '^[a-f0-9]{64}$')
);

CREATE TABLE provisioning_step_attempt (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    execution_step_id uuid NOT NULL,
    phase varchar(20) NOT NULL,
    attempt_number integer NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    fencing_token bigint NOT NULL,
    deadline timestamptz NOT NULL,
    status varchar(30) NOT NULL,
    error_code varchar(80),
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_attempt_step FOREIGN KEY (execution_step_id, tenant_id)
        REFERENCES provisioning_execution_step (id, tenant_id),
    CONSTRAINT uq_provisioning_attempt UNIQUE (tenant_id, execution_step_id, phase, attempt_number),
    CONSTRAINT ck_provisioning_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_provisioning_attempt_fencing CHECK (fencing_token > 0),
    CONSTRAINT ck_provisioning_attempt_phase CHECK (phase IN ('PREFLIGHT', 'APPLY', 'VERIFY', 'COMPENSATE')),
    CONSTRAINT ck_provisioning_attempt_status CHECK (status IN (
        'DISPATCHED', 'SUCCEEDED', 'TRANSIENT_FAILURE', 'PERMANENT_FAILURE'
    )),
    CONSTRAINT ck_provisioning_attempt_result CHECK (
        (status = 'DISPATCHED' AND completed_at IS NULL AND error_code IS NULL)
        OR (status = 'SUCCEEDED' AND completed_at IS NOT NULL AND error_code IS NULL)
        OR (status IN ('TRANSIENT_FAILURE', 'PERMANENT_FAILURE') AND completed_at IS NOT NULL AND error_code IS NOT NULL)
    )
);

CREATE TABLE provisioning_step_snapshot (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    execution_step_id uuid NOT NULL,
    snapshot_kind varchar(30) NOT NULL,
    state_hash char(64) NOT NULL,
    normalized_state jsonb NOT NULL,
    captured_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_step_snapshot_step FOREIGN KEY (execution_step_id, tenant_id)
        REFERENCES provisioning_execution_step (id, tenant_id),
    CONSTRAINT ck_provisioning_step_snapshot_kind CHECK (
        snapshot_kind IN ('BEFORE', 'AFTER', 'ROLLBACK_CHECK', 'ROLLBACK_RESULT')
    ),
    CONSTRAINT ck_provisioning_step_snapshot_hash CHECK (state_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_provisioning_step_snapshot_object CHECK (jsonb_typeof(normalized_state) = 'object'),
    CONSTRAINT ck_provisioning_step_snapshot_no_secrets CHECK (NOT provisioning_json_has_sensitive_key(normalized_state))
);

CREATE TABLE provisioning_device_circuit (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    failure_count integer NOT NULL DEFAULT 0,
    open_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_provisioning_device_circuit UNIQUE (tenant_id, device_kind, device_id),
    CONSTRAINT ck_provisioning_circuit_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_circuit_failure_count CHECK (failure_count >= 0)
);

CREATE INDEX ix_provisioning_execution_step_execution
    ON provisioning_execution_step (tenant_id, execution_id, step_order);
CREATE INDEX ix_provisioning_attempt_pending
    ON provisioning_step_attempt (tenant_id, status, deadline);
CREATE INDEX ix_provisioning_snapshot_step
    ON provisioning_step_snapshot (tenant_id, execution_step_id, captured_at);

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'provisioning_device_lease', 'provisioning_execution_step', 'provisioning_step_attempt',
        'provisioning_step_snapshot', 'provisioning_device_circuit'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    END LOOP;
END $$;
