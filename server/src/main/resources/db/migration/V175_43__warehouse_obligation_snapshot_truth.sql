CREATE FUNCTION warehouse_obligation_snapshot_insert_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE lifecycle inventory_material_lifecycle%ROWTYPE; expected record; body jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT lifecycle FROM inventory_material_lifecycle WHERE tenant_id=NEW.tenant_id AND id=NEW.lifecycle_id;
    SELECT * INTO expected FROM warehouse_material_obligation_totals(NEW.tenant_id,lifecycle.work_order_id)
        WHERE issue_line_id=NEW.issue_line_id AND stock_identity_id=NEW.stock_identity_id;
    IF NOT FOUND OR
        (NEW.issued_base,NEW.used_base,NEW.returned_base,NEW.transferred_base,NEW.accountable_base,NEW.transit_base,NEW.acknowledged_base,NEW.base_unit)
            IS DISTINCT FROM (expected.issued_base,expected.used_base,expected.returned_base,expected.transferred_base,expected.accountable_base,
                expected.transit_base,expected.acknowledged_base,expected.base_unit) OR NEW.disposed_base<>0 THEN
        RAISE EXCEPTION 'obligation snapshot must match exact current source quantities and identity' USING ERRCODE='23514';
    END IF;
    body:=jsonb_build_object('issueLineId',NEW.issue_line_id,'stockIdentityId',NEW.stock_identity_id,'baseUnit',NEW.base_unit,
        'issuedBase',NEW.issued_base::text,'usedBase',NEW.used_base::text,'returnedBase',NEW.returned_base::text,
        'transferredBase',NEW.transferred_base::text,'disposedBase',NEW.disposed_base::text,'stillAccountableBase',NEW.accountable_base::text,
        'transitBase',NEW.transit_base::text,'acknowledgedBase',NEW.acknowledged_base::text);
    IF NOT (lifecycle.body::jsonb->'lines' @> jsonb_build_array(body)) THEN
        RAISE EXCEPTION 'obligation response must match its quantitative snapshot' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_obligation_snapshot_truth BEFORE INSERT ON inventory_material_obligation_snapshot
    FOR EACH ROW EXECUTE FUNCTION warehouse_obligation_snapshot_insert_guard();

CREATE FUNCTION warehouse_assert_material_close(target_tenant uuid,target_work_order uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    IF (SELECT material_state FROM inventory_material_lifecycle WHERE tenant_id=target_tenant AND work_order_id=target_work_order
        ORDER BY revision DESC LIMIT 1)='CLOSED' AND (EXISTS (
        SELECT FROM warehouse_material_obligation_totals(target_tenant,target_work_order) WHERE accountable_base>0 OR returned_base>0) OR EXISTS (
        SELECT FROM inventory_reservation reservation JOIN inventory_document_line line ON line.tenant_id=reservation.tenant_id AND line.id=reservation.document_line_id
        JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
        WHERE reservation.tenant_id=target_tenant AND document.work_order_id=target_work_order
            AND reservation.reserved_unpicked_base+reservation.reserved_picked_base>0)) THEN
        RAISE EXCEPTION 'closed material settlement cannot retain or acquire obligations' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_material_close_final_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE work_order uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_reservation' THEN
        SELECT document.work_order_id INTO work_order FROM inventory_document_line line JOIN inventory_document document
            ON document.tenant_id=line.tenant_id AND document.id=line.document_id
            WHERE line.tenant_id=NEW.tenant_id AND line.id=NEW.document_line_id;
    ELSE work_order:=NEW.work_order_id;
    END IF;
    IF work_order IS NOT NULL THEN PERFORM warehouse_assert_material_close(NEW.tenant_id,work_order); END IF;
    RETURN NEW;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_material_lifecycle','inventory_document','inventory_reservation'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_material_close_final AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_close_final_guard()',table_name);
    END LOOP;
END $$;
