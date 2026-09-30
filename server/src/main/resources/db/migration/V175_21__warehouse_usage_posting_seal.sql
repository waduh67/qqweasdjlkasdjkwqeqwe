DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_material_usage(uuid,uuid)'::regprocedure);
    IF position('IF line_count=0 OR cardinality(usage.posting_ids)<>1 OR' IN definition)=0 OR
        position('AND movement.kind=''CONSUME'' AND movement.state=''APPLIED'') THEN' IN definition)=0 THEN
        RAISE EXCEPTION 'expected usage posting binding missing';
    END IF;
    definition:=replace(definition,'IF line_count=0 OR cardinality(usage.posting_ids)<>1 OR',
        'IF line_count=0 OR cardinality(usage.posting_ids)<>1 OR
        (SELECT count(*) FROM inventory_movement WHERE tenant_id=target_tenant AND operation_id=header.id)<>1 OR');
    definition:=replace(definition,'AND movement.kind=''CONSUME'' AND movement.state=''APPLIED'') THEN',
        'AND movement.kind=''CONSUME'' AND movement.state=''APPLIED'' AND movement.warehouse_admission=''VERIFIED''
            AND movement.operation_namespace=operation.namespace AND movement.operation_key=operation.operation_key
            AND movement.payload_hash=operation.payload_hash AND movement.actor_id=header.actor_id
            AND movement.server_received_at=header.recorded_at) THEN');
    EXECUTE definition;
END $$;

CREATE OR REPLACE FUNCTION warehouse_material_usage_bound_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_material_usage' THEN target:=NEW.id;
    ELSIF TG_TABLE_NAME='inventory_material_usage_line' THEN target:=NEW.usage_id;
    ELSIF TG_TABLE_NAME='inventory_customer_material_fact' THEN
        target:=NEW.usage_id;
        IF target IS NULL THEN
            SELECT movement.operation_id INTO target FROM inventory_movement movement JOIN inventory_operation operation
                ON operation.tenant_id=movement.tenant_id AND operation.id=movement.operation_id
                WHERE movement.tenant_id=NEW.tenant_id AND movement.id=NEW.posting_id AND operation.namespace='warehouse.material.use';
        END IF;
    ELSIF TG_TABLE_NAME='inventory_usage_snapshot' THEN
        IF NEW.material_mode='NONE' OR EXISTS (SELECT FROM inventory_material_usage WHERE tenant_id=NEW.tenant_id AND id=NEW.id) THEN target:=NEW.id; END IF;
    ELSIF TG_TABLE_NAME='inventory_operation' THEN
        IF NEW.namespace='warehouse.material.use' THEN target:=NEW.id; END IF;
    ELSIF TG_TABLE_NAME='inventory_document' THEN
        IF NEW.kind='USAGE' AND NEW.state<>'DRAFT' THEN target:=NEW.id; END IF;
    ELSIF TG_TABLE_NAME='inventory_movement' THEN
        SELECT id INTO target FROM inventory_operation WHERE tenant_id=NEW.tenant_id AND id=NEW.operation_id AND namespace='warehouse.material.use';
        IF target IS NULL AND NEW.kind='CONSUME' AND EXISTS (SELECT FROM inventory_movement_leg leg JOIN inventory_document_line line
            ON line.tenant_id=leg.tenant_id AND line.id=leg.document_line_id JOIN inventory_issue_line issue ON issue.tenant_id=line.tenant_id AND issue.id=line.source_line_id
            WHERE leg.tenant_id=NEW.tenant_id AND leg.movement_id=NEW.id) THEN target:=NEW.operation_id; END IF;
    ELSIF TG_TABLE_NAME='inventory_movement_leg' THEN
        SELECT movement.operation_id INTO target FROM inventory_movement movement JOIN inventory_operation operation
            ON operation.tenant_id=movement.tenant_id AND operation.id=movement.operation_id
            WHERE movement.tenant_id=NEW.tenant_id AND movement.id=NEW.movement_id AND
                (operation.namespace='warehouse.material.use' OR (movement.kind='CONSUME' AND EXISTS (
                    SELECT FROM inventory_document_line line JOIN inventory_issue_line issue ON issue.tenant_id=line.tenant_id AND issue.id=line.source_line_id
                    WHERE line.tenant_id=NEW.tenant_id AND line.id=NEW.document_line_id)));
    END IF;
    IF target IS NOT NULL THEN PERFORM warehouse_assert_material_usage(NEW.tenant_id,target); END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_usage_bound AFTER INSERT ON inventory_movement_leg
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_usage_bound_guard();
