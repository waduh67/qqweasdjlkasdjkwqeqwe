ALTER TABLE cpe_episode_snapshot ADD COLUMN revision_evidence text NOT NULL DEFAULT 'LEGACY_UNVERIFIED';
ALTER TABLE cpe_episode_snapshot ALTER COLUMN revision_evidence SET DEFAULT 'CURRENT_VERIFIED';
ALTER TABLE cpe_episode_snapshot ADD CONSTRAINT cpe_revision_evidence_kind
    CHECK(revision_evidence IN ('LEGACY_UNVERIFIED','CURRENT_VERIFIED'));
DROP INDEX cpe_episode_snapshot_dedup;
CREATE UNIQUE INDEX cpe_episode_snapshot_dedup ON cpe_episode_snapshot
    (tenant_id,device_id,assignment_revision,episode_revision,md5(snapshot::text),observed_fields_at,revision_evidence) NULLS NOT DISTINCT;
CREATE FUNCTION cpe_revision_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.revision_evidence<>'CURRENT_VERIFIED' THEN
        RAISE EXCEPTION 'new snapshots require current binding evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER cpe_revision_evidence_guard BEFORE INSERT ON cpe_episode_snapshot
    FOR EACH ROW EXECUTE FUNCTION cpe_revision_evidence_guard();
