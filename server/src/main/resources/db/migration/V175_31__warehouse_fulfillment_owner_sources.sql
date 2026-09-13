CREATE FUNCTION warehouse_assert_fulfillment_owner_sources(target_tenant uuid,target_id uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE frozen fulfillment_approval_snapshot%ROWTYPE; job work_order%ROWTYPE; ordered order_record%ROWTYPE;
    visited fieldservice_visit%ROWTYPE; subscribed subscription%ROWTYPE; access subscriber_access%ROWTYPE;
    body jsonb; context jsonb; reference jsonb; completed boolean; expected_revision bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO frozen FROM fulfillment_approval_snapshot WHERE tenant_id=target_tenant AND id=target_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'FULFILLMENT_SNAPSHOT_BINDING' USING ERRCODE='23514'; END IF;
    SELECT * INTO job FROM work_order WHERE tenant_id=target_tenant AND id=frozen.work_order_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'FULFILLMENT_WORK_ORDER_BINDING' USING ERRCODE='23514'; END IF;
    body:=frozen.snapshot::jsonb; context:=body->'workOrder'->'material';
    SELECT EXISTS(SELECT FROM fulfillment_checkpoint WHERE tenant_id=target_tenant AND namespace=frozen.namespace
        AND operation_key=frozen.operation_key AND state='APPLIED') INTO completed;
    IF job.order_id IS DISTINCT FROM (context->>'orderId')::uuid OR job.customer_id IS DISTINCT FROM (context->>'customerId')::uuid OR
        job.subscription_id IS DISTINCT FROM (context->>'subscriptionId')::uuid THEN
        RAISE EXCEPTION 'FULFILLMENT_WORK_ORDER_BINDING' USING ERRCODE='23514';
    END IF;
    IF job.order_id IS NOT NULL THEN
        SELECT * INTO ordered FROM order_record WHERE tenant_id=target_tenant AND id=job.order_id;
        expected_revision:=(body->>'orderRevision')::bigint;
        IF NOT FOUND OR job.customer_id IS NULL OR ordered.customer_id IS DISTINCT FROM job.customer_id OR expected_revision IS NULL OR
            ordered.revision<>expected_revision+(CASE WHEN completed THEN 1 ELSE 0 END) OR
            ordered.status<>(CASE WHEN completed THEN 'FULFILLED' ELSE 'FULFILLING' END) THEN
            RAISE EXCEPTION 'FULFILLMENT_ORDER_BINDING' USING ERRCODE='23514';
        END IF;
        reference:=body->'orderBinding';
        IF reference IS NOT NULL AND reference<>'null'::jsonb AND
            (reference->>'tenantId' IS DISTINCT FROM target_tenant::text OR reference->>'orderId' IS DISTINCT FROM ordered.id::text OR
            reference->>'customerId' IS DISTINCT FROM ordered.customer_id::text OR reference->>'revision' IS DISTINCT FROM expected_revision::text OR
            reference->>'state' IS DISTINCT FROM 'FULFILLING') THEN
            RAISE EXCEPTION 'FULFILLMENT_ORDER_BINDING' USING ERRCODE='23514';
        END IF;
    END IF;
    reference:=body->'visit';
    IF reference IS NOT NULL AND reference<>'null'::jsonb THEN
        SELECT * INTO visited FROM fieldservice_visit WHERE tenant_id=target_tenant AND id=(reference->>'id')::uuid;
        expected_revision:=(reference->>'revision')::bigint;
        IF NOT FOUND OR visited.work_order_id<>job.id OR reference->>'tenantId' IS DISTINCT FROM target_tenant::text OR
            reference->>'workOrderId' IS DISTINCT FROM job.id::text OR reference->>'orderId' IS DISTINCT FROM visited.order_id::text OR
            reference->>'technicianId' IS DISTINCT FROM visited.technician_id::text OR reference->>'state' IS DISTINCT FROM 'CHECKED_OUT' OR
            expected_revision IS NULL OR visited.revision<>expected_revision+(CASE WHEN completed THEN 1 ELSE 0 END) OR
            visited.state<>(CASE WHEN completed THEN 'SUBMITTED' ELSE 'CHECKED_OUT' END) OR (NOT completed AND NOT visited.assignment_active) OR
            NOT EXISTS(SELECT FROM work_order_assignee WHERE tenant_id=target_tenant AND work_order_id=job.id AND technician_id=visited.technician_id) THEN
            RAISE EXCEPTION 'FULFILLMENT_VISIT_BINDING' USING ERRCODE='23514';
        END IF;
    ELSIF frozen.created_xid=pg_current_xact_id() AND EXISTS(SELECT FROM fieldservice_visit WHERE tenant_id=target_tenant AND work_order_id=job.id) THEN
        RAISE EXCEPTION 'FULFILLMENT_VISIT_BINDING' USING ERRCODE='23514';
    END IF;
    IF job.subscription_id IS NOT NULL THEN
        SELECT * INTO subscribed FROM subscription WHERE tenant_id=target_tenant AND id=job.subscription_id;
        IF NOT FOUND OR subscribed.customer_id IS DISTINCT FROM job.customer_id OR body->'subscription'->>'id' IS DISTINCT FROM subscribed.id::text OR
            body->'subscription'->>'customerId' IS DISTINCT FROM subscribed.customer_id::text THEN
            RAISE EXCEPTION 'FULFILLMENT_SUBSCRIPTION_BINDING' USING ERRCODE='23514';
        END IF;
        IF NOT completed AND body->'customerBinding'->>'subscriptionRevision' IS DISTINCT FROM
            encode(sha256(convert_to(to_jsonb(subscribed)::text,'UTF8')),'hex') THEN
            RAISE EXCEPTION 'FULFILLMENT_SUBSCRIPTION_BINDING' USING ERRCODE='23514';
        END IF;
    END IF;
    IF body->>'bngAccessId' IS NOT NULL THEN
        SELECT * INTO access FROM subscriber_access WHERE tenant_id=target_tenant AND id=(body->>'bngAccessId')::uuid;
        reference:=body->'bngBinding';
        IF NOT FOUND OR access.subscription_id IS DISTINCT FROM job.subscription_id OR access.customer_id IS DISTINCT FROM job.customer_id OR
            reference->>'tenantId' IS DISTINCT FROM target_tenant::text OR reference->>'accessId' IS DISTINCT FROM access.id::text OR
            reference->>'subscriptionId' IS DISTINCT FROM access.subscription_id::text OR reference->>'customerId' IS DISTINCT FROM access.customer_id::text THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_BINDING' USING ERRCODE='23514';
        END IF;
        IF NOT completed AND reference->>'revision' IS DISTINCT FROM encode(sha256(convert_to(to_jsonb(access)::text,'UTF8')),'hex') THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_BINDING' USING ERRCODE='23514';
        END IF;
    END IF;
END $$;

CREATE FUNCTION warehouse_assert_fulfillment_owner_receipts(target_tenant uuid,target_id uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE frozen fulfillment_approval_snapshot%ROWTYPE; checkpoint fulfillment_checkpoint%ROWTYPE; body jsonb; owner_operation order_operation%ROWTYPE;
    customer_receipt customer_fulfillment_receipt%ROWTYPE; bng_receipt bng_fulfillment_receipt%ROWTYPE;
    visit_receipt fieldservice_fulfillment_receipt%ROWTYPE; expected_action text; target_action uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO frozen FROM fulfillment_approval_snapshot WHERE tenant_id=target_tenant AND id=target_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'FULFILLMENT_SNAPSHOT_BINDING' USING ERRCODE='23514'; END IF;
    SELECT * INTO checkpoint FROM fulfillment_checkpoint WHERE tenant_id=target_tenant AND namespace=frozen.namespace AND operation_key=frozen.operation_key;
    IF NOT FOUND THEN RAISE EXCEPTION 'FULFILLMENT_HANDOFF_BINDING' USING ERRCODE='23514'; END IF;
    body:=frozen.snapshot::jsonb;
    IF (SELECT count(*) FROM fulfillment_outbox WHERE tenant_id=target_tenant AND fulfillment_id=checkpoint.id)<>1 OR
        NOT EXISTS(SELECT FROM fulfillment_outbox WHERE tenant_id=target_tenant AND fulfillment_id=checkpoint.id AND sequence=1
            AND event_type='FULFILLMENT_APPLY' AND payload_hash=frozen.payload_hash AND payload=frozen.request_payload) THEN
        RAISE EXCEPTION 'FULFILLMENT_HANDOFF_BINDING' USING ERRCODE='23514';
    END IF;
    IF EXISTS(SELECT FROM customer_fulfillment_receipt WHERE tenant_id=target_tenant AND id=target_id) AND NOT ('SUBSCRIPTION'=ANY(frozen.required_effects)) OR
        EXISTS(SELECT FROM bng_fulfillment_receipt WHERE tenant_id=target_tenant AND id=target_id) AND NOT ('PROVISIONING'=ANY(frozen.required_effects)) OR
        EXISTS(SELECT FROM fieldservice_fulfillment_receipt WHERE tenant_id=target_tenant AND id=target_id) AND NOT ('VISIT'=ANY(frozen.required_effects)) THEN
        RAISE EXCEPTION 'FULFILLMENT_EXTRA_OWNER_RECEIPT' USING ERRCODE='23514';
    END IF;
    IF checkpoint.state<>'APPLIED' THEN RETURN; END IF;
    PERFORM warehouse_assert_fulfillment_completion(target_tenant,target_id);
    IF 'ORDER'=ANY(frozen.required_effects) THEN
        SELECT * INTO owner_operation FROM order_operation WHERE tenant_id=target_tenant AND namespace=frozen.namespace AND operation_key=frozen.operation_key;
        IF NOT FOUND OR owner_operation.payload_hash<>frozen.payload_hash OR
            owner_operation.outcome_json::jsonb->>'id' IS DISTINCT FROM body->'workOrder'->'material'->>'orderId' OR
            owner_operation.outcome_json::jsonb->>'tenantId' IS DISTINCT FROM target_tenant::text OR
            owner_operation.outcome_json::jsonb->>'customerId' IS DISTINCT FROM body->'workOrder'->'material'->>'customerId' OR
            owner_operation.outcome_json::jsonb->>'status' IS DISTINCT FROM 'FULFILLED' OR
            (owner_operation.outcome_json::jsonb->>'revision')::bigint IS DISTINCT FROM (body->>'orderRevision')::bigint+1 THEN
            RAISE EXCEPTION 'FULFILLMENT_ORDER_RECEIPT' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT FROM order_fulfillment_transition WHERE tenant_id=target_tenant AND approval_id=target_id
            AND owner_id=(body->'workOrder'->'material'->>'orderId')::uuid AND source_state='FULFILLING' AND result_state='FULFILLED'
            AND source_revision=(body->>'orderRevision')::bigint AND result_revision=source_revision+1
            AND transaction_id=owner_operation.fulfillment_xid) THEN
            RAISE EXCEPTION 'FULFILLMENT_ORDER_TRANSITION' USING ERRCODE='23514';
        END IF;
    END IF;
    IF 'VISIT'=ANY(frozen.required_effects) THEN
        SELECT * INTO visit_receipt FROM fieldservice_fulfillment_receipt WHERE tenant_id=target_tenant AND id=target_id;
        IF NOT FOUND OR visit_receipt.visit_id::text IS DISTINCT FROM body->'visit'->>'id' OR visit_receipt.payload_hash<>frozen.payload_hash OR
            visit_receipt.namespace<>frozen.namespace OR visit_receipt.operation_key<>frozen.operation_key OR
            visit_receipt.source_revision IS DISTINCT FROM (body->'visit'->>'revision')::bigint OR
            visit_receipt.actor_id::text IS DISTINCT FROM body->'visit'->>'technicianId' OR
            NOT EXISTS(SELECT FROM fieldservice_visit_operation WHERE tenant_id=target_tenant AND id=visit_receipt.operation_id
                AND visit_id=visit_receipt.visit_id AND namespace=frozen.namespace AND operation_key=frozen.operation_key
                AND payload_hash=frozen.payload_hash AND result='SUBMITTED') THEN
            RAISE EXCEPTION 'FULFILLMENT_VISIT_RECEIPT' USING ERRCODE='23514';
        END IF;
        IF NOT EXISTS(SELECT FROM fieldservice_fulfillment_transition WHERE tenant_id=target_tenant AND approval_id=target_id
            AND owner_id=visit_receipt.visit_id AND source_state='CHECKED_OUT' AND result_state='SUBMITTED'
            AND source_revision=visit_receipt.source_revision AND result_revision=visit_receipt.result_revision AND transaction_id=visit_receipt.created_xid) THEN
            RAISE EXCEPTION 'FULFILLMENT_VISIT_TRANSITION' USING ERRCODE='23514';
        END IF;
    END IF;
    expected_action:=CASE WHEN body->'workOrder'->'material'->>'action'='REMOVE' THEN 'TERMINATE' ELSE 'ACTIVATE' END;
    IF 'SUBSCRIPTION'=ANY(frozen.required_effects) THEN
        SELECT * INTO customer_receipt FROM customer_fulfillment_receipt WHERE tenant_id=target_tenant AND id=target_id;
        IF NOT FOUND OR customer_receipt.customer_id::text IS DISTINCT FROM body->'workOrder'->'material'->>'customerId' OR
            customer_receipt.subscription_id::text IS DISTINCT FROM body->'workOrder'->'material'->>'subscriptionId' OR
            customer_receipt.actor_id<>frozen.approved_by OR customer_receipt.namespace<>frozen.namespace OR customer_receipt.operation_key<>frozen.operation_key OR
            customer_receipt.payload_hash<>frozen.payload_hash OR customer_receipt.action<>expected_action OR
            customer_receipt.source_state IS DISTINCT FROM body->'subscription'->>'status' OR
            customer_receipt.source_hash IS DISTINCT FROM body->'customerBinding'->>'subscriptionRevision' THEN
            RAISE EXCEPTION 'FULFILLMENT_SUBSCRIPTION_RECEIPT' USING ERRCODE='23514';
        END IF;
        IF customer_receipt.created_xid=pg_current_xact_id() AND NOT EXISTS(SELECT FROM subscription current WHERE tenant_id=target_tenant
            AND id=customer_receipt.subscription_id AND status=customer_receipt.result_state
            AND encode(sha256(convert_to(to_jsonb(current)::text,'UTF8')),'hex')=customer_receipt.result_hash) THEN
            RAISE EXCEPTION 'FULFILLMENT_SUBSCRIPTION_RESULT' USING ERRCODE='23514';
        END IF;
        IF customer_receipt.source_hash<>customer_receipt.result_hash AND NOT EXISTS(SELECT FROM customer_fulfillment_transition
            WHERE tenant_id=target_tenant AND approval_id=target_id AND owner_id=customer_receipt.subscription_id
                AND source_hash=customer_receipt.source_hash AND result_hash=customer_receipt.result_hash AND transaction_id=customer_receipt.created_xid) THEN
            RAISE EXCEPTION 'FULFILLMENT_SUBSCRIPTION_TRANSITION' USING ERRCODE='23514';
        END IF;
    END IF;
    IF 'PROVISIONING'=ANY(frozen.required_effects) THEN
        SELECT * INTO bng_receipt FROM bng_fulfillment_receipt WHERE tenant_id=target_tenant AND id=target_id;
        IF NOT FOUND OR bng_receipt.access_id::text IS DISTINCT FROM body->>'bngAccessId' OR bng_receipt.payload_hash<>frozen.payload_hash OR
            bng_receipt.customer_id::text IS DISTINCT FROM body->'workOrder'->'material'->>'customerId' OR
            bng_receipt.subscription_id::text IS DISTINCT FROM body->'workOrder'->'material'->>'subscriptionId' OR
            bng_receipt.actor_id<>frozen.approved_by OR bng_receipt.namespace<>frozen.namespace OR bng_receipt.operation_key<>frozen.operation_key OR
            bng_receipt.action<>expected_action OR bng_receipt.source_state IS DISTINCT FROM body->'bngBinding'->>'state' OR
            bng_receipt.source_hash IS DISTINCT FROM body->'bngBinding'->>'revision' THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_RECEIPT' USING ERRCODE='23514';
        END IF;
        IF bng_receipt.created_xid=pg_current_xact_id() AND NOT EXISTS(SELECT FROM subscriber_access current WHERE tenant_id=target_tenant
            AND id=bng_receipt.access_id AND status=bng_receipt.result_state
            AND nas_id IS NOT DISTINCT FROM (body->'bngBinding'->>'nasId')::uuid
            AND encode(sha256(convert_to(to_jsonb(current)::text,'UTF8')),'hex')=bng_receipt.result_hash) THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_RESULT' USING ERRCODE='23514';
        END IF;
        IF bng_receipt.source_hash<>bng_receipt.result_hash AND NOT EXISTS(SELECT FROM bng_fulfillment_transition
            WHERE tenant_id=target_tenant AND approval_id=target_id AND owner_id=bng_receipt.access_id
                AND source_hash=bng_receipt.source_hash AND result_hash=bng_receipt.result_hash AND transaction_id=bng_receipt.created_xid) THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_TRANSITION' USING ERRCODE='23514';
        END IF;
        FOREACH target_action IN ARRAY bng_receipt.action_ids LOOP
            IF NOT EXISTS(SELECT FROM bng_action WHERE tenant_id=target_tenant AND id=target_action AND subscriber_access_id=bng_receipt.access_id) THEN
                RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF' USING ERRCODE='23514';
            END IF;
        END LOOP;
        IF body->'bngBinding'->>'nasId' IS NOT NULL AND
            ((bng_receipt.action='ACTIVATE' AND bng_receipt.source_state='PENDING') OR
             (bng_receipt.action='TERMINATE' AND bng_receipt.source_state<>'TERMINATED')) AND cardinality(bng_receipt.action_ids)=0 THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF' USING ERRCODE='23514';
        END IF;
    END IF;
END $$;
