CREATE FUNCTION warehouse_rework_operation_insert_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE document inventory_document%ROWTYPE; target uuid; latest inventory_material_rework%ROWTYPE;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.business_action NOT IN ('RESERVE','PICK','DISPATCH','REPORT_USE','ACKNOWLEDGE') THEN RETURN NEW; END IF;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id;
    IF NOT FOUND OR document.work_order_id IS NULL THEN RETURN NEW; END IF;
    SELECT id INTO target FROM inventory_material_rework WHERE tenant_id=NEW.tenant_id
        AND work_order_id=document.work_order_id AND plan_revision=document.plan_revision;
    IF target IS NOT NULL THEN PERFORM warehouse_assert_rework_live(NEW.tenant_id,target); END IF;
    IF document.kind='USAGE' THEN
        SELECT * INTO latest FROM inventory_material_rework WHERE tenant_id=NEW.tenant_id AND work_order_id=document.work_order_id
            ORDER BY plan_revision DESC LIMIT 1;
        IF FOUND AND latest.plan_revision IS DISTINCT FROM document.plan_revision THEN
            RAISE EXCEPTION 'new usage must bind the current rework plan revision' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_rework_operation_insert BEFORE INSERT ON inventory_operation
    FOR EACH ROW EXECUTE FUNCTION warehouse_rework_operation_insert_guard();
