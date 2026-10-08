CREATE FUNCTION warehouse_movement_workflow_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE workflow text; safety text;
BEGIN
    SELECT workflow_mode,state INTO workflow,safety FROM inventory_tenant_cutover
        WHERE tenant_id=NEW.tenant_id FOR SHARE;
    IF coalesce(workflow='REFERENCE',false) IS DISTINCT FROM
        starts_with(NEW.operation_namespace,'warehouse.reference.') OR
        (workflow='REFERENCE' AND safety<>'ENFORCED') THEN
        RAISE EXCEPTION 'stock writer does not match the tenant warehouse workflow' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_movement_workflow BEFORE INSERT ON inventory_movement
    FOR EACH ROW EXECUTE FUNCTION warehouse_movement_workflow_guard();
