DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_assert_reference_post(uuid,uuid)'::regprocedure);
    anchor:='OR movement.cutover_epoch IS DISTINCT FROM operation.cutover_epoch
        OR movement.authority_epoch IS DISTINCT FROM operation.authority_epoch';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference command guard layout changed'; END IF;
    definition:=replace(definition,anchor,'OR operation.cutover_epoch IS DISTINCT FROM document.cutover_epoch');
    EXECUTE definition;
END $$;
