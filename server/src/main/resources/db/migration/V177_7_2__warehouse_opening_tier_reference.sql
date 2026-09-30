-- Disambiguate the configured tier JSON from the captured requirement's tier number.
DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_migration_opening_approval_current(uuid,uuid,uuid,boolean)'::regprocedure);
    anchor:='IS DISTINCT FROM tier->>''minimumMinor''';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'opening policy tier reference missing'; END IF;
    EXECUTE replace(definition,anchor,'IS DISTINCT FROM required.tier->>''minimumMinor''');
END $$;
