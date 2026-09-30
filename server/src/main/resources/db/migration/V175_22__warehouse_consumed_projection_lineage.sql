CREATE INDEX inventory_consumed_history_identity_idx ON inventory_movement_leg(tenant_id,stock_identity_id,movement_id)
    WHERE direction='IN' AND status='CONSUMED' AND warehouse_admission='VERIFIED';
CREATE INDEX inventory_consumed_projection_identity_idx ON inventory_balance_projection(tenant_id,stock_identity_id);

CREATE FUNCTION warehouse_assert_consumed_identity(target_tenant uuid,target_identity uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE piece inventory_segment%ROWTYPE; expected record; consumed_total numeric; position_count bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO piece FROM inventory_segment WHERE tenant_id=target_tenant AND id=target_identity FOR SHARE;
    IF NOT FOUND OR piece.state<>'ACTIVE' OR piece.warehouse_admission<>'VERIFIED' OR
        EXISTS (SELECT FROM inventory_segment WHERE tenant_id=target_tenant AND parent_segment_id=target_identity) THEN
        RAISE EXCEPTION 'consumed usage requires its original terminal identity without descendants' USING ERRCODE='23514';
    END IF;
    SELECT sum(leg.quantity_base::numeric) INTO consumed_total FROM inventory_movement_leg leg JOIN inventory_movement movement
        ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
        WHERE leg.tenant_id=target_tenant AND leg.stock_identity_id=target_identity AND leg.direction='IN' AND leg.status='CONSUMED'
            AND leg.warehouse_admission='VERIFIED' AND movement.warehouse_admission='VERIFIED' AND movement.kind='CONSUME' AND movement.state='APPLIED';
    IF consumed_total IS NULL OR consumed_total<=0 OR consumed_total>piece.quantity_base OR
        (piece.base_unit='MM' AND consumed_total<>piece.quantity_base) OR EXISTS (
            SELECT FROM inventory_movement_leg leg JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            WHERE leg.tenant_id=target_tenant AND leg.stock_identity_id=target_identity AND leg.direction='OUT' AND leg.status='CONSUMED'
                AND leg.warehouse_admission='VERIFIED' AND movement.state='APPLIED') THEN
        RAISE EXCEPTION 'consumed usage requires exact irreversible verified posting history' USING ERRCODE='23514';
    END IF;
    FOR expected IN SELECT leg.sku_id,leg.lot_id,leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,
            leg.condition,leg.legal_owner,leg.base_unit,bool_or(leg.serialized) serialized,sum(leg.quantity_base::numeric) quantity_base
        FROM inventory_movement_leg leg JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
        WHERE leg.tenant_id=target_tenant AND leg.stock_identity_id=target_identity AND leg.direction='IN' AND leg.status='CONSUMED'
            AND leg.warehouse_admission='VERIFIED' AND movement.warehouse_admission='VERIFIED' AND movement.kind='CONSUME' AND movement.state='APPLIED'
        GROUP BY leg.sku_id,leg.lot_id,leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,leg.condition,leg.legal_owner,leg.base_unit LOOP
        SELECT count(*) INTO position_count FROM inventory_balance_projection balance
            WHERE balance.tenant_id=target_tenant AND balance.stock_identity_id=target_identity AND balance.item_id=target_identity
                AND balance.warehouse_admission='VERIFIED' AND balance.status='CONSUMED' AND balance.quantity_base=expected.quantity_base
                AND balance.lot_id IS NOT DISTINCT FROM expected.lot_id AND
                (balance.sku_id,balance.location_id,balance.custody_owner_id,balance.custody_owner_kind,balance.condition,balance.legal_owner,balance.base_unit,balance.serialized)
                =(expected.sku_id,expected.location_id,expected.custody_owner_id,expected.custody_owner_kind,expected.condition,expected.legal_owner,expected.base_unit,expected.serialized);
        IF position_count<>1 THEN
            RAISE EXCEPTION 'consumed usage requires exactly one final projection matching immutable posting dimensions and quantity' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF (SELECT coalesce(sum(quantity_base::numeric),0) FROM inventory_balance_projection
            WHERE tenant_id=target_tenant AND stock_identity_id=target_identity AND quantity_base>0)>piece.quantity_base OR
        (SELECT coalesce(sum(quantity_base::numeric),0) FROM inventory_balance_projection
            WHERE tenant_id=target_tenant AND stock_identity_id=target_identity AND status='CONSUMED')<>consumed_total OR
        (piece.base_unit='MM' AND EXISTS (SELECT FROM inventory_balance_projection
            WHERE tenant_id=target_tenant AND stock_identity_id=target_identity AND quantity_base>0 AND status<>'CONSUMED')) THEN
        RAISE EXCEPTION 'consumed usage identity cannot acquire additional or reusable positions' USING ERRCODE='23514';
    END IF;
END $$;

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_material_usage(uuid,uuid)'::regprocedure);
    IF position('        IF split_required THEN' IN definition)=0 THEN
        RAISE EXCEPTION 'expected immutable usage line validation missing';
    END IF;
    EXECUTE replace(definition,'        IF split_required THEN',
        '        PERFORM warehouse_assert_consumed_identity(target_tenant,item.consumed_identity_id);
        IF split_required THEN');
END $$;

CREATE FUNCTION warehouse_consumed_truth_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target_tenant uuid; affected uuid[]; target_identity uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    target_tenant:=CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    IF TG_TABLE_NAME='inventory_segment' THEN
        affected:=CASE WHEN TG_OP='DELETE' THEN ARRAY[OLD.id,OLD.parent_segment_id]
            WHEN TG_OP='INSERT' THEN ARRAY[NEW.id,NEW.parent_segment_id]
            ELSE ARRAY[OLD.id,OLD.parent_segment_id,NEW.id,NEW.parent_segment_id] END;
    ELSIF TG_TABLE_NAME='inventory_movement' THEN
        SELECT array_agg(stock_identity_id) INTO affected FROM inventory_movement_leg
            WHERE tenant_id=target_tenant AND movement_id=(CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END);
    ELSE
        affected:=CASE WHEN TG_OP='DELETE' THEN ARRAY[OLD.stock_identity_id]
            WHEN TG_OP='INSERT' THEN ARRAY[NEW.stock_identity_id] ELSE ARRAY[OLD.stock_identity_id,NEW.stock_identity_id] END;
    END IF;
    FOR target_identity IN WITH RECURSIVE ancestry(id) AS (
            SELECT unnest(affected)
            UNION SELECT segment.parent_segment_id FROM inventory_segment segment JOIN ancestry ON segment.id=ancestry.id
                WHERE segment.tenant_id=target_tenant AND segment.parent_segment_id IS NOT NULL)
        SELECT DISTINCT leg.stock_identity_id FROM ancestry JOIN inventory_movement_leg leg ON leg.stock_identity_id=ancestry.id
            JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            WHERE leg.tenant_id=target_tenant AND leg.direction='IN' AND leg.status='CONSUMED' AND leg.warehouse_admission='VERIFIED'
                AND movement.warehouse_admission='VERIFIED' AND movement.kind='CONSUME' AND movement.state='APPLIED'
            ORDER BY leg.stock_identity_id LOOP
        PERFORM warehouse_assert_consumed_identity(target_tenant,target_identity);
    END LOOP;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_balance_projection','inventory_segment','inventory_movement','inventory_movement_leg'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_consumed_truth_bound AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_consumed_truth_guard()',table_name);
    END LOOP;
END $$;
