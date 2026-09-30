CREATE TABLE cpe_unassigned_observation (
    tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL DEFAULT gen_random_uuid(),
    serial_number text NOT NULL, source_hash text NOT NULL,
    reason text NOT NULL CHECK(reason IN ('AMBIGUOUS_SERIAL','STALE_INFORM')),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,serial_number,source_hash,reason)
);
ALTER TABLE cpe_unassigned_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE cpe_unassigned_observation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cpe_unassigned_observation
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER cpe_unassigned_observation_immutable BEFORE UPDATE OR DELETE ON cpe_unassigned_observation
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
