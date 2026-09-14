DO $$ DECLARE definition text; query_start integer; query_end integer; query_text text;
BEGIN
    SELECT pg_get_functiondef('warehouse_assert_asset_episodes(uuid,uuid)'::regprocedure) INTO definition;
    query_start:=strpos(definition,'IF EXISTS (SELECT FROM onu episode LEFT JOIN inventory_asset_assignment assignment');
    query_end:=strpos(definition,'IF EXISTS (SELECT FROM onu first JOIN onu second');
    IF query_start=0 OR query_end<=query_start THEN
        RAISE EXCEPTION 'expected episode binding query missing';
    END IF;
    query_text:=substring(definition FROM query_start FOR query_end-query_start);
    EXECUTE replace(definition,query_text,
        replace(replace(query_text,'inventory_asset_assignment assignment','inventory_asset_assignment bound_assignment'),
            'assignment.','bound_assignment.'));
END $$;
