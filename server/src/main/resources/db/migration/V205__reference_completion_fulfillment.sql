ALTER TABLE fulfillment_approval_snapshot ADD COLUMN source varchar(24) NOT NULL DEFAULT 'WORK_ORDER'
    CHECK(source IN ('WORK_ORDER','REFERENCE_WORK_ORDER')), ADD COLUMN fulfillment_actor_id uuid;
ALTER TABLE fulfillment_approval_snapshot DISABLE TRIGGER warehouse_append_only;
ALTER TABLE fulfillment_approval_snapshot DISABLE TRIGGER warehouse_fulfillment_bound;
ALTER TABLE fulfillment_approval_snapshot DISABLE TRIGGER warehouse_fulfillment_owner_bound;
UPDATE fulfillment_approval_snapshot SET fulfillment_actor_id=approved_by;
ALTER TABLE fulfillment_approval_snapshot ENABLE TRIGGER warehouse_append_only;
ALTER TABLE fulfillment_approval_snapshot ENABLE TRIGGER warehouse_fulfillment_bound;
ALTER TABLE fulfillment_approval_snapshot ENABLE TRIGGER warehouse_fulfillment_owner_bound;
ALTER TABLE fulfillment_approval_snapshot ALTER COLUMN approved_by DROP NOT NULL, ALTER COLUMN plan_id DROP NOT NULL,
    ALTER COLUMN fulfillment_actor_id SET NOT NULL;
ALTER TABLE fulfillment_approval_snapshot ADD FOREIGN KEY(tenant_id,fulfillment_actor_id) REFERENCES app_user(tenant_id,id);
ALTER TABLE fulfillment_approval_snapshot DROP CONSTRAINT fulfillment_material_source_shape;
ALTER TABLE fulfillment_approval_snapshot ADD CONSTRAINT fulfillment_material_source_shape CHECK(
    (source='WORK_ORDER' AND approved_by IS NOT NULL AND fulfillment_actor_id=approved_by AND plan_id IS NOT NULL
        AND (usage_id IS NOT NULL OR material_mode='MATERIAL_REQUIRED')) OR
    (source='REFERENCE_WORK_ORDER' AND approved_by IS NULL AND plan_id IS NULL AND usage_id IS NULL
        AND namespace='workorder.fulfillment.complete'));

CREATE FUNCTION warehouse_fulfillment_actor_default() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF NEW.source='WORK_ORDER' AND NEW.fulfillment_actor_id IS NULL THEN NEW.fulfillment_actor_id:=NEW.approved_by; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_fulfillment_actor_default BEFORE INSERT ON fulfillment_approval_snapshot
    FOR EACH ROW EXECUTE FUNCTION warehouse_fulfillment_actor_default();

CREATE TABLE fulfillment_reference_material_receipt(
    tenant_id uuid NOT NULL REFERENCES tenant(id), id uuid NOT NULL, work_order_id uuid NOT NULL,
    completion_hash varchar(64) NOT NULL, document_id uuid, payload_hash varchar(64) NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,id) REFERENCES fulfillment_approval_snapshot(tenant_id,id),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order_reference_completion(tenant_id,work_order_id),
    FOREIGN KEY(tenant_id,document_id) REFERENCES inventory_document(tenant_id,id)
);
ALTER TABLE fulfillment_reference_material_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE fulfillment_reference_material_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fulfillment_reference_material_receipt
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON fulfillment_reference_material_receipt
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE UPDATE,DELETE,TRUNCATE ON fulfillment_reference_material_receipt FROM warehouse_app;
        GRANT SELECT,INSERT ON fulfillment_reference_material_receipt TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_assert_reference_fulfillment_snapshot(scope uuid,target uuid,live boolean) RETURNS void LANGUAGE plpgsql AS $$
