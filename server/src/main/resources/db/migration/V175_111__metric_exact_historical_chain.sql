CREATE OR REPLACE FUNCTION monitoring_metric_evidence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE topology onu_topology_history; distribution network_observation_edge;
    cabinet network_observation_edge; port network_observation_edge; expected jsonb; odp_id uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF current_setting('transaction_isolation')<>'read committed' THEN
        RAISE EXCEPTION 'metric attribution requires read committed' USING ERRCODE='23514';
    END IF;
    PERFORM pg_advisory_xact_lock_shared(hashtextextended(namespace.nspname||':network-observation:'||NEW.tenant_id::text,0))
        FROM pg_class relation JOIN pg_namespace namespace ON namespace.oid=relation.relnamespace WHERE relation.oid='network_observation_edge'::regclass;
    IF NEW.time>clock_timestamp() OR (NEW.olt_id IS NOT NULL AND NOT EXISTS(SELECT FROM olt WHERE tenant_id=NEW.tenant_id AND id=NEW.olt_id)) THEN
        RAISE EXCEPTION 'metric time and reported OLT must be admissible' USING ERRCODE='23514';
    END IF;
    IF NEW.attribution IS NULL THEN NEW.attribution_verified:=false; RETURN NEW; END IF;
    SELECT * INTO topology FROM onu_topology_history WHERE tenant_id=NEW.tenant_id AND onu_id=NEW.onu_id
        AND (revision=0 OR effective_at<=NEW.time) ORDER BY revision DESC LIMIT 1;
    IF NOT NEW.attribution_verified OR NEW.attribution->>'source' IS NULL
        OR NEW.attribution->>'source' NOT IN ('COLLECTOR','SERVER_POLL','SERVER_INSTANT')
        OR NEW.attribution->>'receivedAt' IS NULL OR jsonb_typeof(NEW.attribution->'networkEdgeIds') IS DISTINCT FROM 'array'
        OR (NEW.attribution->>'receivedAt')::timestamptz<NEW.time
        OR NOT EXISTS(SELECT FROM onu WHERE tenant_id=NEW.tenant_id AND id=NEW.onu_id
            AND episode_revision=(NEW.attribution->>'episodeRevisionAtDecision')::bigint
            AND assignment_id IS NOT DISTINCT FROM (NEW.attribution->>'assignmentId')::uuid)
        OR (NEW.attribution->>'assignmentId' IS NOT NULL AND NOT EXISTS(SELECT FROM inventory_asset_assignment
            WHERE tenant_id=NEW.tenant_id AND id=(NEW.attribution->>'assignmentId')::uuid
                AND revision=(NEW.attribution->>'assignmentRevisionAtDecision')::bigint))
        OR topology.onu_id IS NULL OR (NEW.attribution->>'topologyRevision')::bigint IS DISTINCT FROM topology.revision THEN
        RAISE EXCEPTION 'metric decision must bind its episode and historical topology' USING ERRCODE='23514';
    END IF;
    odp_id:=(topology.snapshot->>'odpId')::uuid;
    IF odp_id IS NULL THEN
        IF NEW.attribution->>'decision' IS DISTINCT FROM 'EPISODE_ONLY' OR NEW.attribution->'networkEdgeIds' IS DISTINCT FROM '[]'::jsonb THEN
            RAISE EXCEPTION 'unattached episode cannot assert verified path evidence' USING ERRCODE='23514';
        END IF;
        NEW.attribution_verified:=false;
        RETURN NEW;
    END IF;
    SELECT * INTO distribution FROM network_observation_edge WHERE tenant_id=NEW.tenant_id AND node_kind='odp'
        AND node_id=odp_id AND effective_at<=NEW.time ORDER BY id DESC LIMIT 1;
    SELECT * INTO cabinet FROM network_observation_edge WHERE tenant_id=NEW.tenant_id AND node_kind='odc'
        AND node_id=distribution.parent_id AND effective_at<=NEW.time ORDER BY id DESC LIMIT 1;
    SELECT * INTO port FROM network_observation_edge WHERE tenant_id=NEW.tenant_id AND node_kind='pon_port'
        AND node_id=cabinet.parent_id AND effective_at<=NEW.time ORDER BY id DESC LIMIT 1;
    expected:=jsonb_build_array(distribution.id,cabinet.id,port.id);
    IF distribution.id IS NULL OR cabinet.id IS NULL OR port.id IS NULL OR distribution.deleted OR cabinet.deleted OR port.deleted
        OR port.parent_id IS NULL OR NEW.olt_id IS DISTINCT FROM port.parent_id
        OR NEW.attribution->>'decision' IS DISTINCT FROM 'BOUND' OR NEW.attribution->'networkEdgeIds' IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'BOUND metric requires the exact latest historical ODP ODC PON chain and OLT' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
