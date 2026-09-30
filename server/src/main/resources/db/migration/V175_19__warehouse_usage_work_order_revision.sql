DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_material_usage(uuid,uuid)'::regprocedure);
    IF position('plan.work_order_revision<>usage.work_order_revision' IN definition)=0 THEN
        RAISE EXCEPTION 'expected source plan revision binding missing';
    END IF;
    EXECUTE replace(definition,'plan.work_order_revision<>usage.work_order_revision','plan.work_order_revision>usage.work_order_revision');
END $$;
