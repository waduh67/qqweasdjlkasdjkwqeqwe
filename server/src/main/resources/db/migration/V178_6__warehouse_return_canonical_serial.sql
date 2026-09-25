-- Physical serial identity is trim + locale-independent uppercase.
-- Receipt spelling, frozen origin snapshots, command payloads and replay bodies
-- remain byte-for-byte historical records. Only observed identity comparison changes.
-- Preserve every source, ownership, reset, quantity, actor and transaction guard.

DO $migration$
DECLARE definition text; previous text := $previous$inspection->>'observedSerial' IS DISTINCT FROM record->'source'->>'serial'$previous$;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_return(uuid,uuid)'::regprocedure);
    IF strpos(definition,previous)=0 OR strpos(substr(definition,strpos(definition,previous)+length(previous)),previous)>0 THEN
        RAISE EXCEPTION 'expected unique serial identity guard in warehouse_assert_return(uuid,uuid) not found';
    END IF;
    definition:=replace(definition,previous,$replacement$warehouse_canonical_serial(inspection->>'observedSerial') IS DISTINCT FROM warehouse_canonical_serial(record->'source'->>'serial')$replacement$);
    EXECUTE definition;
END $migration$;

DO $migration$
DECLARE definition text; previous text := $previous$inspection->>'observedSerial' IS DISTINCT FROM physical.serial_number$previous$;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_returned_asset(uuid,uuid)'::regprocedure);
    IF strpos(definition,previous)=0 OR strpos(substr(definition,strpos(definition,previous)+length(previous)),previous)>0 THEN
        RAISE EXCEPTION 'expected unique serial identity guard in warehouse_assert_returned_asset(uuid,uuid) not found';
    END IF;
    definition:=replace(definition,previous,$replacement$warehouse_canonical_serial(inspection->>'observedSerial') IS DISTINCT FROM warehouse_canonical_serial(physical.serial_number)$replacement$);
    EXECUTE definition;
END $migration$;

DO $migration$
DECLARE definition text; previous text := $previous$request->>'observedSerial' IS DISTINCT FROM intake.body::jsonb->'source'->>'serial'$previous$;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_return_repair_step(uuid,uuid,uuid,jsonb,jsonb)'::regprocedure);
    IF strpos(definition,previous)=0 OR strpos(substr(definition,strpos(definition,previous)+length(previous)),previous)>0 THEN
        RAISE EXCEPTION 'expected unique serial identity guard in warehouse_assert_return_repair_step(uuid,uuid,uuid,jsonb,jsonb) not found';
    END IF;
    definition:=replace(definition,previous,$replacement$warehouse_canonical_serial(request->>'observedSerial') IS DISTINCT FROM warehouse_canonical_serial(intake.body::jsonb->'source'->>'serial')$replacement$);
    EXECUTE definition;
END $migration$;

DO $migration$
DECLARE definition text; previous text := $previous$request->>'observedSerial' IS DISTINCT FROM physical.serial_number$previous$;
BEGIN
    definition:=pg_get_functiondef('warehouse_capture_rma_handover()'::regprocedure);
    IF strpos(definition,previous)=0 OR strpos(substr(definition,strpos(definition,previous)+length(previous)),previous)>0 THEN
        RAISE EXCEPTION 'expected unique serial identity guard in warehouse_capture_rma_handover() not found';
    END IF;
    definition:=replace(definition,previous,$replacement$warehouse_canonical_serial(request->>'observedSerial') IS DISTINCT FROM warehouse_canonical_serial(physical.serial_number)$replacement$);
    EXECUTE definition;
END $migration$;

DO $migration$
DECLARE definition text; previous text := $previous$NEW.request->>'observedSerial' IS DISTINCT FROM request->>'observedSerial'$previous$;
BEGIN
    definition:=pg_get_functiondef('warehouse_rma_receipt_guard()'::regprocedure);
    IF strpos(definition,previous)=0 OR strpos(substr(definition,strpos(definition,previous)+length(previous)),previous)>0 THEN
        RAISE EXCEPTION 'expected unique serial identity guard in warehouse_rma_receipt_guard() not found';
    END IF;
    definition:=replace(definition,previous,$replacement$warehouse_canonical_serial(NEW.request->>'observedSerial') IS DISTINCT FROM warehouse_canonical_serial(request->>'observedSerial')$replacement$);
    EXECUTE definition;
END $migration$;

