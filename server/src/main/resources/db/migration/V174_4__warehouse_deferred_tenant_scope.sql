CREATE FUNCTION warehouse_assert_deferred_scope(owner_tenant uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    IF NULLIF(current_setting('app.tenant_id',true),'')::uuid IS DISTINCT FROM owner_tenant THEN
        RAISE EXCEPTION 'deferred warehouse validation requires the row tenant scope'
            USING ERRCODE='23514',CONSTRAINT='warehouse_deferred_tenant_scope_ck';
    END IF;
END $$;

CREATE FUNCTION warehouse_deferred_scope_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION warehouse_check_lot_root_capacity() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE previous_tenant uuid; previous_lot uuid; current_lot uuid; target record;
    received bigint; allocated numeric;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_lot' THEN
        current_lot:=NEW.id;
    ELSE
        current_lot:=NEW.lot_id;
        IF TG_OP='UPDATE' THEN previous_tenant:=OLD.tenant_id; previous_lot:=OLD.lot_id; END IF;
    END IF;
    FOR target IN
        SELECT DISTINCT owner_tenant,lot_id
        FROM (VALUES (NEW.tenant_id,current_lot),(previous_tenant,previous_lot)) AS affected(owner_tenant,lot_id)
        WHERE lot_id IS NOT NULL ORDER BY owner_tenant,lot_id
    LOOP
        PERFORM warehouse_assert_deferred_scope(target.owner_tenant);
        SELECT received_quantity_base INTO received FROM inventory_lot
        WHERE tenant_id=target.owner_tenant AND id=target.lot_id FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'referenced warehouse lot is missing or invisible'
                USING ERRCODE='23514',CONSTRAINT='warehouse_lot_capacity_reference_ck';
        END IF;
        SELECT coalesce(sum(quantity_base::numeric),0) INTO allocated FROM inventory_segment
        WHERE tenant_id=target.owner_tenant AND lot_id=target.lot_id
          AND warehouse_admission='VERIFIED' AND parent_segment_id IS NULL;
        IF allocated>received THEN
            RAISE EXCEPTION 'verified root allocation exceeds received lot quantity'
                USING ERRCODE='23514',CONSTRAINT='warehouse_lot_root_capacity_ck';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_segment','inventory_lot','inventory_serialized_asset',
        'inventory_balance_projection','inventory_reservation','inventory_identity_claim','inventory_usage_snapshot'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_aaa_deferred_tenant_scope AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_deferred_scope_guard()',table_name);
    END LOOP;
END $$;
