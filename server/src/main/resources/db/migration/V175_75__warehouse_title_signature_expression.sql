DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_capture_acceptance_origin()'::regprocedure);
    IF strpos(definition,'body->''signature''-''receivedAt''')=0 THEN RAISE EXCEPTION 'signature expression missing'; END IF;
    EXECUTE replace(definition,'body->''signature''-''receivedAt''','(body->''signature'')-''receivedAt''');
END $$;
