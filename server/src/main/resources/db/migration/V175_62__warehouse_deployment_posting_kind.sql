DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_result(uuid,uuid)'::regprocedure);
    IF strpos(definition,'movement.kind=''CONSUME''')=0 THEN RAISE EXCEPTION 'expected deployment posting kind missing'; END IF;
    EXECUTE replace(definition,'movement.kind=''CONSUME''','movement.kind=''DEPLOY''');
END $$;
