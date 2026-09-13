CREATE FUNCTION warehouse_assert_fulfillment_owners(target_tenant uuid,target_id uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE persisted boolean;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    PERFORM warehouse_assert_fulfillment_owner_sources(target_tenant,target_id);
    SELECT EXISTS(SELECT FROM fulfillment_approval_snapshot snapshot JOIN fulfillment_checkpoint checkpoint
        ON checkpoint.tenant_id=snapshot.tenant_id AND checkpoint.namespace=snapshot.namespace AND checkpoint.operation_key=snapshot.operation_key
        WHERE snapshot.tenant_id=target_tenant AND snapshot.id=target_id) INTO persisted;
    IF persisted THEN PERFORM warehouse_assert_fulfillment_owner_receipts(target_tenant,target_id); END IF;
END $$;

CREATE FUNCTION warehouse_fulfillment_owner_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target_tenant uuid; before_row jsonb; after_row jsonb; target uuid; ids uuid[];
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    target_tenant:=CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    before_row:=CASE WHEN TG_OP='INSERT' THEN '{}'::jsonb ELSE to_jsonb(OLD) END;
    after_row:=CASE WHEN TG_OP='DELETE' THEN '{}'::jsonb ELSE to_jsonb(NEW) END;
    ids:=ARRAY[(before_row->>'id')::uuid,(after_row->>'id')::uuid];
    FOR target IN SELECT DISTINCT snapshot.id FROM fulfillment_approval_snapshot snapshot
        WHERE snapshot.tenant_id=target_tenant AND CASE
            WHEN TG_TABLE_NAME IN ('fulfillment_approval_snapshot','inventory_material_settlement','customer_fulfillment_receipt','bng_fulfillment_receipt','fieldservice_fulfillment_receipt')
                THEN snapshot.id=ANY(ids)
            WHEN TG_TABLE_NAME IN ('customer_fulfillment_transition','bng_fulfillment_transition','fieldservice_fulfillment_transition','order_fulfillment_transition')
                THEN snapshot.id IN ((before_row->>'approval_id')::uuid,(after_row->>'approval_id')::uuid)
            WHEN TG_TABLE_NAME IN ('fulfillment_checkpoint','workorder_fulfillment_result','order_operation','fieldservice_visit_operation')
                THEN (snapshot.namespace=before_row->>'namespace' AND snapshot.operation_key=before_row->>'operation_key') OR
                    (snapshot.namespace=after_row->>'namespace' AND snapshot.operation_key=after_row->>'operation_key')
            WHEN TG_TABLE_NAME IN ('fulfillment_effect_progress','fulfillment_outbox') THEN EXISTS(SELECT FROM fulfillment_checkpoint checkpoint
                WHERE checkpoint.tenant_id=target_tenant AND checkpoint.id IN ((before_row->>'fulfillment_id')::uuid,(after_row->>'fulfillment_id')::uuid)
                    AND checkpoint.namespace=snapshot.namespace AND checkpoint.operation_key=snapshot.operation_key)
            WHEN TG_TABLE_NAME='work_order' THEN snapshot.work_order_id=ANY(ids)
            WHEN TG_TABLE_NAME='order_record' THEN (snapshot.snapshot::jsonb->'workOrder'->'material'->>'orderId')::uuid=ANY(ids)
            WHEN TG_TABLE_NAME='fieldservice_visit' THEN (snapshot.snapshot::jsonb->'visit'->>'id')::uuid=ANY(ids)
            WHEN TG_TABLE_NAME='subscription' THEN (snapshot.snapshot::jsonb->'workOrder'->'material'->>'subscriptionId')::uuid=ANY(ids)
            WHEN TG_TABLE_NAME='customer' THEN (snapshot.snapshot::jsonb->'workOrder'->'material'->>'customerId')::uuid=ANY(ids)
            WHEN TG_TABLE_NAME='subscriber_access' THEN (snapshot.snapshot::jsonb->>'bngAccessId')::uuid=ANY(ids)
            WHEN TG_TABLE_NAME='bng_action' THEN EXISTS(SELECT FROM bng_fulfillment_receipt receipt WHERE receipt.tenant_id=target_tenant
                AND receipt.id=snapshot.id AND receipt.action_ids && ids)
            ELSE false END LOOP
        PERFORM warehouse_assert_fulfillment_owner_sources(target_tenant,target);
        PERFORM warehouse_assert_fulfillment_owner_receipts(target_tenant,target);
    END LOOP;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['fulfillment_approval_snapshot','inventory_material_settlement','customer_fulfillment_receipt',
        'bng_fulfillment_receipt','fieldservice_fulfillment_receipt','fulfillment_checkpoint','workorder_fulfillment_result','order_operation',
        'fieldservice_visit_operation','fulfillment_effect_progress','fulfillment_outbox','work_order','order_record','fieldservice_visit',
        'subscription','customer','subscriber_access','bng_action','customer_fulfillment_transition','bng_fulfillment_transition',
        'fieldservice_fulfillment_transition','order_fulfillment_transition'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_fulfillment_owner_bound AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_owner_guard()',table_name);
    END LOOP;
END $$;
