ALTER TABLE collector
    ADD CONSTRAINT uq_collector_id_tenant UNIQUE (id, tenant_id);

ALTER TABLE provisioning_step_attempt
    ADD COLUMN collector_id uuid;

ALTER TABLE provisioning_step_attempt
    ADD CONSTRAINT uq_provisioning_attempt_id_tenant UNIQUE (id, tenant_id);

ALTER TABLE provisioning_step_attempt
    ADD CONSTRAINT fk_provisioning_attempt_collector FOREIGN KEY (collector_id, tenant_id)
        REFERENCES collector (id, tenant_id) ON DELETE SET NULL (collector_id);

CREATE INDEX ix_provisioning_attempt_collector
    ON provisioning_step_attempt (tenant_id, collector_id, status, deadline);

CREATE TABLE provisioning_collector_device_report (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    collector_id uuid NOT NULL,
    report_key varchar(300) NOT NULL,
    target_id varchar(120) NOT NULL,
    vendor varchar(120) NOT NULL,
    model varchar(120) NOT NULL,
    firmware varchar(120) NOT NULL,
    transport varchar(120) NOT NULL,
    capabilities text NOT NULL,
    reported_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_collector_report_collector FOREIGN KEY (collector_id, tenant_id)
        REFERENCES collector (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_provisioning_collector_report UNIQUE (tenant_id, collector_id, report_key)
);

CREATE INDEX ix_provisioning_collector_report_target
    ON provisioning_collector_device_report (tenant_id, target_id, reported_at);

ALTER TABLE provisioning_collector_device_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE provisioning_collector_device_report FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provisioning_collector_device_report
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE provisioning_collector_result_receipt (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    collector_id uuid NOT NULL,
    idempotency_key varchar(200) NOT NULL,
    plan_id varchar(120) NOT NULL,
    revision integer NOT NULL,
    step_id varchar(120) NOT NULL,
    attempt_id uuid NOT NULL,
    target_id varchar(120) NOT NULL,
    operation_class varchar(120) NOT NULL,
    fencing_epoch bigint NOT NULL,
    phase varchar(20) NOT NULL,
    success boolean NOT NULL,
    completed_at timestamptz NOT NULL,
    error_code varchar(80),
    preflight_hash varchar(64),
    apply_changed boolean,
    apply_state_hash varchar(64),
    verification_matches boolean,
    verification_state_hash varchar(64),
    managed_resource_count integer,
    rollback_success boolean,
    rollback_state_hash varchar(64),
    rollback_error_code varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_collector_result_attempt FOREIGN KEY (attempt_id, tenant_id)
        REFERENCES provisioning_step_attempt (id, tenant_id),
    CONSTRAINT fk_provisioning_collector_result_collector FOREIGN KEY (collector_id, tenant_id)
        REFERENCES collector (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_provisioning_collector_result UNIQUE (tenant_id, attempt_id),
    CONSTRAINT ck_provisioning_collector_result_phase CHECK (phase IN ('PREFLIGHT', 'APPLY', 'VERIFY', 'ROLLBACK')),
    CONSTRAINT ck_provisioning_collector_result_hashes CHECK (
        (preflight_hash IS NULL OR preflight_hash ~ '^[a-f0-9]{64}$')
        AND (apply_state_hash IS NULL OR apply_state_hash ~ '^[a-f0-9]{64}$')
        AND (verification_state_hash IS NULL OR verification_state_hash ~ '^[a-f0-9]{64}$')
        AND (rollback_state_hash IS NULL OR rollback_state_hash ~ '^[a-f0-9]{64}$')
    ),
    CONSTRAINT ck_provisioning_collector_result_resource_count CHECK (managed_resource_count IS NULL OR managed_resource_count >= 0)
);

CREATE INDEX ix_provisioning_collector_result_attempt
    ON provisioning_collector_result_receipt (tenant_id, idempotency_key);

ALTER TABLE provisioning_collector_result_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE provisioning_collector_result_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provisioning_collector_result_receipt
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
