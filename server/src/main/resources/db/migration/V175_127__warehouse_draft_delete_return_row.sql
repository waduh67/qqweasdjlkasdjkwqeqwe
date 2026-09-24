-- A BEFORE DELETE trigger must return OLD to perform an allowed deletion.
-- Keep the current tenant and immutable transfer checks before returning the row.
DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_transfer_binding_guard()'::regprocedure);
    anchor:='RETURN NEW;';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'transfer mutation return changed'; END IF;
    EXECUTE replace(definition,anchor,'IF TG_OP=''DELETE'' THEN RETURN OLD; END IF; RETURN NEW;');
END $$;
