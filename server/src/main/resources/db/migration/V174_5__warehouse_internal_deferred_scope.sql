CREATE OR REPLACE FUNCTION warehouse_asset_claim_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.warehouse_admission='VERIFIED' THEN
        IF NEW.canonical_serial IS DISTINCT FROM warehouse_canonical_serial(NEW.serial_number) OR
           NEW.canonical_mac IS DISTINCT FROM warehouse_canonical_mac(NEW.mac_address) THEN
            RAISE EXCEPTION 'canonical identity does not match raw identity' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS (SELECT FROM inventory_identity_claim WHERE tenant_id=NEW.tenant_id AND identity_type='SERIAL'
            AND canonical_value=NEW.canonical_serial AND state IN ('ADMITTED','RETIRED') AND admitted_asset_id=NEW.id) OR
           (NEW.mac_address IS NOT NULL AND (NEW.canonical_mac IS NULL OR NOT EXISTS (SELECT FROM inventory_identity_claim
            WHERE tenant_id=NEW.tenant_id AND identity_type='MAC' AND canonical_value=NEW.canonical_mac AND state IN ('ADMITTED','RETIRED') AND admitted_asset_id=NEW.id))) THEN
            RAISE EXCEPTION 'identity is not admitted to this asset' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION warehouse_segment_conservation() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target_id uuid; parent_quantity bigint; parent_state text; child_quantity numeric;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    FOREACH target_id IN ARRAY ARRAY[NEW.id,NEW.parent_segment_id] LOOP
        IF target_id IS NULL THEN CONTINUE; END IF;
        SELECT quantity_base,state INTO parent_quantity,parent_state FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=target_id;
        SELECT sum(quantity_base::numeric) INTO child_quantity FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND parent_segment_id=target_id;
        IF (parent_state='SPLIT' AND child_quantity IS DISTINCT FROM parent_quantity::numeric) OR
           (parent_state<>'SPLIT' AND child_quantity IS NOT NULL) THEN
            RAISE EXCEPTION 'segment split violates conservation' USING ERRCODE='23514';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION warehouse_origin_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target_sku uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.warehouse_admission<>'VERIFIED' THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND NEW.origin_document_line_id IS DISTINCT FROM OLD.origin_document_line_id THEN
        RAISE EXCEPTION 'warehouse origin is immutable' USING ERRCODE='23514';
    END IF;
    target_sku := CASE WHEN TG_TABLE_NAME='inventory_lot' THEN to_jsonb(NEW)->>'sku_id' ELSE to_jsonb(NEW)->>'warehouse_sku_id' END;
    PERFORM warehouse_assert_stock_origin(NEW.tenant_id,NEW.origin_document_line_id,target_sku,NEW.base_unit);
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION warehouse_usage_postings_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE posting uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    FOREACH posting IN ARRAY NEW.posting_ids LOOP
        IF posting IS NULL OR NOT EXISTS (SELECT FROM inventory_movement WHERE tenant_id=NEW.tenant_id AND id=posting AND state='APPLIED') THEN
            RAISE EXCEPTION 'usage posting is missing or belongs to another tenant' USING ERRCODE='23503';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION warehouse_stock_provenance_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE piece inventory_segment; balance inventory_balance_projection; reservation inventory_reservation;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_segment' THEN
        SELECT * INTO piece FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
        IF piece.warehouse_admission='VERIFIED' THEN
            PERFORM warehouse_assert_verified_segment(piece.tenant_id,piece.id,false,TG_OP='INSERT');
        END IF;
    ELSIF TG_TABLE_NAME='inventory_balance_projection' THEN
        SELECT * INTO balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
        IF balance.warehouse_admission='VERIFIED' THEN
            PERFORM warehouse_assert_verified_segment(balance.tenant_id,balance.stock_identity_id,balance.quantity_base>0);
        END IF;
    ELSE
        SELECT * INTO reservation FROM inventory_reservation WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
        IF FOUND THEN
            PERFORM warehouse_assert_verified_segment(reservation.tenant_id,reservation.stock_identity_id,
                reservation.state='OPEN' AND (reservation.reserved_unpicked_base>0 OR reservation.reserved_picked_base>0));
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION warehouse_source_provenance_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE identity_id uuid; old_asset uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_identity_claim' THEN
        IF TG_OP='UPDATE' THEN old_asset:=OLD.admitted_asset_id; END IF;
        FOR identity_id IN SELECT id FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND warehouse_admission='VERIFIED'
            AND (asset_id=NEW.admitted_asset_id OR asset_id=old_asset) ORDER BY id LOOP
            PERFORM warehouse_assert_verified_segment(NEW.tenant_id,identity_id,false);
        END LOOP;
    ELSIF TG_TABLE_NAME='inventory_serialized_asset' THEN
        FOR identity_id IN SELECT id FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND asset_id=NEW.id AND warehouse_admission='VERIFIED' ORDER BY id LOOP
            PERFORM warehouse_assert_verified_segment(NEW.tenant_id,identity_id,false);
        END LOOP;
    ELSE
        FOR identity_id IN SELECT id FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND lot_id=NEW.id AND warehouse_admission='VERIFIED' ORDER BY id LOOP
            PERFORM warehouse_assert_verified_segment(NEW.tenant_id,identity_id,false);
        END LOOP;
    END IF;
    RETURN NEW;
END $$;
