DO $$ DECLARE definition text; anchor text:='IF EXISTS(SELECT FROM inventory_document other JOIN inventory_document_line line';
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_result(uuid,uuid)'::regprocedure);
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'expected extra deployment document check missing'; END IF;
    EXECUTE replace(definition,anchor,'IF permit.consumed AND EXISTS(SELECT FROM inventory_document other JOIN inventory_document_line line');
END $$;
