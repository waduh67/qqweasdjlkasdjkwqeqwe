CREATE OR REPLACE FUNCTION warehouse_reference_wo_source_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data jsonb; scope uuid; target uuid;
BEGIN
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(row_data->>'tenant_id')::uuid;
    target:=(row_data->>CASE WHEN TG_TABLE_NAME='work_order' THEN 'id' ELSE 'work_order_id' END)::uuid;
    IF TG_TABLE_NAME='work_order' AND TG_OP='INSERT' THEN
        PERFORM warehouse_assert_deferred_scope(scope);
    END IF;
    IF EXISTS(SELECT FROM work_order_reference WHERE tenant_id=scope AND id=target) THEN
        PERFORM warehouse_assert_reference_wo(scope,target,'WO');
    ELSIF TG_TABLE_NAME='work_order' AND TG_OP='INSERT' AND
        EXISTS(SELECT FROM work_order WHERE tenant_id=scope AND id=target) AND
        EXISTS(SELECT FROM inventory_tenant_cutover WHERE tenant_id=scope AND workflow_mode='REFERENCE') THEN
        RAISE EXCEPTION 'reference workflow requires a reference work order' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
