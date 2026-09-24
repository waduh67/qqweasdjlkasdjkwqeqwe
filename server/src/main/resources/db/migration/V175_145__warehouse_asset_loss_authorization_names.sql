DO $migration$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure);
    anchor:='inventory_asset_loss_permit_retirement WHERE tenant_id=scope AND authorization_id=permit.id';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'loss retired-permit lookup changed'; END IF;
    definition:=replace(definition,anchor,
        'inventory_asset_loss_permit_retirement loss_retirement WHERE loss_retirement.tenant_id=scope AND loss_retirement.authorization_id=permit.id');
    anchor:='inventory_asset_loss_permit_retirement retirement WHERE tenant_id=scope AND authorization_id=permit.id';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'loss retired-permit snapshot lookup changed'; END IF;
    EXECUTE replace(definition,anchor,
        'inventory_asset_loss_permit_retirement retirement WHERE retirement.tenant_id=scope AND retirement.authorization_id=permit.id');
END $migration$;
