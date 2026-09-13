DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_material_lifecycle_final_guard()'::regprocedure);
    IF position('THEN NEW.id ELSE NEW.residual_id END' IN definition)=0 THEN
        RAISE EXCEPTION 'expected lifecycle trigger routing missing';
    END IF;
    EXECUTE replace(definition,'THEN NEW.id ELSE NEW.residual_id END',
        'THEN NEW.id ELSE (to_jsonb(NEW)->>''residual_id'')::uuid END');
END $$;
