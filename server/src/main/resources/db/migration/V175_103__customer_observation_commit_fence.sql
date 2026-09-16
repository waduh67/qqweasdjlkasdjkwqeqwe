CREATE FUNCTION customer_observation_commit_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    IF TG_TABLE_NAME='onu' AND TG_OP='UPDATE' THEN
        IF (NEW.customer_id,NEW.serial_number,NEW.assignment_id,NEW.started_at,NEW.retired_at,NEW.episode_revision)
            IS NOT DISTINCT FROM (OLD.customer_id,OLD.serial_number,OLD.assignment_id,OLD.started_at,OLD.retired_at,OLD.episode_revision) THEN
            RETURN NULL;
        END IF;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(TG_TABLE_SCHEMA||':cpe-ownership-commit',0));
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER customer_observation_commit_fence AFTER INSERT OR UPDATE OR DELETE ON onu
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION customer_observation_commit_fence();
CREATE CONSTRAINT TRIGGER customer_observation_commit_fence AFTER INSERT OR UPDATE OR DELETE ON inventory_asset_assignment
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION customer_observation_commit_fence();
