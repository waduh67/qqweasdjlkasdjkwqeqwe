CREATE FUNCTION warehouse_assert_opening_approval(owner_tenant uuid, document_id uuid, document_revision bigint)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'independent document/revision-bound opening approval is unavailable' USING ERRCODE='42501';
END $$;

CREATE FUNCTION warehouse_opening_approval_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.kind='OPENING_BALANCE' AND NEW.state IN ('APPROVED','POSTED','CLOSED') THEN
        PERFORM warehouse_assert_opening_approval(NEW.tenant_id,NEW.id,NEW.revision);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_opening_approval BEFORE INSERT OR UPDATE ON inventory_document
FOR EACH ROW EXECUTE FUNCTION warehouse_opening_approval_guard();

CREATE FUNCTION warehouse_assert_stock_origin(owner_tenant uuid, origin_line uuid, stock_sku uuid, stock_unit text)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE source_line inventory_document_line; source_document inventory_document;
BEGIN
    SELECT * INTO source_line FROM inventory_document_line WHERE tenant_id=owner_tenant AND id=origin_line FOR SHARE;
    IF NOT FOUND OR source_line.sku_id IS DISTINCT FROM stock_sku OR source_line.base_unit IS DISTINCT FROM stock_unit THEN
        RAISE EXCEPTION 'stock origin line is missing or mismatched' USING ERRCODE='23514';
    END IF;
    SELECT * INTO source_document FROM inventory_document WHERE tenant_id=owner_tenant AND id=source_line.document_id FOR SHARE;
    IF source_document.kind='OPENING_BALANCE' THEN
        PERFORM warehouse_assert_opening_approval(owner_tenant,source_document.id,source_document.revision);
        IF source_document.state NOT IN ('POSTED','CLOSED') THEN
            RAISE EXCEPTION 'opening origin is not posted' USING ERRCODE='23514';
        END IF;
    ELSIF source_document.kind IS DISTINCT FROM 'RECEIPT' OR source_document.state NOT IN ('RECEIVED_IN_INSPECTION','PUTAWAY','CLOSED') THEN
        RAISE EXCEPTION 'verified stock requires a trusted posted origin' USING ERRCODE='23514';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION warehouse_origin_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_sku uuid;
BEGIN
    IF NEW.warehouse_admission<>'VERIFIED' THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND NEW.origin_document_line_id IS DISTINCT FROM OLD.origin_document_line_id THEN
        RAISE EXCEPTION 'warehouse origin is immutable' USING ERRCODE='23514';
    END IF;
    target_sku := CASE WHEN TG_TABLE_NAME='inventory_lot' THEN to_jsonb(NEW)->>'sku_id' ELSE to_jsonb(NEW)->>'warehouse_sku_id' END;
    PERFORM warehouse_assert_stock_origin(NEW.tenant_id,NEW.origin_document_line_id,target_sku,NEW.base_unit);
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_assert_verified_segment(owner_tenant uuid, stock_identity uuid, spendable boolean, newly_created boolean DEFAULT false)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE piece inventory_segment; parent inventory_segment; asset inventory_serialized_asset; lot inventory_lot;
    visited uuid[] := '{}'; require_admitted boolean;
