DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_returned_asset(uuid,uuid)'::regprocedure);
    anchor:='SELECT * INTO intake FROM inventory_return_case WHERE tenant_id=scope AND id=target AND origin=''ASSET_REMOVAL'';';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'returned asset origin query changed'; END IF;
    EXECUTE replace(definition,anchor,
        'SELECT entry.* INTO intake FROM inventory_return_case entry WHERE entry.tenant_id=scope AND entry.id=target AND entry.origin=''ASSET_REMOVAL'';');
END $$;
