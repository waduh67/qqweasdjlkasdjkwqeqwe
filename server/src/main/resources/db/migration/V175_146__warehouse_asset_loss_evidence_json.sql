DO $migration$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_capture_asset_loss_request()'::regprocedure);
    anchor:='body->''evidence''-''receivedAt''';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'loss evidence comparison changed'; END IF;
    EXECUTE replace(definition,anchor,'(body->''evidence'')-''receivedAt''');
END $migration$;
