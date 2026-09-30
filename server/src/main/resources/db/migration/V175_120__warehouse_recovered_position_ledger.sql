-- A historical receipt authorizes later movements, not arbitrary rewrites of
-- both current projections. Reconcile by dimension (status is a projection of
-- the latest inbound leg), using numeric sums and excluding receipt sources.
CREATE FUNCTION warehouse_assert_recovered_position(scope uuid, asset_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE physical inventory_serialized_asset; position inventory_balance_projection;
    ledger record; nonzero_positions integer:=0;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO physical FROM inventory_serialized_asset WHERE tenant_id=scope AND id=$2;
    IF physical.id IS NULL OR physical.warehouse_admission<>'VERIFIED' THEN
        RAISE EXCEPTION 'RETURN_CURRENT_ASSET_REQUIRED' USING ERRCODE='23514'; END IF;
    FOR ledger IN
        SELECT leg.sku_id,leg.stock_identity_id,leg.lot_id,leg.location_id,leg.custody_owner_id,
            leg.custody_owner_kind,leg.condition,leg.legal_owner,leg.base_unit,
            sum(CASE leg.direction WHEN 'IN' THEN leg.quantity_base::numeric ELSE -leg.quantity_base::numeric END) quantity_base,
            (array_agg(leg.status ORDER BY leg.revision DESC,movement.server_received_at DESC)
                FILTER (WHERE leg.direction='IN'))[1] status
        FROM inventory_movement_leg leg JOIN inventory_movement movement
            ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
        WHERE leg.tenant_id=scope AND leg.stock_identity_id=$2 AND leg.warehouse_admission='VERIFIED'
            AND movement.state='APPLIED' AND leg.status<>'RECEIPT_SOURCE'
        GROUP BY leg.sku_id,leg.stock_identity_id,leg.lot_id,leg.location_id,leg.custody_owner_id,
            leg.custody_owner_kind,leg.condition,leg.legal_owner,leg.base_unit
    LOOP
        IF ledger.quantity_base=0 THEN CONTINUE; END IF;
        nonzero_positions:=nonzero_positions+1;
        SELECT * INTO position FROM inventory_balance_projection current_position
            WHERE current_position.tenant_id=scope AND current_position.stock_identity_id=$2
                AND current_position.sku_id=ledger.sku_id AND current_position.lot_id IS NOT DISTINCT FROM ledger.lot_id
                AND current_position.location_id=ledger.location_id AND current_position.custody_owner_id=ledger.custody_owner_id
                AND current_position.custody_owner_kind=ledger.custody_owner_kind AND current_position.condition=ledger.condition
                AND current_position.legal_owner=ledger.legal_owner AND current_position.warehouse_admission='VERIFIED';
        IF ledger.quantity_base<>1 OR ledger.base_unit<>'EA' OR position.id IS NULL
            OR position.quantity_base<>1 OR position.base_unit<>'EA' OR position.status IS DISTINCT FROM ledger.status
            OR (physical.sku_id,physical.location_id,physical.custody_owner_id,physical.custody_owner_kind,
                physical.condition,physical.legal_owner,physical.status) IS DISTINCT FROM
                (ledger.sku_id,ledger.location_id,ledger.custody_owner_id,ledger.custody_owner_kind,
                ledger.condition,ledger.legal_owner,ledger.status) THEN
            RAISE EXCEPTION 'RETURN_CURRENT_POSITION_REQUIRES_POSTING' USING ERRCODE='23514'; END IF;
    END LOOP;
    IF nonzero_positions<>1 OR (SELECT count(*) FROM inventory_balance_projection
        WHERE tenant_id=scope AND stock_identity_id=$2 AND quantity_base>0)<>1 THEN
        RAISE EXCEPTION 'RETURN_CURRENT_POSITION_REQUIRES_POSTING' USING ERRCODE='23514'; END IF;
END $$;

-- The existing removal final guards discover this asset through mutations of
-- assets, balances, movements and their legs. Keep all immutable origin checks.
CREATE OR REPLACE FUNCTION warehouse_received_recovery(scope uuid, removal_id uuid) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE target uuid; physical_id uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT id,stock_identity_id INTO target,physical_id FROM inventory_return_case
        WHERE tenant_id=scope AND origin='ASSET_REMOVAL' AND source_document_id=$2;
    IF target IS NULL THEN RETURN false; END IF;
    PERFORM warehouse_assert_returned_asset(scope,target);
    PERFORM warehouse_assert_recovered_position(scope,physical_id);
    RETURN true;
END $$;
