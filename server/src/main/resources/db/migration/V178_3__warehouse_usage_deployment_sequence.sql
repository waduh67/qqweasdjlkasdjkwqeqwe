-- Measured usage and physical deployment share the WO revision sequence.
-- A deployment is not a previous bulk usage snapshot.
CREATE UNIQUE INDEX inventory_material_physical_revision_key
    ON inventory_document(tenant_id,work_order_id,use_revision)
    WHERE kind IN ('USAGE','DEPLOYMENT') AND state='POSTED';

CREATE FUNCTION warehouse_assert_usage_sequence(scope uuid, usage_id uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE usage inventory_usage_snapshot%ROWTYPE; previous_usage uuid; previous_revision bigint; live_revision bigint; creating boolean;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT usage FROM inventory_usage_snapshot WHERE tenant_id=scope AND id=usage_id;
    SELECT created_xid=pg_current_xact_id() INTO STRICT creating FROM inventory_material_usage WHERE tenant_id=scope AND id=usage_id;
    SELECT id INTO previous_usage FROM inventory_usage_snapshot
        WHERE tenant_id=scope AND work_order_id=usage.work_order_id AND use_revision<usage.use_revision
        ORDER BY use_revision DESC LIMIT 1;
    SELECT coalesce(max(revision),0) INTO previous_revision FROM (
        SELECT use_revision revision FROM inventory_usage_snapshot
            WHERE tenant_id=scope AND work_order_id=usage.work_order_id AND use_revision<usage.use_revision
        UNION ALL SELECT use_revision FROM inventory_document
            WHERE tenant_id=scope AND work_order_id=usage.work_order_id AND kind='DEPLOYMENT' AND state='POSTED'
                AND use_revision<usage.use_revision
    ) preceding;
    IF usage.use_revision::numeric<>previous_revision::numeric+1 OR usage.compensates_snapshot_id IS DISTINCT FROM previous_usage THEN
        RAISE EXCEPTION 'usage requires the next physical revision and latest usage predecessor' USING ERRCODE='23514';
    END IF;
    IF creating THEN
        SELECT coalesce(max(revision),0) INTO live_revision FROM (
            SELECT use_revision revision FROM inventory_usage_snapshot
                WHERE tenant_id=scope AND work_order_id=usage.work_order_id AND id<>usage.id
            UNION ALL SELECT use_revision FROM inventory_document
                WHERE tenant_id=scope AND work_order_id=usage.work_order_id AND kind='DEPLOYMENT' AND state='POSTED'
        ) live;
        IF usage.use_revision::numeric<>live_revision::numeric+1 THEN
            RAISE EXCEPTION 'new usage physical revision is stale' USING ERRCODE='23514';
        END IF;
    END IF;
END $$;

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_material_usage(uuid,uuid)'::regprocedure);
    IF strpos(definition,'predecessor.use_revision+1<>usage.use_revision')=0
        OR strpos(definition,'usage.use_revision<>1 OR usage.compensates_snapshot_id IS NOT NULL')=0
        OR strpos(definition,'    frozen:=usage.frozen_snapshot::jsonb;')=0 THEN
        RAISE EXCEPTION 'expected immutable usage revision guards missing';
    END IF;
    definition:=replace(definition,'predecessor.use_revision+1<>usage.use_revision','predecessor.use_revision>=usage.use_revision');
    definition:=replace(definition,'usage.use_revision<>1 OR usage.compensates_snapshot_id IS NOT NULL','usage.compensates_snapshot_id IS NOT NULL');
    definition:=replace(definition,'    frozen:=usage.frozen_snapshot::jsonb;',
        E'    PERFORM warehouse_assert_usage_sequence(target_tenant,target_usage);\n    frozen:=usage.frozen_snapshot::jsonb;');
    EXECUTE definition;
END $$;