DECLARE frozen fulfillment_approval_snapshot; completion work_order_reference_completion; job work_order;
    command work_order_reference_command; body jsonb; context jsonb; payload text[]; expected text[]; roster jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT frozen FROM fulfillment_approval_snapshot WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT completion FROM work_order_reference_completion WHERE tenant_id=scope AND work_order_id=frozen.work_order_id;
    SELECT * INTO STRICT job FROM work_order WHERE tenant_id=scope AND id=frozen.work_order_id FOR UPDATE;
    SELECT * INTO STRICT command FROM work_order_reference_command WHERE tenant_id=scope AND resource_kind='WO'
        AND resource_id=job.id AND revision=completion.revision AND action='COMPLETE';
    PERFORM warehouse_assert_reference_completion(scope,job.id);
    IF completion.document_id IS NOT NULL THEN PERFORM warehouse_assert_reference_consume(scope,completion.document_id); END IF;
    body:=frozen.snapshot::jsonb; context:=body#>'{workOrder,material}'; payload:=string_to_array(frozen.request_payload,'|');
    SELECT coalesce(jsonb_agg(technician_id::text ORDER BY technician_id),'[]') INTO roster
        FROM work_order_assignee WHERE tenant_id=scope AND work_order_id=job.id;
    IF frozen.source<>'REFERENCE_WORK_ORDER' OR frozen.namespace<>'workorder.fulfillment.complete' OR frozen.approved_by IS NOT NULL
        OR frozen.plan_id IS NOT NULL OR frozen.usage_id IS NOT NULL OR frozen.fulfillment_actor_id IS DISTINCT FROM completion.technician_id
        OR frozen.use_revision<>completion.revision OR frozen.work_order_revision<>job.warehouse_revision
        OR frozen.material_mode IS DISTINCT FROM (CASE WHEN jsonb_array_length(completion.proof_canonical::jsonb->'materials')=0 THEN 'NONE' ELSE 'MATERIAL_REQUIRED' END)
        OR frozen.payload_hash IS DISTINCT FROM encode(sha256(convert_to(frozen.snapshot,'UTF8')),'hex')
        OR body->>'id' IS DISTINCT FROM frozen.id::text OR body#>>'{identity,tenantId}' IS DISTINCT FROM scope::text
        OR body#>>'{identity,userId}' IS DISTINCT FROM completion.technician_id::text
        OR body->>'cutoverEpoch' IS DISTINCT FROM command.cutover_epoch::text
        OR body#>>'{workOrder,approvedBy}' IS NOT NULL OR body#>>'{workOrder,completedBy}' IS DISTINCT FROM completion.technician_id::text
        OR body#>>'{workOrder,proofHash}' IS DISTINCT FROM completion.proof_hash
        OR body->'referenceCompletion' IS DISTINCT FROM completion.proof_canonical::jsonb
        OR coalesce(body->'material','null'::jsonb)<>'null'::jsonb
        OR context IS DISTINCT FROM jsonb_build_object('workOrderId',job.id,'code',job.code,'customerId',job.customer_id,
            'subscriptionId',job.subscription_id,'orderId',job.order_id,'visitId',null,'areaId',job.area_id,
            'activeAssigneeIds',roster,'active',false,'cancelled',false,'action',CASE job.type
                WHEN 'PSB' THEN 'INSTALL' WHEN 'MIGRATION' THEN 'REPLACE' WHEN 'DISMANTLE' THEN 'REMOVE' ELSE job.type END,
            'workType',job.type,'workOrderRevision',job.warehouse_revision,'scheduledAt',context->'scheduledAt','scheduledEndAt',null)
        OR (context->>'scheduledAt')::timestamptz IS DISTINCT FROM job.scheduled_at
        OR roster IS DISTINCT FROM jsonb_build_array(completion.technician_id::text)
        OR cardinality(payload)<>13 OR payload[1] IS DISTINCT FROM scope::text OR payload[2] IS DISTINCT FROM frozen.namespace
        OR payload[3] IS DISTINCT FROM frozen.operation_key OR payload[4] IS DISTINCT FROM frozen.payload_hash
        OR payload[5] IS DISTINCT FROM frozen.source OR payload[6] IS DISTINCT FROM job.id::text
        OR payload[7] IS DISTINCT FROM coalesce(job.subscription_id::text,'') OR payload[8] IS DISTINCT FROM job.id::text
        OR payload[9] IS DISTINCT FROM job.type OR payload[10] IS DISTINCT FROM 'false'
        OR payload[12] IS DISTINCT FROM coalesce(job.order_id::text,'') OR payload[13] IS DISTINCT FROM completion.technician_id::text
        OR frozen.operation_key IS DISTINCT FROM job.id||':'||job.warehouse_revision||':'||completion.proof_hash THEN
        RAISE EXCEPTION 'reference fulfillment must bind its exact completed assignment and consumption' USING ERRCODE='23514'; END IF;
    expected:=ARRAY['INVENTORY','WORK_ORDER'];
    IF job.order_id IS NOT NULL THEN expected:=array_append(expected,'ORDER'); END IF;
    IF body->>'visit' IS NOT NULL THEN expected:=array_append(expected,'VISIT'); END IF;
    IF job.subscription_id IS NOT NULL AND job.customer_id IS NOT NULL AND context->>'action' IN ('INSTALL','REMOVE') THEN
        expected:=array_append(expected,'SUBSCRIPTION');
        IF body->>'bngAccessId' IS NOT NULL THEN expected:=array_append(expected,'PROVISIONING'); END IF;
    END IF;
    SELECT array_agg(effect ORDER BY effect) INTO expected FROM unnest(expected) effect;
    IF frozen.required_effects IS DISTINCT FROM expected OR string_to_array(payload[11],',') IS DISTINCT FROM expected
        OR (SELECT array_agg(effect ORDER BY effect) FROM jsonb_array_elements_text(body->'effects') effect) IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'reference fulfillment applicability differs from its owner links' USING ERRCODE='23514'; END IF;
    IF live AND NOT EXISTS(SELECT FROM inventory_tenant_cutover WHERE tenant_id=scope AND state='ENFORCED'
        AND workflow_mode='REFERENCE' AND epoch=command.cutover_epoch FOR SHARE) THEN
        RAISE EXCEPTION 'reference fulfillment writer epoch changed' USING ERRCODE='23514'; END IF;
    IF frozen.created_xid=pg_current_xact_id() AND completion.created_xid<>pg_current_xact_id() THEN
        RAISE EXCEPTION 'reference fulfillment must freeze in its completion transaction' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_assert_reference_fulfillment_completion(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE frozen fulfillment_approval_snapshot; checkpoint fulfillment_checkpoint; completion work_order_reference_completion;
    receipt fulfillment_reference_material_receipt;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT frozen FROM fulfillment_approval_snapshot WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT completion FROM work_order_reference_completion WHERE tenant_id=scope AND work_order_id=frozen.work_order_id;
    SELECT * INTO STRICT checkpoint FROM fulfillment_checkpoint WHERE tenant_id=scope AND namespace=frozen.namespace AND operation_key=frozen.operation_key;
    IF checkpoint.source IS DISTINCT FROM 'REFERENCE_WORK_ORDER' OR checkpoint.canonical_hash IS DISTINCT FROM frozen.payload_hash
        OR checkpoint.target_id IS DISTINCT FROM frozen.work_order_id OR checkpoint.work_order_id IS DISTINCT FROM frozen.work_order_id
        OR checkpoint.approval_actor_id IS DISTINCT FROM frozen.fulfillment_actor_id
        OR checkpoint.subscription_id IS DISTINCT FROM (frozen.snapshot::jsonb#>>'{workOrder,material,subscriptionId}')::uuid
        OR checkpoint.order_id IS DISTINCT FROM (frozen.snapshot::jsonb#>>'{workOrder,material,orderId}')::uuid
        OR checkpoint.work_order_kind IS DISTINCT FROM frozen.snapshot::jsonb#>>'{workOrder,material,workType}'
        OR string_to_array(checkpoint.required_effects,',') IS DISTINCT FROM frozen.required_effects THEN
        RAISE EXCEPTION 'reference fulfillment checkpoint differs from its immutable snapshot' USING ERRCODE='23514'; END IF;
    SELECT * INTO receipt FROM fulfillment_reference_material_receipt WHERE tenant_id=scope AND id=target;
    IF receipt.id IS NOT NULL AND (receipt.work_order_id IS DISTINCT FROM frozen.work_order_id
        OR receipt.completion_hash IS DISTINCT FROM completion.proof_hash OR receipt.document_id IS DISTINCT FROM completion.document_id
        OR receipt.payload_hash IS DISTINCT FROM frozen.payload_hash) THEN
        RAISE EXCEPTION 'reference material receipt differs from original consumption' USING ERRCODE='23514'; END IF;
    IF checkpoint.state='APPLIED' AND (receipt.id IS NULL
        OR EXISTS(SELECT FROM inventory_material_settlement WHERE tenant_id=scope AND id=target)
        OR (SELECT array_agg(effect_type ORDER BY effect_type) FROM fulfillment_effect_progress
            WHERE tenant_id=scope AND fulfillment_id=checkpoint.id AND status='COMPLETED') IS DISTINCT FROM frozen.required_effects
        OR NOT EXISTS(SELECT FROM workorder_fulfillment_result WHERE tenant_id=scope AND work_order_id=frozen.work_order_id
            AND namespace=frozen.namespace AND operation_key=frozen.operation_key AND payload_hash=frozen.payload_hash
            AND source='REFERENCE_WORK_ORDER' AND result='APPLIED')) THEN
        RAISE EXCEPTION 'reference fulfillment requires every exact completion receipt' USING ERRCODE='23514'; END IF;
END $$;

ALTER FUNCTION warehouse_assert_fulfillment_snapshot(uuid,uuid,boolean) RENAME TO warehouse_assert_fulfillment_snapshot_v204;
CREATE FUNCTION warehouse_assert_fulfillment_snapshot(scope uuid,target uuid,live boolean) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
    IF EXISTS(SELECT FROM fulfillment_approval_snapshot WHERE tenant_id=scope AND id=target AND source='REFERENCE_WORK_ORDER') THEN
        PERFORM warehouse_assert_reference_fulfillment_snapshot(scope,target,live);
    ELSE PERFORM warehouse_assert_fulfillment_snapshot_v204(scope,target,live); END IF;
END $$;
ALTER FUNCTION warehouse_assert_fulfillment_completion(uuid,uuid) RENAME TO warehouse_assert_fulfillment_completion_v204;
CREATE FUNCTION warehouse_assert_fulfillment_completion(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
    IF EXISTS(SELECT FROM fulfillment_approval_snapshot WHERE tenant_id=scope AND id=target AND source='REFERENCE_WORK_ORDER') THEN
        PERFORM warehouse_assert_reference_fulfillment_completion(scope,target);
    ELSE PERFORM warehouse_assert_fulfillment_completion_v204(scope,target); END IF;
END $$;

CREATE FUNCTION warehouse_reference_fulfillment_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF NEW.created_xid<>pg_current_xact_id() OR NOT EXISTS(SELECT FROM fulfillment_checkpoint checkpoint
        JOIN fulfillment_approval_snapshot snapshot ON snapshot.tenant_id=checkpoint.tenant_id AND snapshot.namespace=checkpoint.namespace
            AND snapshot.operation_key=checkpoint.operation_key WHERE snapshot.tenant_id=NEW.tenant_id AND snapshot.id=NEW.id
            AND snapshot.source='REFERENCE_WORK_ORDER' AND checkpoint.state IN ('APPLYING','APPLIED')
            AND EXISTS(SELECT FROM fulfillment_effect_progress WHERE tenant_id=checkpoint.tenant_id AND fulfillment_id=checkpoint.id
                AND effect_type='INVENTORY' AND status='COMPLETED')) THEN
        RAISE EXCEPTION 'reference material verification requires its coordinator effect' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_reference_fulfillment_snapshot(NEW.tenant_id,NEW.id,true);
    PERFORM warehouse_assert_reference_fulfillment_completion(NEW.tenant_id,NEW.id);
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_reference_fulfillment_receipt AFTER INSERT ON fulfillment_reference_material_receipt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_fulfillment_receipt_guard();

CREATE FUNCTION warehouse_reference_completion_handoff_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT id INTO STRICT target FROM fulfillment_approval_snapshot WHERE tenant_id=NEW.tenant_id
        AND work_order_id=NEW.work_order_id AND source='REFERENCE_WORK_ORDER' AND created_xid=NEW.created_xid;
    PERFORM warehouse_assert_reference_fulfillment_snapshot(NEW.tenant_id,target,true);
    PERFORM warehouse_assert_reference_fulfillment_completion(NEW.tenant_id,target);
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_reference_completion_handoff AFTER INSERT ON work_order_reference_completion
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_completion_handoff_guard();

DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_fulfillment_deployments_guard()'::regprocedure);
    anchor:='material:=NEW.snapshot::jsonb->''material'';';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'fulfillment deployment guard anchor changed'; END IF;
    EXECUTE replace(definition,anchor,'IF NEW.source=''REFERENCE_WORK_ORDER'' THEN
        PERFORM warehouse_assert_reference_fulfillment_snapshot(NEW.tenant_id,NEW.id,true); RETURN NULL; END IF; '||anchor);
    definition:=pg_get_functiondef('warehouse_assert_fulfillment_owner_receipts(uuid,uuid)'::regprocedure);
    IF strpos(definition,'frozen.approved_by')=0 THEN RAISE EXCEPTION 'owner receipt actor anchor changed'; END IF;
    EXECUTE replace(definition,'frozen.approved_by','frozen.fulfillment_actor_id');
    definition:=pg_get_functiondef('warehouse_bng_handoff_stamp()'::regprocedure);
    IF strpos(definition,'actor.id=snapshot.approved_by')=0 THEN RAISE EXCEPTION 'BNG receipt actor anchor changed'; END IF;
    EXECUTE replace(definition,'actor.id=snapshot.approved_by','actor.id=snapshot.fulfillment_actor_id');
    definition:=pg_get_functiondef('warehouse_fulfillment_bound_guard()'::regprocedure);
    anchor:='IF NEW.source=''WORK_ORDER'' AND NEW.state=''APPLIED'' AND target IS NULL THEN';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'fulfillment source checkpoint anchor changed'; END IF;
    EXECUTE replace(definition,anchor,'IF (NEW.source=''WORK_ORDER'' AND NEW.state=''APPLIED'' OR NEW.source=''REFERENCE_WORK_ORDER'') AND target IS NULL THEN');
END $$;
