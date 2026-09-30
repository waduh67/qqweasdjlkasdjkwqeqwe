CREATE OR REPLACE FUNCTION warehouse_consumed_balance_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE balance inventory_balance_projection%ROWTYPE; posted numeric;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='UPDATE' AND OLD.status='CONSUMED' AND NEW.status<>'CONSUMED' THEN
        RAISE EXCEPTION 'consumed material cannot become reusable custody' USING ERRCODE='23514';
    END IF;
    IF NEW.status<>'CONSUMED' THEN
        IF NEW.warehouse_admission='VERIFIED' AND NEW.quantity_base>0 AND EXISTS (
            SELECT FROM inventory_movement_leg leg JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            WHERE leg.tenant_id=NEW.tenant_id AND leg.stock_identity_id=NEW.stock_identity_id AND leg.status='CONSUMED'
                AND leg.direction='IN' AND movement.state='APPLIED' AND
                (NEW.base_unit='MM' OR (leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,leg.condition,leg.legal_owner)=
                    (NEW.location_id,NEW.custody_owner_id,NEW.custody_owner_kind,NEW.condition,NEW.legal_owner))) THEN
            RAISE EXCEPTION 'consumed material cannot become reusable custody' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
    IF balance.warehouse_admission='VERIFIED' AND balance.status='CONSUMED' AND balance.quantity_base>0 THEN
        SELECT coalesce(sum(CASE WHEN leg.direction='IN' THEN leg.quantity_base::numeric ELSE -leg.quantity_base::numeric END),0) INTO posted
            FROM inventory_movement_leg leg JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            WHERE leg.tenant_id=NEW.tenant_id AND leg.stock_identity_id=balance.stock_identity_id AND leg.location_id=balance.location_id
                AND leg.custody_owner_id=balance.custody_owner_id AND leg.custody_owner_kind=balance.custody_owner_kind
                AND leg.condition=balance.condition AND leg.legal_owner=balance.legal_owner AND leg.status='CONSUMED' AND movement.state='APPLIED';
        IF posted<>balance.quantity_base THEN RAISE EXCEPTION 'consumed state requires physical posting' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END $$;
