ALTER TABLE provisioning_drift_record
    ADD CONSTRAINT uq_provisioning_drift_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE provisioning_adoption_baseline (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    drift_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    observation_id uuid NOT NULL,
    adopted_by uuid NOT NULL,
    adopted_at timestamptz NOT NULL,
    CONSTRAINT fk_provisioning_adoption_drift FOREIGN KEY (drift_id, tenant_id)
        REFERENCES provisioning_drift_record (id, tenant_id),
    CONSTRAINT fk_provisioning_adoption_snapshot FOREIGN KEY (snapshot_id, tenant_id)
        REFERENCES provisioning_device_snapshot (id, tenant_id),
    CONSTRAINT fk_provisioning_adoption_observation FOREIGN KEY (observation_id, tenant_id)
        REFERENCES provisioning_device_observation (id, tenant_id),
    CONSTRAINT uq_provisioning_adoption_drift UNIQUE (tenant_id, drift_id),
    CONSTRAINT ck_provisioning_adoption_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS'))
);

ALTER TABLE provisioning_adoption_baseline ENABLE ROW LEVEL SECURITY;
ALTER TABLE provisioning_adoption_baseline FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provisioning_adoption_baseline
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
