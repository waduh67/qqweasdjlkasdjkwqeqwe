CREATE FUNCTION warehouse_assert_fulfillment_snapshot(target_tenant uuid,target_id uuid,live boolean) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE frozen fulfillment_approval_snapshot%ROWTYPE; job work_order%ROWTYPE; usage inventory_usage_snapshot%ROWTYPE;
    plan inventory_material_plan%ROWTYPE; body jsonb; material jsonb; context jsonb; expected text[]; payload text[]; item jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO STRICT frozen FROM fulfillment_approval_snapshot WHERE tenant_id=target_tenant AND id=target_id;
    body:=frozen.snapshot::jsonb; material:=body->'material'; context:=body->'workOrder'->'material';
    SELECT * INTO STRICT usage FROM inventory_usage_snapshot WHERE tenant_id=target_tenant AND id=frozen.usage_id;
    SELECT * INTO STRICT plan FROM inventory_material_plan WHERE tenant_id=target_tenant AND id=frozen.plan_id;
    payload:=string_to_array(frozen.request_payload,'|');
    IF frozen.payload_hash<>encode(sha256(convert_to(frozen.snapshot,'UTF8')),'hex') OR
        body->>'id' IS DISTINCT FROM frozen.id::text OR body->'identity'->>'tenantId' IS DISTINCT FROM target_tenant::text OR
        body->'identity'->>'userId' IS DISTINCT FROM frozen.approved_by::text OR
        body->'workOrder'->>'approvedBy' IS DISTINCT FROM frozen.approved_by::text OR
        body->'workOrder'->>'completedBy' IS NOT DISTINCT FROM frozen.approved_by::text OR
        context->>'workOrderId' IS DISTINCT FROM frozen.work_order_id::text OR
        context->>'workOrderRevision' IS DISTINCT FROM frozen.work_order_revision::text OR
        material->>'usageId' IS DISTINCT FROM frozen.usage_id::text OR material->>'useRevision' IS DISTINCT FROM frozen.use_revision::text OR
        material->>'planId' IS DISTINCT FROM frozen.plan_id::text OR material->>'planRevision' IS DISTINCT FROM plan.plan_revision::text OR
        material->>'materialMode' IS DISTINCT FROM frozen.material_mode OR material->>'reason' IS DISTINCT FROM plan.reason OR
        material->>'usageBody' IS DISTINCT FROM usage.frozen_snapshot OR
        material->>'usageHash' IS DISTINCT FROM encode(sha256(convert_to(usage.frozen_snapshot,'UTF8')),'hex') OR
        usage.work_order_id<>frozen.work_order_id OR usage.plan_id<>plan.id OR usage.use_revision<>frozen.use_revision OR
        usage.material_mode<>frozen.material_mode OR plan.material_mode<>frozen.material_mode OR plan.state<>'SUBMITTED' OR
        plan.work_order_id<>frozen.work_order_id OR cardinality(payload)<>13 OR
        payload[1]<>target_tenant::text OR payload[2]<>frozen.namespace OR payload[3]<>frozen.operation_key OR
        payload[4]<>frozen.payload_hash OR payload[5]<>'WORK_ORDER' OR payload[6]<>frozen.work_order_id::text OR
        payload[7]<>coalesce(context->>'subscriptionId','') OR payload[8]<>frozen.work_order_id::text OR
        payload[9]<>context->>'workType' OR payload[10]<>'true' OR payload[12]<>coalesce(context->>'orderId','') OR
        payload[13]<>frozen.approved_by::text THEN
        RAISE EXCEPTION 'fulfillment snapshot binding mismatch' USING ERRCODE='23514';
    END IF;
    expected:=ARRAY['INVENTORY','WORK_ORDER'];
    IF context->>'orderId' IS NOT NULL THEN
        IF body->>'orderRevision' IS NULL THEN RAISE EXCEPTION 'fulfillment order revision missing' USING ERRCODE='23514'; END IF;
        expected:=array_append(expected,'ORDER');
    END IF;
    IF body->>'visit' IS NOT NULL THEN
        IF body->'visit'->>'workOrderId' IS DISTINCT FROM frozen.work_order_id::text OR
            body->'visit'->>'tenantId' IS DISTINCT FROM target_tenant::text THEN
            RAISE EXCEPTION 'fulfillment visit link mismatch' USING ERRCODE='23514';
        END IF;
        expected:=array_append(expected,'VISIT');
    END IF;
    IF context->>'subscriptionId' IS NOT NULL AND context->>'customerId' IS NOT NULL AND context->>'action' IN ('INSTALL','REMOVE') THEN
        IF body->'subscription'->>'id' IS DISTINCT FROM context->>'subscriptionId' OR
            body->'subscription'->>'customerId' IS DISTINCT FROM context->>'customerId' THEN
            RAISE EXCEPTION 'fulfillment subscription link mismatch' USING ERRCODE='23514';
        END IF;
        expected:=expected||ARRAY['SUBSCRIPTION','PROVISIONING'];
    END IF;
    SELECT array_agg(effect ORDER BY effect) INTO expected FROM unnest(expected) effect;
    IF frozen.required_effects IS DISTINCT FROM expected OR string_to_array(payload[11],',') IS DISTINCT FROM expected OR
        (SELECT array_agg(effect ORDER BY effect) FROM jsonb_array_elements_text(body->'effects') effect) IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'fulfillment applicability mismatch' USING ERRCODE='23514';
    END IF;
    IF live THEN
        SELECT * INTO STRICT job FROM work_order WHERE tenant_id=target_tenant AND id=frozen.work_order_id FOR UPDATE;
        IF job.warehouse_revision<>frozen.work_order_revision OR job.approval_status<>'APPROVED' OR job.status<>'DONE' OR
            job.approved_by IS DISTINCT FROM frozen.approved_by OR job.completed_by IS NOT DISTINCT FROM job.approved_by OR
            context->>'customerId' IS DISTINCT FROM job.customer_id::text OR context->>'subscriptionId' IS DISTINCT FROM job.subscription_id::text OR
            context->>'orderId' IS DISTINCT FROM job.order_id::text OR context->>'areaId' IS DISTINCT FROM job.area_id::text OR
            context->>'workType' IS DISTINCT FROM job.type OR body->'workOrder'->>'proofHash' IS DISTINCT FROM job.proof_of_work_hash OR
            body->'workOrder'->>'completedBy' IS DISTINCT FROM job.completed_by::text OR
            EXISTS (SELECT FROM inventory_material_plan WHERE tenant_id=target_tenant AND work_order_id=job.id AND plan_revision>plan.plan_revision) OR
            NOT EXISTS (SELECT FROM inventory_material_usage use_header JOIN work_order_assignee assigned
                ON assigned.tenant_id=use_header.tenant_id AND assigned.technician_id=use_header.actor_id AND assigned.work_order_id=job.id
                WHERE use_header.tenant_id=target_tenant AND use_header.id=usage.id) THEN
            RAISE EXCEPTION 'fulfillment snapshot is stale' USING ERRCODE='23514';
        END IF;
        IF jsonb_array_length(material->'documents')<>(SELECT count(*) FROM inventory_document WHERE tenant_id=target_tenant AND work_order_id=job.id) THEN
            RAISE EXCEPTION 'fulfillment source documents changed' USING ERRCODE='23514';
        END IF;
        FOR item IN SELECT value FROM jsonb_array_elements(material->'documents') LOOP
            IF NOT EXISTS (SELECT FROM inventory_document WHERE tenant_id=target_tenant AND id=(item->>'id')::uuid
                AND work_order_id=job.id AND revision=(item->>'revision')::bigint) THEN
                RAISE EXCEPTION 'fulfillment source revision changed' USING ERRCODE='23514';
            END IF;
        END LOOP;
        PERFORM warehouse_assert_material_submission(target_tenant,plan.id);
        PERFORM warehouse_assert_material_usage(target_tenant,usage.id);
    END IF;
