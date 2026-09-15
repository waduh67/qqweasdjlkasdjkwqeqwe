DO $$ DECLARE target regprocedure; definition text; entry integer;
BEGIN
    FOREACH target IN ARRAY ARRAY['warehouse_asset_handover_final_guard()'::regprocedure,'warehouse_title_final_guard()'::regprocedure,
        'warehouse_deployment_document_final_guard()'::regprocedure,'warehouse_deployment_fact_final_guard()'::regprocedure,
        'warehouse_deployment_final_guard()'::regprocedure,'warehouse_deployment_posting_final_guard()'::regprocedure] LOOP
        definition:=pg_get_functiondef(target);
        IF target IN ('warehouse_asset_handover_final_guard()'::regprocedure,'warehouse_title_final_guard()'::regprocedure,
            'warehouse_deployment_posting_final_guard()'::regprocedure) THEN
            definition:=replace(definition,'    PERFORM warehouse_assert_deferred_scope(scope);','');
        ELSIF target='warehouse_deployment_final_guard()'::regprocedure THEN
            definition:=replace(definition,'    PERFORM warehouse_assert_deferred_scope(target_scope);','');
        END IF;
        entry:=strpos(definition,E'BEGIN\n');
        IF entry=0 THEN RAISE EXCEPTION 'validator entry missing'; END IF;
        EXECUTE overlay(definition placing E'BEGIN\n    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP=''DELETE'' THEN OLD.tenant_id ELSE NEW.tenant_id END);\n'
            FROM entry FOR length(E'BEGIN\n'));
    END LOOP;
END $$;
