DO $$ DECLARE definition text; BEGIN
    definition:=pg_get_functiondef('warehouse_assert_reference_consume(uuid,uuid)'::regprocedure);
    IF strpos(definition,'quantity bigint; debit bigint; retained bigint;')=0 THEN
        RAISE EXCEPTION 'reference consumed quantity variable anchor changed';
    END IF;
    EXECUTE regexp_replace(definition,'\mquantity\M','consumed_base','g');
END $$;
