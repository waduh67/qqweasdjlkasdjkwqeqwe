-- warehouse_received_recovery validates the accepted return and current physical
-- owner against the APPLIED ledger. A closed episode keeps its original title;
-- later approved transfers change the physical title without rewriting that seal.
DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_asset_removal(uuid,uuid)'::regprocedure);
    anchor:='ARRAY[''location_id'',''custody_owner_id'',''custody_owner_kind'',''status'',''condition'',''revision'']';
    IF position(anchor IN definition)=0
        OR position('continued:=warehouse_received_recovery(scope,removal.id)' IN definition)=0 THEN
        RAISE EXCEPTION 'received removal identity comparison changed'; END IF;
    EXECUTE replace(definition,anchor,
        '('||anchor||' || CASE WHEN continued THEN ARRAY[''legal_owner''] ELSE ARRAY[]::text[] END)');
END $$;
