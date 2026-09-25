-- Check the row's tenant before any RLS-filtered lookup can hide the request.
CREATE OR REPLACE FUNCTION warehouse_return_title_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE value jsonb; target uuid; scope uuid;
BEGIN
    value:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(value->>'tenant_id')::uuid;
    PERFORM warehouse_assert_deferred_scope(scope);
    target:=CASE WHEN TG_TABLE_NAME='inventory_document_line' THEN (value->>'document_id')::uuid ELSE (value->>'id')::uuid END;
    IF TG_TABLE_NAME='inventory_return_title_request' OR EXISTS(SELECT FROM inventory_return_title_request WHERE tenant_id=scope AND id=target)
        OR (TG_TABLE_NAME='inventory_document' AND value->>'kind'='RETURN_TITLE') THEN
        PERFORM warehouse_assert_return_title_request(scope,target);
    END IF;
    RETURN NULL;
END $$;
