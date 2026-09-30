ALTER TABLE onu_metric ADD COLUMN interval_protected boolean NOT NULL DEFAULT false;
ALTER TABLE onu_metric ALTER COLUMN interval_protected SET DEFAULT true;
CREATE FUNCTION monitoring_lock_metric_episode() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF current_setting('transaction_isolation')<>'read committed' OR NOT NEW.interval_protected THEN
        RAISE EXCEPTION 'new metric requires a current protected episode interval' USING ERRCODE='23514';
    END IF;
    PERFORM pg_advisory_xact_lock_shared(hashtextextended(namespace.nspname||':network-observation:'||NEW.tenant_id::text,0))
        FROM pg_class relation JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace WHERE relation.oid='network_observation_edge'::regclass;
    PERFORM id FROM onu WHERE tenant_id=NEW.tenant_id AND id=NEW.onu_id FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'metric episode unavailable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER aa_monitoring_metric_episode_lock BEFORE INSERT ON onu_metric
    FOR EACH ROW EXECUTE FUNCTION monitoring_lock_metric_episode();
CREATE FUNCTION customer_assert_metric_interval() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE episode onu;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'episode interval mutation requires read committed' USING ERRCODE='23514';
    END IF;
    SELECT * INTO episode FROM onu WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
    IF EXISTS(SELECT FROM onu_metric WHERE tenant_id=NEW.tenant_id AND onu_id=NEW.id
        AND (interval_protected OR attribution_verified)
        AND (time<coalesce(episode.started_at,episode.created_at) OR (episode.retired_at IS NOT NULL AND time>=episode.retired_at))) THEN
        RAISE EXCEPTION 'episode closure would invalidate retained metric attribution' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER customer_metric_interval_final AFTER UPDATE ON onu
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    WHEN (OLD.started_at IS DISTINCT FROM NEW.started_at OR OLD.retired_at IS DISTINCT FROM NEW.retired_at)
    EXECUTE FUNCTION customer_assert_metric_interval();
