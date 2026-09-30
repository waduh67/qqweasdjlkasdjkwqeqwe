ALTER TABLE onu_metric ADD COLUMN attribution jsonb;
ALTER TABLE onu_metric ADD COLUMN attribution_topology_revision bigint
    GENERATED ALWAYS AS ((attribution->>'topologyRevision')::bigint) STORED;
ALTER TABLE onu_metric ADD CONSTRAINT onu_metric_topology_reference
    FOREIGN KEY(tenant_id,onu_id,attribution_topology_revision)
    REFERENCES onu_topology_history(tenant_id,onu_id,revision) NOT VALID;
CREATE FUNCTION monitoring_metric_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE edge_id bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.time>clock_timestamp() THEN
        RAISE EXCEPTION 'future observations cannot be finalized as metric attribution' USING ERRCODE='23514';
    END IF;
    IF NEW.attribution IS NULL THEN
        NEW.attribution_verified:=false;
        RETURN NEW;
    END IF;
    IF NOT NEW.attribution_verified OR NEW.attribution->>'source' IS NULL
        OR NEW.attribution->>'source' NOT IN ('COLLECTOR','SERVER_POLL','SERVER_INSTANT')
        OR NEW.attribution->>'receivedAt' IS NULL OR jsonb_typeof(NEW.attribution->'networkEdgeIds') IS DISTINCT FROM 'array'
        OR NEW.attribution->>'decision' IS DISTINCT FROM 'BOUND'
        OR (NEW.attribution->>'receivedAt')::timestamptz<NEW.time
        OR NOT EXISTS(SELECT FROM onu WHERE tenant_id=NEW.tenant_id AND id=NEW.onu_id
            AND episode_revision=(NEW.attribution->>'episodeRevisionAtDecision')::bigint
            AND assignment_id IS NOT DISTINCT FROM (NEW.attribution->>'assignmentId')::uuid)
        OR (NEW.attribution->>'assignmentId' IS NOT NULL AND NOT EXISTS(SELECT FROM inventory_asset_assignment
            WHERE tenant_id=NEW.tenant_id AND id=(NEW.attribution->>'assignmentId')::uuid
                AND revision=(NEW.attribution->>'assignmentRevisionAtDecision')::bigint))
        OR (NEW.attribution->>'topologyRevision')::bigint IS DISTINCT FROM
            (SELECT revision FROM onu_topology_history WHERE tenant_id=NEW.tenant_id AND onu_id=NEW.onu_id
                AND (revision=0 OR effective_at<=NEW.time) ORDER BY revision DESC LIMIT 1) THEN
        RAISE EXCEPTION 'metric attribution decision must bind current evidence and historical path' USING ERRCODE='23514';
    END IF;
    FOR edge_id IN SELECT jsonb_array_elements_text(NEW.attribution->'networkEdgeIds')::bigint LOOP
        IF NOT EXISTS(SELECT FROM network_observation_edge WHERE tenant_id=NEW.tenant_id AND id=edge_id AND effective_at<=NEW.time) THEN
            RAISE EXCEPTION 'metric edge evidence must be tenant scoped and historical' USING ERRCODE='23514';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;
CREATE TRIGGER monitoring_metric_evidence BEFORE INSERT ON onu_metric
    FOR EACH ROW EXECUTE FUNCTION monitoring_metric_evidence_guard();
