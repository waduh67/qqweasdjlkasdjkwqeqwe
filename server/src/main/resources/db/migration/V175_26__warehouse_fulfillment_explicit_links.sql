DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_fulfillment_snapshot(uuid,uuid,boolean)'::regprocedure);
    IF position('expected:=expected||ARRAY[''SUBSCRIPTION'',''PROVISIONING''];' IN definition)=0 THEN
        RAISE EXCEPTION 'expected fulfillment service applicability missing';
    END IF;
    definition:=replace(definition,'expected:=expected||ARRAY[''SUBSCRIPTION'',''PROVISIONING''];',
        'expected:=array_append(expected,''SUBSCRIPTION'');
        IF body->>''bngAccessId'' IS NOT NULL THEN expected:=array_append(expected,''PROVISIONING''); END IF;');
    EXECUTE definition;
END $$;

CREATE FUNCTION warehouse_fulfillment_snapshot_insert_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    PERFORM id FROM work_order WHERE tenant_id=NEW.tenant_id AND id=NEW.work_order_id FOR UPDATE;
    IF EXISTS (SELECT FROM fulfillment_approval_snapshot WHERE tenant_id=NEW.tenant_id AND work_order_id=NEW.work_order_id) THEN
        RAISE EXCEPTION 'approved work order already has a frozen fulfillment snapshot' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_fulfillment_snapshot_insert BEFORE INSERT ON fulfillment_approval_snapshot
    FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_snapshot_insert_guard();
