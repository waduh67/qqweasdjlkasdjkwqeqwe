DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_result(uuid,uuid)'::regprocedure);
    IF strpos(definition,'OR assignment.ended_at IS NOT NULL')=0 OR strpos(definition,'id=incoming.document_line_id AND source_line_id=permit.issue_line_id')=0 THEN
        RAISE EXCEPTION 'expected deployment binding clauses missing';
    END IF;
    definition:=replace(definition,'OR assignment.ended_at IS NOT NULL',
        'OR assignment.warehouse_admission<>''VERIFIED'' OR assignment.ended_at IS NOT NULL');
    definition:=replace(definition,'id=incoming.document_line_id AND source_line_id=permit.issue_line_id',
        'id=incoming.document_line_id AND source_line_id=permit.issue_line_id AND document_id=permit.operation_id');
    definition:=replace(definition,'SELECT * INTO incoming FROM inventory_movement_leg',
        $body$
    IF NOT EXISTS(SELECT FROM inventory_document WHERE tenant_id=scope AND id=permit.operation_id
        AND kind='DEPLOYMENT' AND state='POSTED' AND customer_id=permit.customer_id AND work_order_id=permit.work_order_id
        AND actor_id=permit.actor_id AND work_order_revision=permit.expected_work_order_revision AND plan_revision=permit.expected_plan_revision)
        OR (outcome.result::jsonb->>'operationId')::uuid IS DISTINCT FROM permit.operation_id
        OR (outcome.result::jsonb#>>'{assignment,assignmentId}')::uuid IS DISTINCT FROM assignment.id
        OR (outcome.result::jsonb#>>'{assignment,assetId}')::uuid IS DISTINCT FROM permit.asset_id
        OR (outcome.result::jsonb#>>'{assignment,customerId}')::uuid IS DISTINCT FROM permit.customer_id
        OR (outcome.result::jsonb#>>'{assignment,workOrderId}')::uuid IS DISTINCT FROM permit.work_order_id
        OR outcome.result::jsonb#>>'{assignment,legalOwner}' IS DISTINCT FROM 'ISP'
        OR (episode.response::jsonb->>'assignmentId')::uuid IS DISTINCT FROM assignment.id
        OR (episode.response::jsonb->>'assetId')::uuid IS DISTINCT FROM permit.asset_id
        OR (episode.response::jsonb->>'customerId')::uuid IS DISTINCT FROM permit.customer_id
        OR (episode.response::jsonb->>'onuId')::uuid IS DISTINCT FROM episode.onu_id THEN
        RAISE EXCEPTION 'DEPLOYMENT_RESULT_SNAPSHOT' USING ERRCODE='23514';
    END IF;
    SELECT * INTO incoming FROM inventory_movement_leg$body$);
    EXECUTE definition;
END $$;

CREATE FUNCTION warehouse_deployment_posting_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data jsonb; scope uuid; operation uuid; permit_id uuid;
BEGIN
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(row_data->>'tenant_id')::uuid;
    PERFORM warehouse_assert_deferred_scope(scope);
    operation:=CASE WHEN TG_TABLE_NAME='inventory_operation' THEN (row_data->>'id')::uuid ELSE (row_data->>'operation_id')::uuid END;
    SELECT permit.id INTO permit_id FROM inventory_deployment_authorization permit
        JOIN inventory_deployment_execution execution ON execution.tenant_id=permit.tenant_id AND execution.authorization_id=permit.id
        WHERE permit.tenant_id=scope AND permit.operation_id=operation;
    IF permit_id IS NOT NULL THEN
        PERFORM warehouse_assert_deployment_result(scope,permit_id);
    ELSIF coalesce(row_data->>'namespace',row_data->>'operation_namespace')='warehouse.deployment.consume' OR row_data->>'kind'='DEPLOY' THEN
        RAISE EXCEPTION 'DEPLOYMENT_OPERATION_REQUIRES_AUTHORIZATION' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_deployment_posting_final AFTER INSERT OR UPDATE OR DELETE ON inventory_operation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_deployment_posting_final_guard();
CREATE CONSTRAINT TRIGGER warehouse_deployment_posting_final AFTER INSERT OR UPDATE OR DELETE ON inventory_movement
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_deployment_posting_final_guard();
