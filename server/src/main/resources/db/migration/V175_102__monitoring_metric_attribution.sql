ALTER TABLE onu_metric ADD COLUMN attribution_verified boolean NOT NULL DEFAULT false;
ALTER TABLE onu_metric ALTER COLUMN attribution_verified SET DEFAULT true;
ALTER TABLE onu_metric ADD CONSTRAINT onu_metric_episode_reference
    FOREIGN KEY(tenant_id,onu_id) REFERENCES onu(tenant_id,id) NOT VALID;
CREATE FUNCTION monitoring_metric_attribution_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NOT NEW.attribution_verified THEN
        IF current_user<>pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid=TG_RELID)) THEN
            RAISE EXCEPTION 'new metrics require verified attribution' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT EXISTS(SELECT FROM onu WHERE tenant_id=NEW.tenant_id AND id=NEW.onu_id
        AND NEW.time>=coalesce(started_at,created_at) AND (retired_at IS NULL OR NEW.time<retired_at)) THEN
        RAISE EXCEPTION 'metric must reference its tenant episode at observation time' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER monitoring_metric_attribution BEFORE INSERT ON onu_metric
    FOR EACH ROW EXECUTE FUNCTION monitoring_metric_attribution_guard();
CREATE TRIGGER monitoring_metric_immutable BEFORE UPDATE OR DELETE ON onu_metric
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
