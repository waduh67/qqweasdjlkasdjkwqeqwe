CREATE FUNCTION warehouse_lot_capacity_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous_tenant uuid; previous_lot uuid;
BEGIN
    IF TG_OP='UPDATE' THEN
        previous_tenant:=OLD.tenant_id;
        previous_lot:=OLD.lot_id;
    END IF;
    IF NEW.lot_id IS NULL AND previous_lot IS NULL THEN RETURN NEW; END IF;
    IF current_setting('transaction_isolation') NOT IN ('read committed','read uncommitted') THEN
        RAISE EXCEPTION 'lot allocation requires a fresh READ COMMITTED transaction' USING ERRCODE='40001';
    END IF;
    PERFORM id FROM inventory_lot
    WHERE (tenant_id=NEW.tenant_id AND id=NEW.lot_id) OR (tenant_id=previous_tenant AND id=previous_lot)
    ORDER BY tenant_id,id FOR UPDATE;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_check_lot_root_capacity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous_tenant uuid; previous_lot uuid; current_lot uuid; target record;
    received bigint; allocated numeric;
BEGIN
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
        SELECT received_quantity_base INTO received FROM inventory_lot
        WHERE tenant_id=target.owner_tenant AND id=target.lot_id FOR UPDATE;
        IF NOT FOUND THEN CONTINUE; END IF;
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

CREATE INDEX inventory_segment_lot_roots_idx ON inventory_segment(tenant_id,lot_id) INCLUDE (quantity_base)
WHERE warehouse_admission='VERIFIED' AND parent_segment_id IS NULL;

CREATE TRIGGER warehouse_a_lot_capacity_fence BEFORE INSERT OR UPDATE ON inventory_segment
FOR EACH ROW EXECUTE FUNCTION warehouse_lot_capacity_fence();

CREATE CONSTRAINT TRIGGER warehouse_lot_root_capacity AFTER INSERT OR UPDATE ON inventory_segment
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_check_lot_root_capacity();

CREATE CONSTRAINT TRIGGER warehouse_received_root_capacity AFTER INSERT OR UPDATE ON inventory_lot
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_check_lot_root_capacity();
