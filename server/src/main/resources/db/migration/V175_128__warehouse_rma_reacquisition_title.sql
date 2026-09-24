DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_title_request(uuid,uuid)'::regprocedure);
    anchor:='CASE handover.ownership_mode WHEN ''SALE'' THEN 1 ELSE 0 END';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'title request revision entry changed'; END IF;
    EXECUTE replace(definition,anchor,
        'CASE WHEN history->>''purpose''=''RETURN_CUSTOMER_RMA'' THEN 0 WHEN handover.ownership_mode=''SALE'' THEN 1 ELSE 0 END');

    definition:=pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure);
    anchor:='(physical.legal_owner<>''CUSTOMER'' OR permit.ownership_mode<>''SALE'')';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'RMA current title entry changed'; END IF;
    EXECUTE replace(definition,anchor,$patch$
        (physical.legal_owner IS DISTINCT FROM (CASE WHEN permit.consumed
            THEN warehouse_asset_expected_owner(scope,permit.operation_id) ELSE 'CUSTOMER' END)
            OR permit.ownership_mode<>'SALE')$patch$);
END $$;