END $$;

CREATE FUNCTION warehouse_assert_fulfillment_completion(target_tenant uuid,target_id uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE frozen fulfillment_approval_snapshot%ROWTYPE; checkpoint fulfillment_checkpoint%ROWTYPE; receipt inventory_material_settlement%ROWTYPE;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO STRICT frozen FROM fulfillment_approval_snapshot WHERE tenant_id=target_tenant AND id=target_id;
    SELECT * INTO STRICT checkpoint FROM fulfillment_checkpoint WHERE tenant_id=target_tenant AND namespace=frozen.namespace AND operation_key=frozen.operation_key;
    IF checkpoint.canonical_hash<>frozen.payload_hash OR checkpoint.work_order_id IS DISTINCT FROM frozen.work_order_id OR
        checkpoint.approval_actor_id IS DISTINCT FROM frozen.approved_by OR checkpoint.source<>'WORK_ORDER' OR
        (SELECT array_agg(effect ORDER BY effect) FROM unnest(string_to_array(checkpoint.required_effects,',')) effect) IS DISTINCT FROM frozen.required_effects THEN
        RAISE EXCEPTION 'fulfillment checkpoint binding mismatch' USING ERRCODE='23514';
    END IF;
    IF checkpoint.state='APPLIED' THEN
        SELECT * INTO STRICT receipt FROM inventory_material_settlement WHERE tenant_id=target_tenant AND id=frozen.id;
        IF receipt.usage_id<>frozen.usage_id OR receipt.use_revision<>frozen.use_revision OR receipt.plan_id<>frozen.plan_id OR
            receipt.payload_hash<>frozen.payload_hash OR receipt.material_mode<>frozen.material_mode OR
            receipt.body::jsonb IS DISTINCT FROM jsonb_build_object('approvalId',receipt.id,'usageId',receipt.usage_id,'useRevision',receipt.use_revision,'result',receipt.result) OR
            (SELECT array_agg(effect_type ORDER BY effect_type) FROM fulfillment_effect_progress
                WHERE tenant_id=target_tenant AND fulfillment_id=checkpoint.id AND status='COMPLETED') IS DISTINCT FROM frozen.required_effects OR
            NOT EXISTS (SELECT FROM workorder_fulfillment_result WHERE tenant_id=target_tenant AND namespace=frozen.namespace
                AND operation_key=frozen.operation_key AND payload_hash=frozen.payload_hash AND work_order_id=frozen.work_order_id AND result='APPLIED') THEN
            RAISE EXCEPTION 'fulfillment requires exact owner verification receipts' USING ERRCODE='23514';
        END IF;
    END IF;
END $$;

CREATE FUNCTION warehouse_fulfillment_bound_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target_tenant uuid; target uuid; checkpoint_id uuid; reference_namespace text; reference_key text;
BEGIN
    target_tenant:=CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    IF TG_TABLE_NAME='fulfillment_approval_snapshot' THEN
        target:=NEW.id;
        IF NEW.created_xid<>pg_current_xact_id() THEN RAISE EXCEPTION 'fulfillment snapshot creation transaction mismatch' USING ERRCODE='23514'; END IF;
        PERFORM warehouse_assert_fulfillment_snapshot(target_tenant,target,true);
    ELSIF TG_TABLE_NAME='inventory_material_settlement' THEN
        target:=NEW.id;
        PERFORM warehouse_assert_fulfillment_snapshot(target_tenant,target,true);
        IF EXISTS (SELECT FROM inventory_operation WHERE tenant_id=target_tenant AND id=target) OR
            EXISTS (SELECT FROM inventory_movement WHERE tenant_id=target_tenant AND operation_id=target) THEN
            RAISE EXCEPTION 'settlement verification cannot post stock' USING ERRCODE='23514';
        END IF;
    ELSE
        IF TG_TABLE_NAME='fulfillment_effect_progress' THEN
            checkpoint_id:=CASE WHEN TG_OP='DELETE' THEN OLD.fulfillment_id ELSE NEW.fulfillment_id END;
            SELECT namespace,operation_key INTO reference_namespace,reference_key FROM fulfillment_checkpoint WHERE tenant_id=target_tenant AND id=checkpoint_id;
        ELSE
            reference_namespace:=CASE WHEN TG_OP='DELETE' THEN OLD.namespace ELSE NEW.namespace END;
            reference_key:=CASE WHEN TG_OP='DELETE' THEN OLD.operation_key ELSE NEW.operation_key END;
        END IF;
        SELECT id INTO target FROM fulfillment_approval_snapshot WHERE tenant_id=target_tenant AND namespace=reference_namespace AND operation_key=reference_key;
        IF TG_TABLE_NAME='fulfillment_checkpoint' AND TG_OP<>'DELETE' THEN
            IF NEW.source='WORK_ORDER' AND NEW.state='APPLIED' AND target IS NULL THEN
                RAISE EXCEPTION 'legacy fulfillment requires explicit snapshot reconciliation' USING ERRCODE='23514';
            END IF;
        END IF;
    END IF;
    IF target IS NOT NULL THEN PERFORM warehouse_assert_fulfillment_completion(target_tenant,target); END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['fulfillment_approval_snapshot','inventory_material_settlement','fulfillment_checkpoint',
        'fulfillment_effect_progress','workorder_fulfillment_result'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_fulfillment_bound AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_bound_guard()',table_name);
    END LOOP;
END $$;
