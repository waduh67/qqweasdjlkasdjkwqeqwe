CREATE FUNCTION warehouse_assert_deployment_facts(scope uuid, deployment_operation uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    IF EXISTS(SELECT FROM inventory_customer_material_fact fact WHERE fact.tenant_id=scope AND fact.posting_id IN (
        SELECT movement.id FROM inventory_movement movement WHERE movement.tenant_id=scope AND movement.operation_id=deployment_operation
        UNION SELECT result.posting_id FROM inventory_deployment_result result WHERE result.tenant_id=scope AND result.operation_id=deployment_operation)) THEN
        RAISE EXCEPTION 'DEPLOYMENT_MATERIAL_FACTS_FORBIDDEN' USING ERRCODE='23514';
    END IF;
END $$;

DO $$ DECLARE definition text; anchor text:='binding:=execution.binding::jsonb; source:=execution.source::jsonb;';
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_result(uuid,uuid)'::regprocedure);
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'expected deployment result entry missing'; END IF;
    EXECUTE replace(definition,anchor,'PERFORM warehouse_assert_deployment_facts(scope,permit.operation_id); '||anchor);
END $$;

CREATE FUNCTION warehouse_deployment_fact_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE snapshots jsonb[]; snapshot jsonb; scope uuid; posting uuid; target uuid;
BEGIN
    IF TG_OP='INSERT' THEN snapshots:=ARRAY[to_jsonb(NEW)];
    ELSIF TG_OP='DELETE' THEN snapshots:=ARRAY[to_jsonb(OLD)];
    ELSE snapshots:=ARRAY[to_jsonb(OLD),to_jsonb(NEW)]; END IF;
    FOREACH snapshot IN ARRAY snapshots LOOP
        scope:=(snapshot->>'tenant_id')::uuid;
        posting:=(snapshot->>'posting_id')::uuid;
        PERFORM warehouse_assert_deferred_scope(scope);
        FOR target IN
            SELECT movement.operation_id FROM inventory_movement movement
                LEFT JOIN inventory_operation operation ON operation.tenant_id=movement.tenant_id AND operation.id=movement.operation_id
                WHERE movement.tenant_id=scope AND movement.id=posting
                    AND (movement.kind='DEPLOY' OR operation.namespace='warehouse.deployment.consume')
            UNION SELECT result.operation_id FROM inventory_deployment_result result WHERE result.tenant_id=scope AND result.posting_id=posting
        LOOP
            PERFORM warehouse_assert_deployment_facts(scope,target);
            PERFORM warehouse_assert_deployment_result(scope,permit.id) FROM inventory_deployment_authorization permit
                WHERE permit.tenant_id=scope AND permit.operation_id=target;
        END LOOP;
    END LOOP;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_deployment_fact_final AFTER INSERT OR UPDATE OR DELETE ON inventory_customer_material_fact
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_deployment_fact_final_guard();