BEGIN
    SELECT * INTO piece FROM inventory_segment WHERE tenant_id=owner_tenant AND id=stock_identity FOR SHARE;
    IF NOT FOUND OR piece.warehouse_admission<>'VERIFIED' THEN
        RAISE EXCEPTION 'stock requires a VERIFIED segment' USING ERRCODE='23514';
    END IF;
    IF spendable AND piece.state<>'ACTIVE' THEN
        RAISE EXCEPTION 'terminal segment is not spendable' USING ERRCODE='23514';
    END IF;
    PERFORM id FROM inventory_sku WHERE tenant_id=owner_tenant AND id=piece.sku_id AND base_unit=piece.base_unit
        AND ((piece.kind='SERIAL' AND tracking='SERIAL') OR (piece.kind<>'SERIAL' AND tracking<>'SERIAL')) FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'segment tracking does not match SKU' USING ERRCODE='23514'; END IF;
    require_admitted := spendable OR newly_created OR piece.state='ACTIVE';
    IF piece.kind='SERIAL' THEN
        SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=owner_tenant AND id=piece.asset_id FOR SHARE;
        IF NOT FOUND OR asset.warehouse_admission<>'VERIFIED' OR asset.warehouse_sku_id IS DISTINCT FROM piece.sku_id
           OR asset.base_unit IS DISTINCT FROM piece.base_unit OR asset.quantity_base IS DISTINCT FROM piece.quantity_base
           OR piece.base_unit<>'EA' OR piece.quantity_base<>1 OR piece.parent_segment_id IS NOT NULL
           OR asset.canonical_serial IS DISTINCT FROM warehouse_canonical_serial(asset.serial_number)
           OR asset.canonical_mac IS DISTINCT FROM warehouse_canonical_mac(asset.mac_address) THEN
            RAISE EXCEPTION 'serial segment requires matching VERIFIED asset' USING ERRCODE='23514';
        END IF;
        PERFORM id FROM inventory_identity_claim WHERE tenant_id=owner_tenant AND identity_type='SERIAL'
            AND canonical_value=asset.canonical_serial AND admitted_asset_id=asset.id
            AND (state='ADMITTED' OR (NOT require_admitted AND state='RETIRED')) FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'serial claim is not ADMITTED to this asset' USING ERRCODE='23514'; END IF;
        IF asset.mac_address IS NOT NULL THEN
            PERFORM id FROM inventory_identity_claim WHERE tenant_id=owner_tenant AND identity_type='MAC'
                AND canonical_value=asset.canonical_mac AND admitted_asset_id=asset.id
                AND (state='ADMITTED' OR (NOT require_admitted AND state='RETIRED')) FOR SHARE;
            IF NOT FOUND THEN RAISE EXCEPTION 'MAC claim is not ADMITTED to this asset' USING ERRCODE='23514'; END IF;
        END IF;
        PERFORM warehouse_assert_stock_origin(owner_tenant,asset.origin_document_line_id,piece.sku_id,piece.base_unit);
    ELSE
        SELECT * INTO lot FROM inventory_lot WHERE tenant_id=owner_tenant AND id=piece.lot_id FOR SHARE;
        IF NOT FOUND OR lot.warehouse_admission<>'VERIFIED' OR lot.sku_id IS DISTINCT FROM piece.sku_id
           OR lot.base_unit IS DISTINCT FROM piece.base_unit OR piece.quantity_base>lot.received_quantity_base THEN
            RAISE EXCEPTION 'segment requires matching VERIFIED lot' USING ERRCODE='23514';
        END IF;
        PERFORM warehouse_assert_stock_origin(owner_tenant,lot.origin_document_line_id,piece.sku_id,piece.base_unit);
    END IF;
    LOOP
        IF piece.id=ANY(visited) THEN RAISE EXCEPTION 'cyclic segment provenance' USING ERRCODE='23514'; END IF;
        visited := array_append(visited,piece.id);
        IF piece.state<>'ACTIVE' AND
           (EXISTS (SELECT FROM inventory_balance_projection WHERE tenant_id=owner_tenant AND stock_identity_id=piece.id AND warehouse_admission='VERIFIED' AND quantity_base>0) OR
            EXISTS (SELECT FROM inventory_reservation WHERE tenant_id=owner_tenant AND stock_identity_id=piece.id AND state='OPEN' AND (reserved_unpicked_base>0 OR reserved_picked_base>0))) THEN
            RAISE EXCEPTION 'terminal segment retains balance or encumbrance' USING ERRCODE='23514';
        END IF;
        EXIT WHEN piece.parent_segment_id IS NULL;
        SELECT * INTO parent FROM inventory_segment WHERE tenant_id=owner_tenant AND id=piece.parent_segment_id FOR SHARE;
        IF NOT FOUND OR parent.warehouse_admission<>'VERIFIED' OR parent.state<>'SPLIT'
           OR parent.lot_id IS DISTINCT FROM piece.lot_id OR parent.sku_id IS DISTINCT FROM piece.sku_id
           OR parent.base_unit IS DISTINCT FROM piece.base_unit THEN
            RAISE EXCEPTION 'segment lineage must retain VERIFIED same-lot provenance' USING ERRCODE='23514';
        END IF;
        piece := parent;
    END LOOP;
END $$;

CREATE FUNCTION warehouse_stock_provenance_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE piece inventory_segment; balance inventory_balance_projection; reservation inventory_reservation;
BEGIN
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

CREATE FUNCTION warehouse_source_provenance_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE identity_id uuid; old_asset uuid;
BEGIN
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

CREATE FUNCTION warehouse_stock_identity_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=NEW.stock_identity_id FOR UPDATE;
    RETURN NEW;
END $$;

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_segment','inventory_balance_projection','inventory_reservation'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_verified_provenance AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_stock_provenance_guard()',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_serialized_asset','inventory_lot','inventory_identity_claim'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_verified_source AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_source_provenance_guard()',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_balance_projection','inventory_reservation'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_identity_fence BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_stock_identity_fence()',table_name);
    END LOOP;
END $$;
