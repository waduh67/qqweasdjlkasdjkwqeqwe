DO $$ DECLARE target regprocedure; definition text;
BEGIN
    FOREACH target IN ARRAY ARRAY['warehouse_capture_acceptance_origin()'::regprocedure,'warehouse_assert_asset_handover(uuid,uuid)'::regprocedure] LOOP
        definition:=pg_get_functiondef(target);
        IF strpos(definition,'SELECT to_jsonb(result)-''result'' FROM inventory_deployment_result result WHERE')=0 THEN
            RAISE EXCEPTION 'expected deployment result projection missing'; END IF;
        EXECUTE replace(definition,'SELECT to_jsonb(result)-''result'' FROM inventory_deployment_result result WHERE',
            'SELECT to_jsonb(outcome)-''result'' FROM inventory_deployment_result outcome WHERE');
    END LOOP;
END $$;
