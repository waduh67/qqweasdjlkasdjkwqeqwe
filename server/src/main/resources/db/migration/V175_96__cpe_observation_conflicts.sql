CREATE TABLE cpe_observation_conflict (
    tenant_id uuid NOT NULL, device_id uuid NOT NULL, reason text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,device_id),
    FOREIGN KEY(tenant_id,device_id) REFERENCES cpe_device(tenant_id,id)
);
ALTER TABLE cpe_observation_conflict ENABLE ROW LEVEL SECURITY;
ALTER TABLE cpe_observation_conflict FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cpe_observation_conflict
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
INSERT INTO cpe_observation_conflict(tenant_id,device_id,reason)
SELECT device.tenant_id,device.id,'AMBIGUOUS_ACS_ID' FROM cpe_device device
JOIN (SELECT genieacs_id FROM cpe_device GROUP BY genieacs_id HAVING count(DISTINCT tenant_id)>1) collision
    ON collision.genieacs_id=device.genieacs_id;
CREATE TRIGGER cpe_observation_conflict_immutable BEFORE UPDATE OR DELETE ON cpe_observation_conflict
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
