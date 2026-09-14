CREATE FUNCTION warehouse_assert_deployment_document(scope uuid, target_document uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE document inventory_document; item inventory_document_line; permit inventory_deployment_authorization;
    execution inventory_deployment_execution; operation inventory_operation; outcome inventory_deployment_result;
    movement inventory_movement; line_count bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=target_document;
    IF NOT FOUND THEN
        IF EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND inventory_operation.document_id=target_document)
            OR EXISTS(SELECT FROM inventory_deployment_result WHERE tenant_id=scope AND operation_id=target_document) THEN
            RAISE EXCEPTION 'DEPLOYMENT_DOCUMENT_REQUIRED' USING ERRCODE='23514';
        END IF;
        RETURN;
    END IF;
    IF document.kind<>'DEPLOYMENT' THEN RETURN; END IF;
    SELECT count(*) INTO line_count FROM inventory_document_line WHERE tenant_id=scope AND inventory_document_line.document_id=document.id;
    IF document.state='DRAFT' THEN
        IF document.revision<>0 OR line_count>1
            OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND inventory_operation.document_id=document.id)
            OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND inventory_movement.document_id=document.id)
            OR EXISTS(SELECT FROM inventory_deployment_result WHERE tenant_id=scope AND operation_id=document.id) THEN
            RAISE EXCEPTION 'DEPLOYMENT_DRAFT_HAS_EFFECTS' USING ERRCODE='23514';
        END IF;
        RETURN;
    END IF;
    SELECT * INTO permit FROM inventory_deployment_authorization WHERE tenant_id=scope AND operation_id=document.id;
    SELECT * INTO execution FROM inventory_deployment_execution WHERE tenant_id=scope AND authorization_id=permit.id;
    SELECT * INTO outcome FROM inventory_deployment_result WHERE tenant_id=scope AND authorization_id=permit.id;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=scope AND id=permit.operation_id;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND id=outcome.posting_id;
    IF document.state<>'POSTED' OR document.revision<>1 OR line_count<>1 OR permit.id IS NULL OR execution.authorization_id IS NULL
        OR outcome.authorization_id IS NULL OR operation.id IS NULL OR movement.id IS NULL OR NOT permit.consumed
        OR (document.actor_id,document.customer_id,document.work_order_id,document.work_order_revision,document.plan_revision,
            document.use_revision,document.authority_epoch,document.cutover_epoch)
            IS DISTINCT FROM (permit.actor_id,permit.customer_id,permit.work_order_id,permit.expected_work_order_revision,
                permit.expected_plan_revision,execution.use_revision+1,permit.authority_epoch,permit.cutover_epoch)
        OR (operation.document_id,operation.document_revision,operation.namespace,operation.business_action,operation.resource_id)
            IS DISTINCT FROM (document.id,document.revision,'warehouse.deployment.consume'::varchar,'INSTALL'::varchar,permit.id)
        OR outcome.operation_id<>operation.id
        OR (movement.document_id,movement.document_revision,movement.operation_id,movement.kind,movement.state,
            movement.operation_namespace,movement.operation_key,movement.payload_hash,movement.actor_id)
            IS DISTINCT FROM (document.id,document.revision,operation.id,'DEPLOY'::varchar,'APPLIED'::varchar,
                operation.namespace,operation.operation_key,operation.payload_hash,operation.actor_id)
        OR (SELECT count(*) FROM inventory_operation WHERE tenant_id=scope AND inventory_operation.document_id=document.id)<>1
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND inventory_movement.document_id=document.id)<>1 THEN
        RAISE EXCEPTION 'DEPLOYMENT_DOCUMENT_LINEAGE' USING ERRCODE='23514';
    END IF;
    SELECT * INTO STRICT item FROM inventory_document_line WHERE tenant_id=scope AND inventory_document_line.document_id=document.id;
    IF (item.id,item.document_revision,item.revision,item.line_number,item.stock_identity_id,item.source_line_id,item.sku_id,
        item.quantity_base,item.base_unit,item.tracking,item.location_id,item.custodian_id,item.custodian_kind,item.condition,item.legal_owner)
        IS DISTINCT FROM (operation.id,0::bigint,0::bigint,1,permit.asset_id,permit.issue_line_id,
            (execution.source::jsonb#>>'{custody,skuId}')::uuid,1::bigint,'EA'::varchar,'SERIAL'::varchar,
            (execution.source::jsonb#>>'{custody,locationId}')::uuid,permit.actor_id,'TECHNICIAN'::varchar,'SERVICEABLE'::varchar,'ISP'::varchar)
        OR item.lot_id IS NOT NULL OR item.accepted_base<>0 OR item.rejected_base<>0 OR item.missing_base<>0
        OR EXISTS(SELECT FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id<>item.id) THEN
        RAISE EXCEPTION 'DEPLOYMENT_DOCUMENT_LINE_IDENTITY' USING ERRCODE='23514';
    END IF;
    PERFORM warehouse_assert_deployment_facts(scope,operation.id);
END $$;

DO $$ DECLARE definition text; anchor text:='binding:=execution.binding::jsonb; source:=execution.source::jsonb;';
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_result(uuid,uuid)'::regprocedure);
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'expected deployment result entry missing'; END IF;
    EXECUTE replace(definition,anchor,$body$
    PERFORM warehouse_assert_deployment_document(scope,permit.operation_id);
    IF EXISTS(SELECT FROM inventory_document other JOIN inventory_document_line line ON line.tenant_id=other.tenant_id AND line.document_id=other.id
        WHERE other.tenant_id=scope AND other.kind='DEPLOYMENT' AND other.state='POSTED' AND other.id<>permit.operation_id
            AND line.stock_identity_id=permit.asset_id AND line.source_line_id=permit.issue_line_id) THEN
        RAISE EXCEPTION 'DEPLOYMENT_EXTRA_DOCUMENT' USING ERRCODE='23514';
    END IF;
    $body$||anchor);
END $$;

CREATE FUNCTION warehouse_deployment_document_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE snapshots jsonb[]; snapshot jsonb; scope uuid; target uuid; related uuid;
BEGIN
    IF TG_OP='INSERT' THEN snapshots:=ARRAY[to_jsonb(NEW)];
    ELSIF TG_OP='DELETE' THEN snapshots:=ARRAY[to_jsonb(OLD)];
    ELSE snapshots:=ARRAY[to_jsonb(OLD),to_jsonb(NEW)]; END IF;
    FOREACH snapshot IN ARRAY snapshots LOOP
        scope:=(snapshot->>'tenant_id')::uuid;
        PERFORM warehouse_assert_deferred_scope(scope);
        target:=CASE TG_TABLE_NAME
            WHEN 'inventory_document' THEN (snapshot->>'id')::uuid
            WHEN 'inventory_deployment_result' THEN (snapshot->>'operation_id')::uuid
            WHEN 'inventory_deployment_authorization' THEN (snapshot->>'operation_id')::uuid
            ELSE (snapshot->>'document_id')::uuid END;
        PERFORM warehouse_assert_deployment_document(scope,target);
        FOR related IN SELECT permit.id FROM inventory_deployment_authorization permit
            WHERE permit.tenant_id=scope AND (permit.operation_id=target OR EXISTS(
                SELECT FROM inventory_document_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
                WHERE line.tenant_id=scope AND document.id=target AND document.kind='DEPLOYMENT'
                    AND line.stock_identity_id=permit.asset_id AND line.source_line_id=permit.issue_line_id)) LOOP
            PERFORM warehouse_assert_deployment_result(scope,related);
        END LOOP;
    END LOOP;
    RETURN NULL;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_document','inventory_document_line','inventory_operation','inventory_movement',
        'inventory_deployment_result','inventory_deployment_authorization'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_deployment_document_final AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_deployment_document_final_guard()',table_name);
    END LOOP;
END $$;

CREATE OR REPLACE FUNCTION warehouse_read_deployment_authorization(scope uuid, authorization_id uuid)
RETURNS inventory_deployment_authorization LANGUAGE plpgsql AS $$
DECLARE permit inventory_deployment_authorization;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM warehouse_assert_deployment_authorization(scope,authorization_id);
    SELECT * INTO STRICT permit FROM inventory_deployment_authorization WHERE tenant_id=scope AND id=authorization_id;
    IF permit.consumed THEN PERFORM warehouse_assert_deployment_result(scope,permit.id); END IF;
    RETURN permit;
END $$;
