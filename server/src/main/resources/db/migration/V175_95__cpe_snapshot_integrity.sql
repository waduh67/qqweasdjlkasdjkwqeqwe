CREATE FUNCTION cpe_snapshot_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NOT EXISTS(SELECT FROM cpe_device d JOIN onu o ON o.tenant_id=d.tenant_id AND o.id=d.onu_id AND o.customer_id=d.customer_id
        WHERE d.tenant_id=NEW.tenant_id AND d.id=NEW.device_id AND o.id=NEW.onu_id AND o.retired_at IS NULL
        AND o.assignment_id IS NOT DISTINCT FROM NEW.assignment_id AND o.episode_revision=NEW.episode_revision
        AND NEW.assignment_revision=0 AND NEW.snapshot=to_jsonb(d)
        AND warehouse_canonical_serial(d.serial_number)=warehouse_canonical_serial(o.serial_number)
        AND (o.warehouse_admission='LEGACY_UNRESOLVED' OR (d.last_inform_at>=o.started_at AND d.last_inform_at<=clock_timestamp()+interval '5 minutes'))) THEN
        RAISE EXCEPTION 'CPE snapshot must bind current episode and exact cached fields' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER cpe_snapshot_binding BEFORE INSERT ON cpe_episode_snapshot FOR EACH ROW EXECUTE FUNCTION cpe_snapshot_binding_guard();
CREATE FUNCTION cpe_snapshot_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NOT EXISTS(SELECT FROM cpe_device d JOIN cpe_episode_snapshot s ON s.tenant_id=d.tenant_id AND s.device_id=d.id
        WHERE d.tenant_id=NEW.tenant_id AND d.id=NEW.id AND s.onu_id=d.onu_id AND s.snapshot=to_jsonb(d)) THEN
        RAISE EXCEPTION 'CPE current fields require an immutable episode snapshot' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER cpe_snapshot_final AFTER INSERT OR UPDATE ON cpe_device
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION cpe_snapshot_final_guard();
