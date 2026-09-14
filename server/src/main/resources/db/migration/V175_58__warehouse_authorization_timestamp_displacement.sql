DO $$ DECLARE definition text; previous text:='WHEN invalid_datetime_format OR datetime_field_overflow THEN';
BEGIN
    SELECT pg_get_functiondef('warehouse_authorization_snapshot_matches(jsonb,inventory_deployment_authorization)'::regprocedure) INTO definition;
    IF strpos(definition,previous)=0 THEN RAISE EXCEPTION 'expected timestamp parser handling missing'; END IF;
    EXECUTE replace(definition,previous,
        'WHEN invalid_datetime_format OR datetime_field_overflow OR invalid_time_zone_displacement_value THEN');
END $$;
