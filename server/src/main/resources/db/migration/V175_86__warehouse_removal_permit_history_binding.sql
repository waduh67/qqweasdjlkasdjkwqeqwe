DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_authorization_retirement_guard()'::regprocedure);
    IF strpos(definition,'permit.purpose=''REPLACE''')=0 THEN RAISE EXCEPTION 'retirement purpose clause missing'; END IF;
    EXECUTE replace(definition,'permit.purpose=''REPLACE''','permit.purpose IN (''REPLACE'',''REMOVE'',''RETURN_CUSTOMER_RMA'')');
    definition:=pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure);
    IF strpos(definition,'WHERE tenant_id=scope AND asset_id=physical.id')=0
        OR strpos(definition,'WHERE removal.tenant_id=scope AND removal.asset_id=permit.asset_id')=0 THEN
        RAISE EXCEPTION 'recovered authorization lookup missing'; END IF;
    definition:=replace(definition,'WHERE tenant_id=scope AND asset_id=physical.id',
        'WHERE tenant_id=scope AND assignment_id=permit.operation_id AND asset_id=physical.id');
    EXECUTE replace(definition,'WHERE removal.tenant_id=scope AND removal.asset_id=permit.asset_id',
        'WHERE removal.tenant_id=scope AND removal.assignment_id=permit.operation_id AND removal.asset_id=permit.asset_id');
END $$;
