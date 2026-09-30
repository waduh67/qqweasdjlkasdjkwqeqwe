CREATE TABLE customer_onu_observation_path (
    tenant_id uuid NOT NULL, onu_id uuid NOT NULL, topology_revision bigint NOT NULL,
    effective_at timestamptz NOT NULL, captured_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    baseline boolean NOT NULL DEFAULT false, has_odp boolean NOT NULL,
    olt_id uuid, pon_port_id uuid, pon_port_label text,
    PRIMARY KEY(tenant_id,onu_id,topology_revision),
    FOREIGN KEY(tenant_id,onu_id,topology_revision) REFERENCES onu_topology_history(tenant_id,onu_id,revision)
);
ALTER TABLE customer_onu_observation_path ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_onu_observation_path FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_onu_observation_path
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
INSERT INTO customer_onu_observation_path(tenant_id,onu_id,topology_revision,effective_at,baseline,has_odp,olt_id,pon_port_id,pon_port_label)
SELECT h.tenant_id,h.onu_id,h.revision,h.effective_at,true,h.snapshot->>'odpId' IS NOT NULL,p.olt_id,p.id,p.label
FROM onu_topology_history h
LEFT JOIN odp ON odp.tenant_id=h.tenant_id AND odp.id::text=h.snapshot->>'odpId'
LEFT JOIN odc ON odc.tenant_id=odp.tenant_id AND odc.id=odp.odc_id
LEFT JOIN pon_port p ON p.tenant_id=odc.tenant_id AND p.id=odc.pon_port_id;
CREATE FUNCTION customer_capture_observation_path() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    INSERT INTO customer_onu_observation_path(tenant_id,onu_id,topology_revision,effective_at,has_odp,olt_id,pon_port_id,pon_port_label)
    SELECT NEW.tenant_id,NEW.onu_id,NEW.revision,NEW.effective_at,NEW.snapshot->>'odpId' IS NOT NULL,p.olt_id,p.id,p.label
    FROM (SELECT 1) seed
    LEFT JOIN odp ON odp.tenant_id=NEW.tenant_id AND odp.id::text=NEW.snapshot->>'odpId'
    LEFT JOIN odc ON odc.tenant_id=odp.tenant_id AND odc.id=odp.odc_id
    LEFT JOIN pon_port p ON p.tenant_id=odc.tenant_id AND p.id=odc.pon_port_id;
    RETURN NEW;
END $$;
CREATE TRIGGER customer_capture_observation_path AFTER INSERT ON onu_topology_history
    FOR EACH ROW EXECUTE FUNCTION customer_capture_observation_path();
CREATE TRIGGER customer_observation_path_immutable BEFORE UPDATE OR DELETE ON customer_onu_observation_path
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
