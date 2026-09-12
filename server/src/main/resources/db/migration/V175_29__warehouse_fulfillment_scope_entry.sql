DO $$ DECLARE definition text; previous text;
BEGIN
    definition:=pg_get_functiondef('warehouse_fulfillment_bound_guard()'::regprocedure);
    previous:=E'target_tenant:=CASE WHEN TG_OP=''DELETE'' THEN OLD.tenant_id ELSE NEW.tenant_id END;\n    PERFORM warehouse_assert_deferred_scope(target_tenant);';
    IF position(previous IN definition)=0 THEN RAISE EXCEPTION 'expected fulfillment tenant entry missing'; END IF;
    EXECUTE replace(definition,previous,E'PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP=''DELETE'' THEN OLD.tenant_id ELSE NEW.tenant_id END);\n    target_tenant:=CASE WHEN TG_OP=''DELETE'' THEN OLD.tenant_id ELSE NEW.tenant_id END;');
END $$;
