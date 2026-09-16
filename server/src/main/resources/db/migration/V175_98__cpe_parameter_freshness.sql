ALTER TABLE cpe_episode_snapshot ADD COLUMN observed_fields_at timestamptz;
DROP INDEX cpe_episode_snapshot_dedup;
CREATE UNIQUE INDEX cpe_episode_snapshot_dedup ON cpe_episode_snapshot
    (tenant_id,device_id,episode_revision,md5(snapshot::text),observed_fields_at) NULLS NOT DISTINCT;
ALTER TABLE cpe_unassigned_observation DROP CONSTRAINT cpe_unassigned_observation_reason_check;
ALTER TABLE cpe_unassigned_observation ADD CONSTRAINT cpe_unassigned_observation_reason_check
    CHECK(reason IN ('AMBIGUOUS_SERIAL','STALE_INFORM','STALE_FIELDS'));
CREATE OR REPLACE FUNCTION cpe_snapshot_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NOT EXISTS(SELECT FROM cpe_device d JOIN onu o ON o.tenant_id=d.tenant_id AND o.id=d.onu_id AND o.customer_id=d.customer_id
        WHERE d.tenant_id=NEW.tenant_id AND d.id=NEW.device_id AND o.id=NEW.onu_id AND o.retired_at IS NULL
        AND o.assignment_id IS NOT DISTINCT FROM NEW.assignment_id AND o.episode_revision=NEW.episode_revision
        AND NEW.assignment_revision=0 AND NEW.snapshot=to_jsonb(d)
        AND warehouse_canonical_serial(d.serial_number)=warehouse_canonical_serial(o.serial_number)
        AND (o.warehouse_admission='LEGACY_UNRESOLVED' OR (d.last_inform_at>=o.started_at AND d.last_inform_at<=clock_timestamp()+interval '5 minutes'
            AND NEW.observed_fields_at>=o.started_at AND NEW.observed_fields_at<=clock_timestamp()+interval '5 minutes'))) THEN
        RAISE EXCEPTION 'CPE snapshot requires current episode and fresh parameter evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
