ALTER FUNCTION warehouse_assert_reference_count(uuid,uuid) RENAME TO warehouse_assert_reference_count_v194;
CREATE FUNCTION warehouse_assert_reference_count(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE input jsonb; loaded inventory_reference_count_snapshot; BEGIN
    PERFORM warehouse_assert_reference_count_v194(scope,target);
    SELECT canonical_payload::jsonb->'input' INTO STRICT input FROM inventory_reference_count_attempt WHERE tenant_id=scope AND id=target;
    SELECT snapshot.* INTO STRICT loaded FROM inventory_reference_count_snapshot snapshot JOIN inventory_reference_count_attempt attempt
        ON attempt.tenant_id=snapshot.tenant_id AND attempt.snapshot_id=snapshot.id WHERE attempt.tenant_id=scope AND attempt.id=target;
    IF loaded.snapshot->>'tracking'='SERIAL' AND EXISTS(SELECT FROM jsonb_array_elements(input->'serials') entry
        LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=scope AND asset.canonical_serial=warehouse_canonical_serial(entry->>'serial')
        WHERE warehouse_canonical_serial(entry->>'serial') IS NULL OR length(entry->>'serial')>128
            OR entry IS DISTINCT FROM jsonb_build_object('serial',entry->'serial','mac',entry->'mac')
            OR asset.id IS NULL OR entry->>'mac' IS NOT NULL AND
                (warehouse_canonical_mac(entry->>'mac') IS NULL OR warehouse_canonical_mac(entry->>'mac') IS DISTINCT FROM asset.canonical_mac)) THEN
        RAISE EXCEPTION 'count physical serial or MAC differs from owned identity' USING ERRCODE='23514';
    END IF;
END $$;

DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_assert_reference_count_post(uuid,uuid)'::regprocedure);
    anchor:='IF NOT is_loss AND NOT EXISTS(WITH RECURSIVE ancestry';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'count recovery guard layout changed'; END IF;
    definition:=replace(definition,anchor,'IF NOT is_loss AND NOT EXISTS(SELECT FROM inventory_balance_projection balance
            JOIN inventory_movement_leg leg ON leg.tenant_id=balance.tenant_id AND leg.stock_identity_id=balance.stock_identity_id
                AND leg.movement_id=movement.id AND leg.document_line_id=line.id AND leg.direction=''OUT''
                AND leg.location_id=balance.location_id AND leg.custody_owner_id=balance.custody_owner_id
                AND leg.custody_owner_kind=balance.custody_owner_kind AND leg.condition=balance.condition
                AND leg.legal_owner=balance.legal_owner AND leg.status=balance.status AND leg.lot_id IS NOT DISTINCT FROM balance.lot_id
            WHERE balance.tenant_id=scope AND balance.id=(item->>''balanceId'')::uuid AND balance.location_id=missing) THEN
            RAISE EXCEPTION ''count recovery balance differs from command'' USING ERRCODE=''23514''; END IF;
        IF NOT is_loss AND NOT EXISTS(WITH RECURSIVE ancestry');
    EXECUTE definition;
END $$;
