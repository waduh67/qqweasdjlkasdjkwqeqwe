DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF strpos(definition,'CASE OLD.kind')=0 THEN RAISE EXCEPTION 'expected document lifecycle missing'; END IF;
    EXECUTE replace(definition,'CASE OLD.kind',
        'CASE OLD.kind WHEN ''DEPLOYMENT'' THEN (OLD.state,NEW.state)=(''DRAFT'',''POSTED'')');
END $$;
