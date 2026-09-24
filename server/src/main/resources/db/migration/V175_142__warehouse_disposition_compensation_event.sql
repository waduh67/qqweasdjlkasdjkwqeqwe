-- Preserve existing event kinds and admit the one explicit compensation event.
DO $migration$ DECLARE definition text;
BEGIN
    SELECT pg_get_expr(conbin,conrelid) INTO STRICT definition FROM pg_constraint
        WHERE conrelid='inventory_outbox'::regclass AND conname='inventory_outbox_event_kind_check';
    ALTER TABLE inventory_outbox DROP CONSTRAINT inventory_outbox_event_kind_check;
    EXECUTE format('ALTER TABLE inventory_outbox ADD CONSTRAINT inventory_outbox_event_kind_check CHECK ((%s) OR event_kind=''DISPOSITION_REVERSED'')',definition);
END $migration$;
