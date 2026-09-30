DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_count_result(uuid,uuid)'::regprocedure);
    IF strpos(definition,'quantity bigint')=0 THEN RAISE EXCEPTION 'count variance variable missing'; END IF;
    EXECUTE regexp_replace(definition,'\mquantity\M','variance_quantity','g');
END $$;
