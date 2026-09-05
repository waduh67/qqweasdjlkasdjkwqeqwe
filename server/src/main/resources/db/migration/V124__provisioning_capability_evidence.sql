ALTER TABLE provisioning_collector_device_report
    ADD COLUMN operation_classes text NOT NULL DEFAULT '',
    ADD COLUMN expires_at timestamptz;

UPDATE provisioning_collector_device_report
SET expires_at = reported_at
WHERE expires_at IS NULL;

ALTER TABLE provisioning_collector_device_report
    ALTER COLUMN expires_at SET NOT NULL,
    ADD CONSTRAINT uq_provisioning_collector_report_id_tenant UNIQUE (id, tenant_id),
    ADD CONSTRAINT ck_provisioning_collector_report_validity CHECK (expires_at >= reported_at);

CREATE TABLE provisioning_capability_evidence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    collector_id uuid NOT NULL,
    report_id uuid NOT NULL,
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    vendor varchar(120) NOT NULL,
    model varchar(120) NOT NULL,
    firmware varchar(120) NOT NULL,
    transport varchar(120) NOT NULL,
    operation_class varchar(120) NOT NULL,
    supported boolean NOT NULL,
    observed_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_capability_report FOREIGN KEY (report_id, tenant_id)
        REFERENCES provisioning_collector_device_report (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_provisioning_capability_collector FOREIGN KEY (collector_id, tenant_id)
        REFERENCES collector (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT ck_provisioning_capability_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_capability_validity CHECK (expires_at > observed_at),
    CONSTRAINT uq_provisioning_capability_report_operation UNIQUE (tenant_id, report_id, operation_class)
);

CREATE INDEX ix_provisioning_capability_exact
    ON provisioning_capability_evidence
        (tenant_id, device_kind, device_id, operation_class, observed_at DESC);

ALTER TABLE provisioning_capability_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE provisioning_capability_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provisioning_capability_evidence
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
