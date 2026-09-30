-- JSON field extraction must finish before removing allowed object keys.
-- The current physical position remains derived from APPLIED movements after
-- receipt, including replacements with no prior installation/removal history.
DO $migration$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_capture_replacement_request()'::regprocedure);
    anchor:='input->''cost''-ARRAY[''totalMinor'',''currency'']';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'replacement cost validator changed'; END IF;
    definition:=replace(definition,anchor,'(input->''cost'')-ARRAY[''totalMinor'',''currency'']');
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_replacement_receipt(uuid,uuid)'::regprocedure);
    anchor:='END $function$';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'replacement receipt validator changed'; END IF;
    definition:=replace(definition,anchor,$patch$
    PERFORM warehouse_assert_recovered_position(scope,effect.replacement_asset_id);
END $function$
$patch$);
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_replacement_receipt_final_guard()'::regprocedure);
    anchor:='OR TG_TABLE_NAME=''inventory_serialized_asset'' AND effect.replacement_asset_id=target_id';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'replacement final routing changed'; END IF;
    definition:=replace(definition,anchor,anchor||'
                OR effect.replacement_asset_id=(value->>''stock_identity_id'')::uuid');
    EXECUTE definition;
END $migration$;

CREATE CONSTRAINT TRIGGER warehouse_replacement_receipt_final
    AFTER INSERT OR UPDATE OR DELETE ON inventory_balance_projection
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION warehouse_replacement_receipt_final_guard();
