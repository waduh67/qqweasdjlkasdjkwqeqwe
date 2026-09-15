DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure);
    IF strpos(definition,'IF physical.status IN (''DISPOSED'',''LOST'') THEN')=0 THEN
        RAISE EXCEPTION 'authorization physical condition entry missing'; END IF;
    EXECUTE replace(definition,'IF physical.status IN (''DISPOSED'',''LOST'') THEN',$body$
    IF permit.consumed AND EXISTS(SELECT FROM inventory_asset_removal WHERE tenant_id=scope AND asset_id=physical.id) THEN
        PERFORM warehouse_assert_asset_removal(scope,(SELECT id FROM inventory_asset_removal WHERE tenant_id=scope AND asset_id=physical.id));
        SELECT (jsonb_populate_record(NULL::inventory_serialized_asset,origin.asset_snapshot)).* INTO physical
            FROM inventory_asset_removal_origin origin JOIN inventory_asset_removal removal
                ON removal.tenant_id=origin.tenant_id AND removal.id=origin.removal_id
            WHERE removal.tenant_id=scope AND removal.asset_id=permit.asset_id;
    END IF;
    IF physical.status IN ('DISPOSED','LOST') THEN$body$);
END $$;
