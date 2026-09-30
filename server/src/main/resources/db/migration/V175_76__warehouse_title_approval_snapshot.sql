DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_title_transfer(uuid,uuid)'::regprocedure);
    IF strpos(definition,'approval.source_snapshot::jsonb#>>''{document,source_reference}'' IS DISTINCT FROM request.snapshot')=0 THEN
        RAISE EXCEPTION 'approval title snapshot clause missing'; END IF;
    EXECUTE replace(definition,'approval.source_snapshot::jsonb#>>''{document,source_reference}'' IS DISTINCT FROM request.snapshot',
        'approval.source_snapshot::jsonb->''title'' IS DISTINCT FROM request.snapshot::jsonb');
    definition:=pg_get_functiondef('warehouse_assert_title_request(uuid,uuid)'::regprocedure);
    IF strpos(definition,'document.source_reference IS DISTINCT FROM request.snapshot')=0 THEN RAISE EXCEPTION 'document title reference clause missing'; END IF;
    EXECUTE replace(definition,'document.source_reference IS DISTINCT FROM request.snapshot','document.source_reference IS DISTINCT FROM request.id::text');
END $$;
