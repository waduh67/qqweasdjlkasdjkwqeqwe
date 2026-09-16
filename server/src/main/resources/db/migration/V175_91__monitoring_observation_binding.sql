CREATE TABLE monitoring_unassigned_observation (
    id uuid NOT NULL, tenant_id uuid NOT NULL REFERENCES tenant(id), serial_number text NOT NULL,
    observed_at timestamptz, received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    reason text NOT NULL, payload jsonb NOT NULL,
    PRIMARY KEY(tenant_id,id)
);
CREATE INDEX monitoring_unassigned_serial_time ON monitoring_unassigned_observation(tenant_id,serial_number,received_at);
ALTER TABLE monitoring_unassigned_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE monitoring_unassigned_observation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON monitoring_unassigned_observation
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER monitoring_observation_immutable BEFORE UPDATE OR DELETE ON monitoring_unassigned_observation
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
