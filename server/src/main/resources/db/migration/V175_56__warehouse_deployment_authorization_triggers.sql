CREATE FUNCTION warehouse_authorization_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data jsonb; target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    target:=CASE WHEN TG_TABLE_NAME='inventory_deployment_authorization' THEN (row_data->>'id')::uuid
        ELSE (row_data->>'authorization_id')::uuid END;
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'authorization history cannot be removed' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT FROM inventory_deployment_authorization WHERE tenant_id=NEW.tenant_id AND id=target AND warehouse_admission='VERIFIED') THEN
        PERFORM warehouse_assert_deployment_authorization(NEW.tenant_id,target);
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_zz_authorization_final AFTER INSERT OR UPDATE OR DELETE ON inventory_deployment_authorization
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_authorization_final_guard();
CREATE CONSTRAINT TRIGGER warehouse_zz_authorization_history_final AFTER INSERT OR UPDATE OR DELETE ON inventory_deployment_authorization_history
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_authorization_final_guard();

CREATE TRIGGER warehouse_authorization_asset_lock BEFORE INSERT OR UPDATE ON inventory_deployment_authorization
    FOR EACH ROW EXECUTE FUNCTION warehouse_episode_asset_lock();

CREATE FUNCTION warehouse_authorization_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE scope uuid; before_row jsonb; after_row jsonb; ids uuid[]; asset_ids uuid[]; target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    scope:=CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    before_row:=CASE WHEN TG_OP='INSERT' THEN '{}'::jsonb ELSE to_jsonb(OLD) END;
    after_row:=CASE WHEN TG_OP='DELETE' THEN '{}'::jsonb ELSE to_jsonb(NEW) END;
    ids:=ARRAY[(before_row->>'id')::uuid,(after_row->>'id')::uuid];
    asset_ids:=ARRAY[(before_row->>'asset_id')::uuid,(after_row->>'asset_id')::uuid,
        (before_row->>'admitted_asset_id')::uuid,(after_row->>'admitted_asset_id')::uuid];
    FOR target IN SELECT permit.id FROM inventory_deployment_authorization permit
        LEFT JOIN inventory_serialized_asset physical ON physical.tenant_id=permit.tenant_id AND physical.id=permit.asset_id
        LEFT JOIN inventory_document_line origin ON origin.tenant_id=physical.tenant_id AND origin.id=physical.origin_document_line_id
        LEFT JOIN inventory_issue_line issue ON issue.tenant_id=permit.tenant_id AND issue.id=permit.issue_line_id
        WHERE permit.tenant_id=scope AND permit.warehouse_admission='VERIFIED' AND NOT permit.consumed
            AND CASE TG_TABLE_NAME
                WHEN 'inventory_serialized_asset' THEN permit.asset_id=ANY(ids)
                WHEN 'inventory_identity_claim' THEN permit.asset_id=ANY(asset_ids)
                    OR (before_row->>'identity_type'='SERIAL' AND physical.canonical_serial=before_row->>'canonical_value')
                    OR (after_row->>'identity_type'='SERIAL' AND physical.canonical_serial=after_row->>'canonical_value')
                    OR (before_row->>'identity_type'='MAC' AND physical.canonical_mac=before_row->>'canonical_value')
                    OR (after_row->>'identity_type'='MAC' AND physical.canonical_mac=after_row->>'canonical_value')
                WHEN 'inventory_segment' THEN permit.asset_id=ANY(asset_ids)
                WHEN 'inventory_issue_line' THEN permit.issue_line_id=ANY(ids)
                WHEN 'inventory_document_line' THEN physical.origin_document_line_id=ANY(ids) OR permit.issue_line_id=ANY(ids)
                WHEN 'inventory_document' THEN origin.document_id=ANY(ids) OR issue.issue_id=ANY(ids)
                WHEN 'inventory_asset_assignment' THEN permit.previous_assignment_id=ANY(ids)
                WHEN 'inventory_material_receipt_line' THEN permit.issue_line_id=ANY(ARRAY[(before_row->>'issue_line_id')::uuid,(after_row->>'issue_line_id')::uuid])
                WHEN 'inventory_material_receipt' THEN issue.issue_id=ANY(ARRAY[(before_row->>'issue_id')::uuid,(after_row->>'issue_id')::uuid])
                ELSE false END
        ORDER BY permit.id LOOP
        PERFORM warehouse_assert_deployment_authorization(scope,target);
    END LOOP;
    RETURN NULL;
END $$;

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_serialized_asset','inventory_identity_claim','inventory_segment',
        'inventory_issue_line','inventory_document_line','inventory_document','inventory_asset_assignment',
        'inventory_material_receipt_line','inventory_material_receipt'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_authorization_source_final AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_authorization_source_guard()',table_name);
    END LOOP;
END $$;
