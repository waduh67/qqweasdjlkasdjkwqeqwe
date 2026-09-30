-- New reviewed handovers bind the named people as well as the stock/location IDs.
-- Existing requests omit these optional fields, preserving their canonical hashes.
DO $$
DECLARE definition text; marker text := '    payload:=NEW.body::jsonb;';
BEGIN
    definition := pg_get_functiondef('warehouse_material_handover_insert_guard()'::regprocedure);
    IF position(marker IN definition) = 0 THEN
        RAISE EXCEPTION 'expected material handover payload guard is missing';
    END IF;
    definition := replace(definition, marker, marker || '
    IF (payload#>>''{request,expectedSenderId}'' IS NOT NULL AND
            payload#>>''{request,expectedSenderId}'' IS DISTINCT FROM NEW.sender_id::text) OR
        (payload#>>''{request,expectedReceiverId}'' IS NOT NULL AND
            payload#>>''{request,expectedReceiverId}'' IS DISTINCT FROM NEW.receiver_id::text) OR
        ((payload#>>''{request,expectedSenderId}'' IS NULL) <> (payload#>>''{request,expectedReceiverId}'' IS NULL)) OR
        (payload->>''sourceLocationId'' IS NOT NULL AND NOT EXISTS (
            SELECT FROM inventory_balance_projection position WHERE position.tenant_id=NEW.tenant_id
                AND position.stock_identity_id=NEW.source_identity_id AND position.custody_owner_id=NEW.sender_id
                AND position.custody_owner_kind=''TECHNICIAN'' AND position.status=''ISSUED'' AND position.quantity_base>0
                AND position.location_id::text=payload->>''sourceLocationId'')) THEN
        RAISE EXCEPTION ''handover expected parties or source location mismatch'' USING ERRCODE=''23514'';
    END IF;');
    EXECUTE definition;
END $$;
