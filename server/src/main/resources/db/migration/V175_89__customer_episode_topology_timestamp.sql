DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_onu_episode_revision(uuid,uuid)'::regprocedure);
    IF strpos(definition,'topology.snapshot IS DISTINCT FROM jsonb_build_object(''odpId'',episode.odp_id,''portNumber'',episode.odp_port_number,''installedAt'',episode.installed_at)')=0 THEN
        RAISE EXCEPTION 'episode topology timestamp comparison missing'; END IF;
    EXECUTE replace(definition,
        'topology.snapshot IS DISTINCT FROM jsonb_build_object(''odpId'',episode.odp_id,''portNumber'',episode.odp_port_number,''installedAt'',episode.installed_at)',
        '(topology.snapshot-''installedAt'') IS DISTINCT FROM jsonb_build_object(''odpId'',episode.odp_id,''portNumber'',episode.odp_port_number) OR NOT (topology.snapshot ? ''installedAt'') OR (topology.snapshot->>''installedAt'')::timestamptz IS DISTINCT FROM episode.installed_at');
END $$;
