-- CUSTOMER and ISP are distinct balance dimensions with independent revisions.
-- Their title legs share physical identity, quantity, custody and condition.
DO $$ DECLARE definition text; old_clause text; new_clause text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_return_title_effect(uuid,uuid)'::regprocedure);
    old_clause:='to_jsonb(incoming)-ARRAY[''id'',''direction'',''legal_owner''] IS DISTINCT FROM to_jsonb(outgoing)-ARRAY[''id'',''direction'',''legal_owner'']';
    new_clause:='to_jsonb(incoming)-ARRAY[''id'',''direction'',''legal_owner'',''revision''] IS DISTINCT FROM to_jsonb(outgoing)-ARRAY[''id'',''direction'',''legal_owner'',''revision'']';
    IF position(old_clause IN definition)=0 THEN RAISE EXCEPTION 'return title physical leg comparison changed'; END IF;
    EXECUTE replace(definition,old_clause,new_clause);
END $$;
