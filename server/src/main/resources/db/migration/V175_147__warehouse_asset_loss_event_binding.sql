CREATE FUNCTION warehouse_asset_loss_posting_payload(scope uuid,target uuid) RETURNS jsonb LANGUAGE plpgsql AS $function$
DECLARE payload jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT jsonb_build_object('postingId',target,'legs',jsonb_agg(jsonb_build_object(
        'direction',leg.direction,'dimension',jsonb_build_object('skuId',leg.sku_id,'stockIdentityId',leg.stock_identity_id,
            'lotId',leg.lot_id,'locationId',leg.location_id,'custodianId',leg.custody_owner_id,'custodianKind',leg.custody_owner_kind,
            'condition',leg.condition,'legalOwner',leg.legal_owner),
        'quantity',jsonb_build_object('quantityBase',leg.quantity_base,'unit',leg.base_unit),
        'documentLineId',leg.document_line_id,'status',leg.status,
        'endpoint',CASE leg.direction WHEN 'OUT' THEN 'CUSTOMER_INSTALLED' ELSE 'PHYSICAL' END)
        ORDER BY CASE leg.direction WHEN 'OUT' THEN 0 ELSE 1 END), 'reservations','[]'::jsonb,'splits','[]'::jsonb)
        INTO payload FROM inventory_movement_leg leg WHERE leg.tenant_id=scope AND leg.movement_id=target;
    RETURN payload;
END $function$;
DO $migration$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_asset_loss_effect(uuid,uuid)'::regprocedure);
    anchor:='AND payload=posting.original_body';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'loss outbox response comparison changed'; END IF;
    EXECUTE replace(definition,anchor,'AND payload::jsonb=warehouse_asset_loss_posting_payload(scope,movement.id)');
END $migration$;
