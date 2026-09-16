CREATE TABLE customer_onu_observation_state (
    tenant_id uuid NOT NULL, onu_id uuid NOT NULL, last_live_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,onu_id),
    FOREIGN KEY(tenant_id,onu_id) REFERENCES onu(tenant_id,id)
);
ALTER TABLE customer_onu_observation_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_onu_observation_state FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_onu_observation_state
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE FUNCTION customer_observation_state_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='UPDATE' AND ((NEW.tenant_id,NEW.onu_id) IS DISTINCT FROM (OLD.tenant_id,OLD.onu_id)
        OR NEW.last_live_at<=OLD.last_live_at) THEN
        RAISE EXCEPTION 'observation state must advance without reparenting' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(SELECT FROM onu WHERE tenant_id=NEW.tenant_id AND id=NEW.onu_id AND retired_at IS NULL) THEN
        RAISE EXCEPTION 'historical episode cannot receive live state' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER customer_observation_state_guard BEFORE INSERT OR UPDATE ON customer_onu_observation_state
    FOR EACH ROW EXECUTE FUNCTION customer_observation_state_guard();
CREATE TRIGGER customer_observation_state_retention BEFORE DELETE ON customer_onu_observation_state
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
