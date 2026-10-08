DO $$
DECLARE definition text; function_name text;
BEGIN
    FOREACH function_name IN ARRAY ARRAY['warehouse_assert_reference_fulfillment_snapshot(uuid,uuid,boolean)',
        'warehouse_assert_reference_fulfillment_completion(uuid,uuid)'] LOOP
        definition:=pg_get_functiondef(function_name::regprocedure);
        IF strpos(definition,'frozen.required_effects')=0 THEN
            RAISE EXCEPTION 'reference fulfillment effects anchor changed';
        END IF;
        definition:=replace(definition,'frozen.required_effects','frozen.required_effects::text[]');
        definition:=replace(definition,'array_agg(effect_type ORDER BY effect_type)',
            'array_agg(effect_type::text ORDER BY effect_type)');
        EXECUTE definition;
    END LOOP;
END $$;
