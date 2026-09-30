DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure);
    IF strpos(definition,'PERFORM warehouse_assert_verified_segment(scope,physical.id,true);')=0 THEN RAISE EXCEPTION 'deployment physical validator missing'; END IF;
    EXECUTE replace(definition,'PERFORM warehouse_assert_verified_segment(scope,physical.id,true);',$body$
        PERFORM warehouse_assert_verified_segment(scope,physical.id,true);
        IF permit.purpose='INSTALL' AND EXISTS(SELECT FROM inventory_deployment_execution WHERE tenant_id=scope AND authorization_id=permit.id) THEN
            PERFORM warehouse_assert_asset_intent(scope,permit.asset_id,permit.ownership_mode);
        END IF;
    $body$);
    definition:=pg_get_functiondef('warehouse_assert_title_request(uuid,uuid)'::regprocedure);
    IF strpos(definition,'IF document.state=''POSTED'' AND NOT EXISTS')=0 THEN RAISE EXCEPTION 'title document validator missing'; END IF;
    EXECUTE replace(definition,'IF document.state=''POSTED'' AND NOT EXISTS',$body$
    IF (body#>>'{source,latestTransferId}')::uuid IS DISTINCT FROM
        (SELECT id FROM inventory_asset_title_transfer WHERE tenant_id=scope AND assignment_id=request.assignment_id AND title_revision=request.source_title_revision)
        OR (body#>>'{source,recoveryRequired}')::boolean IS DISTINCT FROM
            (request.source_owner='ISP' AND (handover.ownership_mode='LOAN' OR request.source_assignment_revision>1))
        OR body#>>'{source,positionStatus}' IS DISTINCT FROM 'CUSTOMER_INSTALLED'
        OR (body#>>'{source,serviceCeased}')::boolean IS DISTINCT FROM false
        OR (body#>>'{source,recoveryDue}')::boolean IS DISTINCT FROM false THEN
        RAISE EXCEPTION 'TITLE_REQUEST_PREDECESSOR_CONTEXT' USING ERRCODE='23514';
    END IF;
    IF document.state='POSTED' AND ((SELECT count(*) FROM inventory_operation WHERE tenant_id=scope AND document_id=request.id)<>1
        OR (SELECT count(*) FROM inventory_asset_title_transfer WHERE tenant_id=scope AND inventory_asset_title_transfer.request_id=request.id)<>1) THEN
        RAISE EXCEPTION 'TITLE_DOCUMENT_EXACT_EFFECT' USING ERRCODE='23514'; END IF;
    IF document.state='POSTED' AND NOT EXISTS$body$);
END $$;
