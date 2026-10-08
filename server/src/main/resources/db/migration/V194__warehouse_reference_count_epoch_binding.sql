DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_assert_reference_count_post(uuid,uuid)'::regprocedure);
    anchor:=' OR movement.cutover_epoch<>attempt.cutover_epoch';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'count cutover guard layout changed'; END IF;
    definition:=replace(definition,anchor,'');
    anchor:=' OR movement.authority_epoch<>attempt.authority_epoch';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'count authority guard layout changed'; END IF;
    definition:=replace(definition,anchor,'');
    EXECUTE definition;
END $$;
