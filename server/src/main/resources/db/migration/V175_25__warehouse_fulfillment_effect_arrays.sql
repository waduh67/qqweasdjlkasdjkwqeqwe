DO $$ DECLARE signature text; definition text;
BEGIN
    FOREACH signature IN ARRAY ARRAY['warehouse_assert_fulfillment_snapshot(uuid,uuid,boolean)',
        'warehouse_assert_fulfillment_completion(uuid,uuid)'] LOOP
        definition:=pg_get_functiondef(signature::regprocedure);
        IF position('frozen.required_effects' IN definition)=0 THEN
            RAISE EXCEPTION 'expected fulfillment applicability validation missing';
        END IF;
        definition:=replace(definition,'frozen.required_effects','frozen.required_effects::text[]');
        definition:=replace(definition,'array_agg(effect_type ORDER BY effect_type)','array_agg(effect_type::text ORDER BY effect_type)');
        EXECUTE definition;
    END LOOP;
END $$;
