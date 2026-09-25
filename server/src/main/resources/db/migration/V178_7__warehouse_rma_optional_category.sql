-- A category is optional on a serialized SKU. Match the application predicate:
-- only ONU/ONT creates a network ONU episode; an absent category means false.
-- Keep source, customer, custody, title, revision and replay bindings intact.
DO $$
DECLARE definition text;
    previous text := $previous$((execution.rma_origin_snapshot->'sku'->>'category') IN ('ONU','ONT'))$previous$;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_rma_execution(uuid,uuid)'::regprocedure);
    IF (length(definition)-length(replace(definition,previous,'')))/length(previous) <> 1 THEN
        RAISE EXCEPTION 'expected unique optional category guard in warehouse_assert_rma_execution(uuid,uuid) not found';
    END IF;
    definition:=replace(definition,previous,$replacement$coalesce((execution.rma_origin_snapshot->'sku'->>'category') IN ('ONU','ONT'),false)$replacement$);
    EXECUTE definition;
END $$;
