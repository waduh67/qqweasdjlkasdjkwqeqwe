DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_assert_reference_post_legs_v191(uuid,uuid)'::regprocedure);
    anchor:='AND kind IN (''WAREHOUSE'',''BIN'') AND state=''ACTIVE'' AND issue_eligible';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference return destination anchor changed'; END IF;
    EXECUTE replace(definition,anchor,$body$AND state='ACTIVE' AND
        (CASE WHEN binding.action='RETURN' THEN kind='WAREHOUSE'
            ELSE kind IN ('WAREHOUSE','BIN') AND issue_eligible END)$body$);
END $$;
