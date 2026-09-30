LOCK TABLE odp,odc,pon_port IN SHARE ROW EXCLUSIVE MODE;
CREATE TABLE network_observation_edge (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id), node_kind text NOT NULL CHECK(node_kind IN ('odp','odc','pon_port')),
    node_id uuid NOT NULL, parent_id uuid, label text, deleted boolean NOT NULL DEFAULT false,
    effective_at timestamptz NOT NULL DEFAULT clock_timestamp(), baseline boolean NOT NULL DEFAULT false,
    UNIQUE(tenant_id,id)
);
CREATE INDEX network_observation_edge_lookup ON network_observation_edge(tenant_id,node_kind,node_id,id DESC);
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT,UPDATE,DELETE ON network_observation_edge TO warehouse_app;
        GRANT USAGE,SELECT ON SEQUENCE network_observation_edge_id_seq TO warehouse_app;
    END IF;
END $$;
ALTER TABLE network_observation_edge ENABLE ROW LEVEL SECURITY;
ALTER TABLE network_observation_edge FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON network_observation_edge
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
INSERT INTO network_observation_edge(tenant_id,node_kind,node_id,parent_id,baseline)
    SELECT tenant_id,'odp',id,odc_id,true FROM odp;
INSERT INTO network_observation_edge(tenant_id,node_kind,node_id,parent_id,baseline)
    SELECT tenant_id,'odc',id,pon_port_id,true FROM odc;
INSERT INTO network_observation_edge(tenant_id,node_kind,node_id,parent_id,label,baseline)
    SELECT tenant_id,'pon_port',id,olt_id,label,true FROM pon_port;
CREATE FUNCTION network_observation_edge_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF pg_trigger_depth()<2 OR NEW.baseline THEN
        RAISE EXCEPTION 'network path evidence requires its owner transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER network_observation_edge_insert_guard BEFORE INSERT ON network_observation_edge
    FOR EACH ROW EXECUTE FUNCTION network_observation_edge_insert_guard();
CREATE TRIGGER network_observation_edge_immutable BEFORE UPDATE OR DELETE ON network_observation_edge
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE FUNCTION network_observation_mutation_lock() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE scope uuid:=NULLIF(current_setting('app.tenant_id',true),'')::uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM pg_advisory_xact_lock(hashtextextended(TG_TABLE_SCHEMA||':network-observation:'||scope::text,0));
    RETURN NULL;
END $$;
CREATE FUNCTION network_record_observation_edge() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE value jsonb; previous jsonb; parent_key text; transition_at timestamptz:=clock_timestamp();
BEGIN
    value:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    parent_key:=CASE TG_TABLE_NAME WHEN 'odp' THEN 'odc_id' WHEN 'odc' THEN 'pon_port_id' ELSE 'olt_id' END;
    IF TG_OP='UPDATE' THEN
        previous:=to_jsonb(OLD);
        IF (value->parent_key,value->'label') IS NOT DISTINCT FROM (previous->parent_key,previous->'label') THEN RETURN NEW; END IF;
    END IF;
    PERFORM warehouse_assert_deferred_scope((value->>'tenant_id')::uuid);
    IF EXISTS(SELECT FROM network_observation_edge WHERE tenant_id=(value->>'tenant_id')::uuid
        AND node_kind=TG_TABLE_NAME AND node_id=(value->>'id')::uuid AND effective_at>=transition_at) THEN
        RAISE EXCEPTION 'network transition clock must advance under serialization' USING ERRCODE='23514';
    END IF;
    INSERT INTO network_observation_edge(tenant_id,node_kind,node_id,parent_id,label,deleted,effective_at)
    VALUES((value->>'tenant_id')::uuid,TG_TABLE_NAME,(value->>'id')::uuid,(value->>parent_key)::uuid,
        value->>'label',TG_OP='DELETE',transition_at);
    RETURN NULL;
END $$;
DO $$ DECLARE target text;
BEGIN
    FOREACH target IN ARRAY ARRAY['odp','odc','pon_port'] LOOP
        EXECUTE format('CREATE TRIGGER network_observation_mutation_lock BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH STATEMENT EXECUTE FUNCTION network_observation_mutation_lock()',target);
        EXECUTE format('CREATE TRIGGER network_record_observation_edge AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION network_record_observation_edge()',target);
    END LOOP;
END $$;
