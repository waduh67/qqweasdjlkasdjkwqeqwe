-- QA must bind already-posted serialized deployments alongside measured bulk use.
-- This is a read witness; it never creates an asset, a usage line, or a stock movement.
CREATE FUNCTION warehouse_material_deployment_sources(scope uuid, job uuid, plans uuid[]) RETURNS jsonb
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE entry record; line jsonb; witness jsonb; result jsonb := '[]'::jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    FOR entry IN
        SELECT permit.id authorization_id,permit.asset_id,permit.issue_line_id,permit.actor_id,
            permit.customer_id,execution.plan_id,execution.receipt_id,outcome.assignment_id,outcome.posting_id,outcome.use_revision
        FROM inventory_deployment_authorization permit
        JOIN inventory_deployment_execution execution ON execution.tenant_id=permit.tenant_id AND execution.authorization_id=permit.id
        JOIN inventory_deployment_result outcome ON outcome.tenant_id=permit.tenant_id AND outcome.authorization_id=permit.id
        JOIN inventory_asset_assignment assignment ON assignment.tenant_id=outcome.tenant_id AND assignment.id=outcome.assignment_id
        WHERE permit.tenant_id=scope AND permit.work_order_id=job AND execution.plan_id=ANY(plans)
            AND permit.consumed AND permit.purpose IN ('INSTALL','REPLACE') AND assignment.ended_at IS NULL
            AND assignment.warehouse_admission='VERIFIED'
        ORDER BY permit.id
    LOOP
        PERFORM warehouse_assert_deployment_result(scope,entry.authorization_id);
        SELECT issued INTO STRICT line FROM inventory_material_receipt receipt,
            LATERAL jsonb_array_elements(receipt.snapshot::jsonb#>'{issue,lines}') issued
            WHERE receipt.tenant_id=scope AND receipt.id=entry.receipt_id AND issued->>'id'=entry.issue_line_id::text;
        IF line#>>'{sku,tracking}' IS DISTINCT FROM 'SERIAL' OR line->>'baseUnit' IS DISTINCT FROM 'EA'
            OR NOT EXISTS(SELECT FROM inventory_material_plan_line planned
                JOIN inventory_material_plan plan ON plan.tenant_id=planned.tenant_id AND plan.id=planned.plan_id
                WHERE planned.tenant_id=scope AND planned.id=(line->>'planLineId')::uuid AND planned.plan_id=entry.plan_id
                    AND plan.work_order_id=job AND planned.base_unit='EA')
            OR NOT EXISTS(SELECT FROM inventory_serialized_asset asset
                WHERE asset.tenant_id=scope AND asset.id=entry.asset_id AND asset.warehouse_admission='VERIFIED'
                    AND asset.status='CUSTOMER_INSTALLED') THEN
            RAISE EXCEPTION 'serialized settlement requires its verified deployment and plan line' USING ERRCODE='23514';
        END IF;
        witness := jsonb_build_object('authorizationId',entry.authorization_id,'assignmentId',entry.assignment_id,
            'assetId',entry.asset_id,'issueLineId',entry.issue_line_id,'planLineId',line->>'planLineId',
            'postingId',entry.posting_id,'useRevision',entry.use_revision,'actorId',entry.actor_id);
        result := result || jsonb_build_array(witness);
    END LOOP;
    RETURN result;
END $$;

CREATE FUNCTION warehouse_fulfillment_deployments_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE material jsonb; plans uuid[]; actual jsonb; expected_lines uuid[]; reported_lines uuid[];
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    material:=NEW.snapshot::jsonb->'material';
    WITH RECURSIVE lineage(id) AS (
        SELECT NEW.plan_id UNION ALL SELECT rework.previous_plan_id FROM inventory_material_rework rework
            JOIN lineage ON lineage.id=rework.id WHERE rework.tenant_id=NEW.tenant_id)
        SELECT array_agg(id ORDER BY id) INTO plans FROM lineage;
    actual:=warehouse_material_deployment_sources(NEW.tenant_id,NEW.work_order_id,plans);
    IF coalesce(material->'deployments','[]'::jsonb) IS DISTINCT FROM actual THEN
        RAISE EXCEPTION 'fulfillment deployment witnesses do not match posted installations' USING ERRCODE='23514';
    END IF;
    IF EXISTS(SELECT FROM jsonb_array_elements(actual) deployment
        WHERE NOT (coalesce(NEW.snapshot::jsonb#>'{workOrder,material,activeAssigneeIds}','[]'::jsonb) ? (deployment->>'actorId'))) THEN
        RAISE EXCEPTION 'fulfillment deployment actor is not an assigned technician' USING ERRCODE='23514';
    END IF;
    IF NEW.material_mode='MATERIAL_REQUIRED' THEN
        SELECT array_agg(DISTINCT id ORDER BY id) INTO expected_lines FROM (
            SELECT id FROM inventory_material_plan_line WHERE tenant_id=NEW.tenant_id AND plan_id=NEW.plan_id
            UNION SELECT plan_line_id FROM inventory_material_rework_inherited_line WHERE tenant_id=NEW.tenant_id AND rework_id=NEW.plan_id
        ) expected;
        SELECT array_agg(DISTINCT id ORDER BY id) INTO reported_lines FROM (
            SELECT line.plan_line_id id FROM inventory_material_usage_line line
                JOIN inventory_usage_snapshot usage ON usage.tenant_id=line.tenant_id AND usage.id=line.usage_id
                WHERE line.tenant_id=NEW.tenant_id AND usage.work_order_id=NEW.work_order_id AND usage.plan_id=ANY(plans)
            UNION SELECT (entry->>'planLineId')::uuid FROM jsonb_array_elements(actual) entry
        ) reported;
        IF expected_lines IS NULL OR reported_lines IS DISTINCT FROM expected_lines THEN
            RAISE EXCEPTION 'fulfillment must witness every planned material line' USING ERRCODE='23514';
        END IF;
    ELSIF actual<>'[]'::jsonb THEN
        RAISE EXCEPTION 'no-material fulfillment cannot hide a serialized deployment' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_fulfillment_deployments AFTER INSERT ON fulfillment_approval_snapshot
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_deployments_guard();
