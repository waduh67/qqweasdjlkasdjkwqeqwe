ALTER TABLE cpe_device ADD CONSTRAINT cpe_device_tenant_identity UNIQUE(tenant_id,id);
ALTER TABLE cpe_device DROP CONSTRAINT uq_cpe_device_genieacs;
CREATE UNIQUE INDEX cpe_device_episode_identity ON cpe_device(tenant_id,genieacs_id,onu_id) WHERE onu_id IS NOT NULL;
CREATE UNIQUE INDEX cpe_device_unbound_identity ON cpe_device(tenant_id,genieacs_id) WHERE onu_id IS NULL;
CREATE TABLE cpe_episode_snapshot (
    id uuid NOT NULL DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, device_id uuid NOT NULL, onu_id uuid NOT NULL,
    assignment_id uuid, assignment_revision bigint NOT NULL, episode_revision bigint NOT NULL,
    snapshot jsonb NOT NULL, recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,id),
    FOREIGN KEY(tenant_id,device_id) REFERENCES cpe_device(tenant_id,id),
    FOREIGN KEY(tenant_id,onu_id) REFERENCES onu(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id)
);
CREATE UNIQUE INDEX cpe_episode_snapshot_dedup ON cpe_episode_snapshot(tenant_id,device_id,episode_revision,md5(snapshot::text));
ALTER TABLE cpe_episode_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE cpe_episode_snapshot FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cpe_episode_snapshot
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
INSERT INTO cpe_episode_snapshot(tenant_id,device_id,onu_id,assignment_id,assignment_revision,episode_revision,snapshot)
SELECT d.tenant_id,d.id,o.id,o.assignment_id,CASE WHEN o.retired_at IS NULL THEN 0 ELSE 1 END,o.episode_revision,to_jsonb(d)
FROM cpe_device d JOIN onu o ON o.tenant_id=d.tenant_id AND o.id=d.onu_id AND o.customer_id=d.customer_id
WHERE warehouse_canonical_serial(d.serial_number)=warehouse_canonical_serial(o.serial_number);
CREATE TRIGGER cpe_snapshot_immutable BEFORE UPDATE OR DELETE ON cpe_episode_snapshot
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE FUNCTION cpe_episode_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF (NEW.id,NEW.tenant_id,NEW.genieacs_id,NEW.serial_number,NEW.customer_id,NEW.onu_id)
        IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.genieacs_id,OLD.serial_number,OLD.customer_id,OLD.onu_id)
        OR (OLD.last_inform_at IS NOT NULL AND (NEW.last_inform_at IS NULL OR NEW.last_inform_at<OLD.last_inform_at)) THEN
        RAISE EXCEPTION 'CPE identity and episode cannot be reparented or freshness regressed' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER cpe_episode_identity BEFORE UPDATE ON cpe_device FOR EACH ROW EXECUTE FUNCTION cpe_episode_identity_guard();
CREATE TRIGGER cpe_episode_retention BEFORE DELETE ON cpe_device FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
