ALTER TABLE inventory_operation ADD CONSTRAINT warehouse_operation_binding_complete CHECK (
    (master_kind IS NULL AND document_id IS NOT NULL) OR
    (master_kind IS NOT NULL AND document_id IS NULL));

CREATE OR REPLACE FUNCTION warehouse_active_master_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE reference_id uuid; reference_state text; old_reference uuid;
BEGIN
    IF TG_TABLE_NAME IN ('inventory_balance_projection','inventory_movement_leg') AND
        to_jsonb(NEW)->>'warehouse_admission'='LEGACY_UNRESOLVED' THEN RETURN NEW; END IF;
    reference_id := (to_jsonb(NEW)->>TG_ARGV[1])::uuid;
    IF reference_id IS NULL THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' THEN
        old_reference := (to_jsonb(OLD)->>TG_ARGV[1])::uuid;
        IF old_reference IS NOT DISTINCT FROM reference_id THEN RETURN NEW; END IF;
    END IF;
    EXECUTE format('SELECT state FROM %I WHERE tenant_id=$1 AND id=$2 FOR SHARE', TG_ARGV[0])
        INTO reference_state USING NEW.tenant_id,reference_id;
    IF reference_state IS NULL THEN
        RAISE EXCEPTION 'master reference is inaccessible' USING ERRCODE='23503';
    ELSIF reference_state <> 'ACTIVE' THEN
        RAISE EXCEPTION 'master reference is archived' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_master_operation_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE master_id uuid;
BEGIN
    IF NEW.master_kind IS NULL THEN RETURN NEW; END IF;
    EXECUTE format('SELECT id FROM %I WHERE tenant_id=$1 AND id=$2 FOR KEY SHARE','inventory_'||lower(NEW.master_kind))
        INTO master_id USING NEW.tenant_id,NEW.resource_id;
    IF master_id IS NULL THEN RAISE EXCEPTION 'master operation has no resource' USING ERRCODE='23503'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_master_operation_reference BEFORE INSERT ON inventory_operation
    FOR EACH ROW EXECUTE FUNCTION warehouse_master_operation_reference();

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_sku','inventory_supplier','inventory_location'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_master_no_delete BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_balance_projection','inventory_movement_leg'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_active_sku_id BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_active_master_reference(''inventory_sku'',''sku_id'')',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_active_location_id BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_active_master_reference(''inventory_location'',''location_id'')',table_name);
    END LOOP;
END $$;
