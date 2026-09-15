DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure);
    IF strpos(definition,'SELECT FROM inventory_deployment_execution WHERE tenant_id=scope AND authorization_id=permit.id')=0 THEN
        RAISE EXCEPTION 'execution intent clause missing'; END IF;
    EXECUTE replace(definition,'SELECT FROM inventory_deployment_execution WHERE tenant_id=scope AND authorization_id=permit.id',
        'SELECT FROM inventory_deployment_execution execution WHERE execution.tenant_id=scope AND execution.authorization_id=permit.id');
END $$;
