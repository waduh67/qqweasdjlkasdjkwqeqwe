-- A real serialized installation is a material source without a bulk usage row.
ALTER TABLE fulfillment_approval_snapshot ALTER COLUMN usage_id DROP NOT NULL;
ALTER TABLE inventory_material_settlement ALTER COLUMN usage_id DROP NOT NULL;
ALTER TABLE fulfillment_approval_snapshot ADD CONSTRAINT fulfillment_material_source_shape
    CHECK (usage_id IS NOT NULL OR material_mode='MATERIAL_REQUIRED');
ALTER TABLE inventory_material_settlement ADD CONSTRAINT settlement_material_source_shape
    CHECK (usage_id IS NOT NULL OR material_mode='MATERIAL_REQUIRED');

-- Reconstruct an immutable installation witness, including a legitimately ended
-- episode. New approvals separately require the original active-source fence.
CREATE FUNCTION warehouse_material_deployment_witness(scope uuid, permit_id uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE entry record; line jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT permit.id authorization_id,permit.asset_id,permit.issue_line_id,permit.actor_id,permit.work_order_id,
        execution.plan_id,execution.receipt_id,outcome.assignment_id,outcome.posting_id,outcome.use_revision
        INTO STRICT entry
        FROM inventory_deployment_authorization permit
        JOIN inventory_deployment_execution execution ON execution.tenant_id=permit.tenant_id AND execution.authorization_id=permit.id
        JOIN inventory_deployment_result outcome ON outcome.tenant_id=permit.tenant_id AND outcome.authorization_id=permit.id
        JOIN inventory_asset_assignment assignment ON assignment.tenant_id=outcome.tenant_id AND assignment.id=outcome.assignment_id
        WHERE permit.tenant_id=scope AND permit.id=permit_id AND permit.consumed AND permit.purpose IN ('INSTALL','REPLACE')
        FOR SHARE OF assignment;
    PERFORM warehouse_assert_deployment_result(scope,permit_id);
    SELECT issued INTO STRICT line FROM inventory_material_receipt receipt,
        LATERAL jsonb_array_elements(receipt.snapshot::jsonb#>'{issue,lines}') issued
        WHERE receipt.tenant_id=scope AND receipt.id=entry.receipt_id AND issued->>'id'=entry.issue_line_id::text;
    IF line#>>'{sku,tracking}' IS DISTINCT FROM 'SERIAL' OR line->>'baseUnit' IS DISTINCT FROM 'EA'
        OR NOT EXISTS(SELECT FROM inventory_material_plan_line planned
            JOIN inventory_material_plan plan ON plan.tenant_id=planned.tenant_id AND plan.id=planned.plan_id
            WHERE planned.tenant_id=scope AND planned.id=(line->>'planLineId')::uuid AND planned.plan_id=entry.plan_id
                AND plan.work_order_id=entry.work_order_id AND planned.base_unit='EA')
        OR NOT EXISTS(SELECT FROM inventory_serialized_asset asset
            WHERE asset.tenant_id=scope AND asset.id=entry.asset_id AND asset.warehouse_admission='VERIFIED') THEN
        RAISE EXCEPTION 'material witness requires its verified installation and plan line' USING ERRCODE='23514';
    END IF;
    RETURN jsonb_build_object('authorizationId',entry.authorization_id,'assignmentId',entry.assignment_id,
        'assetId',entry.asset_id,'issueLineId',entry.issue_line_id,'planLineId',line->>'planLineId',
        'postingId',entry.posting_id,'useRevision',entry.use_revision,'actorId',entry.actor_id);
END $$;

CREATE FUNCTION warehouse_assert_deployment_only_settlement(scope uuid, approval_id uuid, live boolean) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE frozen fulfillment_approval_snapshot%ROWTYPE; material jsonb; plan_body jsonb; witnessed jsonb; entry jsonb;
    actual jsonb; expected_lines uuid[]; reported_lines uuid[]; witnessed_revision bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT frozen FROM fulfillment_approval_snapshot WHERE tenant_id=scope AND id=approval_id;
    IF frozen.usage_id IS NOT NULL THEN RETURN; END IF;
    material:=frozen.snapshot::jsonb->'material';
    witnessed:=material->'deployments';
    SELECT snapshot::jsonb INTO STRICT plan_body FROM inventory_material_plan_snapshot WHERE tenant_id=scope AND id=frozen.plan_id;
    IF frozen.material_mode<>'MATERIAL_REQUIRED' OR material->>'usageId' IS NOT NULL
        OR material->>'usageBody' IS NOT NULL OR material->>'usageHash' IS NOT NULL
        OR jsonb_typeof(witnessed) IS DISTINCT FROM 'array' OR jsonb_array_length(witnessed)=0
        OR jsonb_typeof(plan_body->'lines') IS DISTINCT FROM 'array'
        OR jsonb_array_length(plan_body->'lines')=0
        OR EXISTS(SELECT FROM jsonb_array_elements(plan_body->'lines') planned WHERE planned#>>'{sku,tracking}' IS DISTINCT FROM 'SERIAL')
        OR jsonb_array_length(witnessed)<>(SELECT count(DISTINCT value->>'authorizationId') FROM jsonb_array_elements(witnessed)) THEN
        RAISE EXCEPTION 'deployment-only settlement requires nonempty serialized material sources' USING ERRCODE='23514';
    END IF;
    SELECT array_agg(id ORDER BY id) INTO expected_lines FROM inventory_material_plan_line WHERE tenant_id=scope AND plan_id=frozen.plan_id;
    SELECT array_agg(DISTINCT (value->>'planLineId')::uuid ORDER BY (value->>'planLineId')::uuid),max((value->>'useRevision')::bigint)
        INTO reported_lines,witnessed_revision FROM jsonb_array_elements(witnessed);
    IF expected_lines IS NULL OR reported_lines IS DISTINCT FROM expected_lines OR witnessed_revision IS DISTINCT FROM frozen.use_revision THEN
        RAISE EXCEPTION 'deployment-only settlement must cover its exact plan and revision' USING ERRCODE='23514';
    END IF;
    FOR entry IN SELECT value FROM jsonb_array_elements(witnessed) ORDER BY value->>'assignmentId' LOOP
        actual:=warehouse_material_deployment_witness(scope,(entry->>'authorizationId')::uuid);
        IF actual IS DISTINCT FROM entry OR NOT EXISTS(SELECT FROM inventory_deployment_authorization
            WHERE tenant_id=scope AND id=(entry->>'authorizationId')::uuid AND work_order_id=frozen.work_order_id) THEN
            RAISE EXCEPTION 'deployment-only settlement witness is not its original installation' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF live THEN
        IF EXISTS(SELECT FROM inventory_usage_snapshot WHERE tenant_id=scope AND work_order_id=frozen.work_order_id)
            OR witnessed IS DISTINCT FROM warehouse_material_deployment_sources(scope,frozen.work_order_id,ARRAY[frozen.plan_id])
            OR EXISTS(SELECT FROM jsonb_array_elements(witnessed) deployment WHERE NOT EXISTS(
                SELECT FROM work_order_assignee WHERE tenant_id=scope AND work_order_id=frozen.work_order_id
                    AND technician_id=(deployment->>'actorId')::uuid)) THEN
            RAISE EXCEPTION 'deployment-only settlement source is stale or omits actual usage' USING ERRCODE='23514';
        END IF;
    END IF;
END $$;

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_fulfillment_snapshot(uuid,uuid,boolean)'::regprocedure);
    IF strpos(definition,'SELECT * INTO STRICT usage FROM inventory_usage_snapshot WHERE tenant_id=target_tenant AND id=frozen.usage_id;')=0
        OR strpos(definition,'usage.work_order_id<>frozen.work_order_id OR usage.plan_id<>plan.id OR usage.use_revision<>frozen.use_revision OR')=0
        OR strpos(definition,'usage.material_mode<>frozen.material_mode OR plan.material_mode<>frozen.material_mode')=0
        OR strpos(definition,'NOT EXISTS (SELECT FROM inventory_material_usage use_header JOIN work_order_assignee assigned')=0
        OR strpos(definition,'WHERE use_header.tenant_id=target_tenant AND use_header.id=usage.id) THEN')=0
        OR strpos(definition,'PERFORM warehouse_assert_material_usage(target_tenant,usage.id);')=0
        OR strpos(definition,'payload:=string_to_array(frozen.request_payload,')=0 THEN
        RAISE EXCEPTION 'expected frozen usage and actor bindings missing';
    END IF;
    definition:=replace(definition,
        'SELECT * INTO STRICT usage FROM inventory_usage_snapshot WHERE tenant_id=target_tenant AND id=frozen.usage_id;',
        'IF frozen.usage_id IS NOT NULL THEN SELECT * INTO STRICT usage FROM inventory_usage_snapshot WHERE tenant_id=target_tenant AND id=frozen.usage_id; END IF;');
    definition:=replace(definition,
        'usage.work_order_id<>frozen.work_order_id OR usage.plan_id<>plan.id OR usage.use_revision<>frozen.use_revision OR',
        '(frozen.usage_id IS NOT NULL AND (usage.work_order_id<>frozen.work_order_id OR usage.plan_id<>plan.id OR usage.use_revision<>frozen.use_revision OR');
    definition:=replace(definition,'usage.material_mode<>frozen.material_mode OR plan.material_mode<>frozen.material_mode',
        'usage.material_mode<>frozen.material_mode)) OR plan.material_mode<>frozen.material_mode');
    definition:=replace(definition,'NOT EXISTS (SELECT FROM inventory_material_usage use_header JOIN work_order_assignee assigned',
        '(frozen.usage_id IS NOT NULL AND NOT EXISTS (SELECT FROM inventory_material_usage use_header JOIN work_order_assignee assigned');
    definition:=replace(definition,'WHERE use_header.tenant_id=target_tenant AND use_header.id=usage.id) THEN',
        'WHERE use_header.tenant_id=target_tenant AND use_header.id=usage.id)) THEN');
    definition:=replace(definition,'PERFORM warehouse_assert_material_usage(target_tenant,usage.id);',
        'IF frozen.usage_id IS NOT NULL THEN PERFORM warehouse_assert_material_usage(target_tenant,usage.id); END IF;');
    definition:=replace(definition,'payload:=string_to_array(frozen.request_payload,',
        E'PERFORM warehouse_assert_deployment_only_settlement(target_tenant,target_id,live);\n    payload:=string_to_array(frozen.request_payload,');
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_fulfillment_completion(uuid,uuid)'::regprocedure);
    IF strpos(definition,'receipt.usage_id<>frozen.usage_id')=0 THEN
        RAISE EXCEPTION 'expected completion usage binding missing';
    END IF;
    EXECUTE replace(definition,'receipt.usage_id<>frozen.usage_id','receipt.usage_id IS DISTINCT FROM frozen.usage_id');
END $$;
