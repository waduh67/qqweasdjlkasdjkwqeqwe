CREATE FUNCTION warehouse_authorization_snapshot_matches(snapshot jsonb, permit inventory_deployment_authorization)
RETURNS boolean LANGUAGE plpgsql STABLE AS $$
DECLARE expected jsonb; timestamp_columns text[]; field_name text; stored_value text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(permit.tenant_id);
    IF jsonb_typeof(snapshot) IS DISTINCT FROM 'object' THEN RETURN false; END IF;
    expected:=to_jsonb(permit);
    SELECT coalesce(array_agg(attribute.attname::text),'{}'::text[]) INTO timestamp_columns
        FROM pg_attribute attribute JOIN pg_type row_type ON row_type.typrelid=attribute.attrelid
        WHERE row_type.oid=pg_typeof(permit)::oid AND attribute.attnum>0 AND NOT attribute.attisdropped
            AND attribute.atttypid='timestamptz'::regtype;
    IF NOT (snapshot ?& timestamp_columns)
        OR (snapshot-timestamp_columns) IS DISTINCT FROM (expected-timestamp_columns) THEN
        RETURN false;
    END IF;
    FOREACH field_name IN ARRAY timestamp_columns LOOP
        IF jsonb_typeof(snapshot->field_name) NOT IN ('string','null') THEN RETURN false; END IF;
        stored_value:=snapshot->>field_name;
        IF stored_value IS NOT NULL AND stored_value NOT IN ('infinity','-infinity')
            AND stored_value !~ '^[0-9]{4,}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2}(:[0-9]{2})?)$' THEN
            RETURN false;
        END IF;
        BEGIN
            IF stored_value::timestamptz IS DISTINCT FROM (expected->>field_name)::timestamptz THEN
                RETURN false;
            END IF;
        EXCEPTION WHEN invalid_datetime_format OR datetime_field_overflow THEN
            RETURN false;
        END;
    END LOOP;
    RETURN true;
END $$;

DO $$ DECLARE definition text; previous text:='history.snapshot=to_jsonb(permit)';
BEGIN
    SELECT pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure) INTO definition;
    IF strpos(definition,previous)=0 THEN RAISE EXCEPTION 'expected authorization history comparison missing'; END IF;
    EXECUTE replace(definition,previous,'warehouse_authorization_snapshot_matches(history.snapshot,permit)');
END $$;

CREATE OR REPLACE FUNCTION warehouse_episode_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected jsonb; permit inventory_deployment_authorization;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    CASE TG_TABLE_NAME
        WHEN 'inventory_asset_assignment_history' THEN
            SELECT to_jsonb(assignment) INTO expected FROM inventory_asset_assignment assignment
                WHERE tenant_id=NEW.tenant_id AND id=NEW.assignment_id AND revision=NEW.revision;
            IF expected IS NULL OR NEW.snapshot IS DISTINCT FROM expected THEN
                RAISE EXCEPTION 'history snapshot must match owner row revision' USING ERRCODE='23514';
            END IF;
        WHEN 'inventory_deployment_authorization_history' THEN
            SELECT * INTO permit FROM inventory_deployment_authorization
                WHERE tenant_id=NEW.tenant_id AND id=NEW.authorization_id AND revision=NEW.revision;
            IF NOT FOUND OR NOT warehouse_authorization_snapshot_matches(NEW.snapshot,permit) THEN
                RAISE EXCEPTION 'history snapshot must match owner row revision' USING ERRCODE='23514';
            END IF;
        ELSE RAISE EXCEPTION 'unsupported history owner' USING ERRCODE='23514';
    END CASE;
    RETURN NEW;
END $$;
