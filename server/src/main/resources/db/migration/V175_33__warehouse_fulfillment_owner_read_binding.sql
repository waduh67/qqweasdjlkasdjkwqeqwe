CREATE OR REPLACE FUNCTION warehouse_assert_fulfillment_owners(target_tenant uuid,target_id uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE persisted boolean;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    PERFORM warehouse_assert_fulfillment_owner_sources(target_tenant,target_id);
    SELECT EXISTS(SELECT FROM fulfillment_approval_snapshot snapshot JOIN fulfillment_checkpoint checkpoint
        ON checkpoint.tenant_id=snapshot.tenant_id AND checkpoint.namespace=snapshot.namespace AND checkpoint.operation_key=snapshot.operation_key
        WHERE snapshot.tenant_id=$1 AND snapshot.id=$2) INTO persisted;
    IF persisted THEN PERFORM warehouse_assert_fulfillment_owner_receipts(target_tenant,target_id); END IF;
END $$;
