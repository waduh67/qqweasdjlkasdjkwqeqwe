CREATE OR REPLACE FUNCTION warehouse_bng_handoff_stamp() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE bound_id uuid; binding jsonb; access subscriber_access%ROWTYPE; expected_xid xid8;
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    IF TG_OP='DELETE' THEN
        IF OLD.fulfillment_approval_id IS NOT NULL OR OLD.fulfillment_xid IS NOT NULL THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_BINDING_IMMUTABLE' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP='UPDATE' THEN
        IF (NEW.tenant_id,NEW.fulfillment_approval_id,NEW.fulfillment_xid) IS DISTINCT FROM
            (OLD.tenant_id,OLD.fulfillment_approval_id,OLD.fulfillment_xid) THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_BINDING_IMMUTABLE' USING ERRCODE='23514';
        END IF;
        IF OLD.fulfillment_approval_id IS NOT NULL AND warehouse_bng_handoff_hash(to_jsonb(NEW)) IS DISTINCT FROM warehouse_bng_handoff_hash(to_jsonb(OLD)) THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_IMMUTABLE' USING ERRCODE='23514';
        END IF;
        RETURN NEW;
    END IF;
    bound_id:=NULLIF(current_setting('app.fulfillment_approval_id',true),'')::uuid;
    expected_xid:=CASE WHEN bound_id IS NULL THEN NULL ELSE pg_current_xact_id() END;
    IF (NEW.fulfillment_approval_id IS NOT NULL AND NEW.fulfillment_approval_id IS DISTINCT FROM bound_id) OR
        (NEW.fulfillment_xid IS NOT NULL AND NEW.fulfillment_xid IS DISTINCT FROM expected_xid) THEN
        RAISE EXCEPTION 'FULFILLMENT_BNG_INITIAL_BINDING' USING ERRCODE='23514';
    END IF;
    NEW.fulfillment_approval_id:=bound_id;
    NEW.fulfillment_xid:=expected_xid;
    IF bound_id IS NULL THEN RETURN NEW; END IF;
    SELECT snapshot.snapshot::jsonb INTO binding FROM fulfillment_approval_snapshot snapshot
        JOIN fulfillment_checkpoint checkpoint ON checkpoint.tenant_id=snapshot.tenant_id AND checkpoint.namespace=snapshot.namespace
            AND checkpoint.operation_key=snapshot.operation_key
        JOIN app_user actor ON actor.tenant_id=snapshot.tenant_id AND actor.id=snapshot.approved_by
        WHERE snapshot.tenant_id=NEW.tenant_id AND snapshot.id=bound_id AND checkpoint.state='APPLYING'
            AND 'PROVISIONING'=ANY(snapshot.required_effects) AND actor.status='ACTIVE'
            AND (NEW.requested_by IS NULL OR NEW.requested_by=actor.id);
    IF NOT FOUND THEN RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_SCOPE' USING ERRCODE='23514'; END IF;
    SELECT * INTO access FROM subscriber_access WHERE tenant_id=NEW.tenant_id AND id=(binding->>'bngAccessId')::uuid;
    IF NOT FOUND OR access.customer_id::text IS DISTINCT FROM binding->'workOrder'->'material'->>'customerId' OR
        access.subscription_id::text IS DISTINCT FROM binding->'workOrder'->'material'->>'subscriptionId' OR NEW.nas_id IS DISTINCT FROM access.nas_id THEN
        RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_TARGET' USING ERRCODE='23514';
    END IF;
    IF binding->'workOrder'->'material'->>'action'='REMOVE' THEN
        IF NEW.action<>'DEPROVISION' THEN RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_TARGET' USING ERRCODE='23514'; END IF;
    ELSIF binding->'workOrder'->'material'->>'action'='INSTALL' THEN
        IF NEW.action NOT IN ('PROVISION','SYNC_GROUP','COA','DISCONNECT') THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_TARGET' USING ERRCODE='23514';
        END IF;
    ELSE RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_TARGET' USING ERRCODE='23514';
    END IF;
    IF NEW.action='SYNC_GROUP' THEN
        IF NEW.subscriber_access_id IS NOT NULL OR NEW.groupname IS DISTINCT FROM 'plan:'||access.plan_id::text THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_TARGET' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NEW.username<>access.username OR NEW.auth_type<>access.auth_type OR
            (NEW.subscriber_access_id IS NOT NULL AND NEW.subscriber_access_id<>access.id) OR
            (NEW.action<>'DEPROVISION' AND NEW.subscriber_access_id IS NULL) OR
            (NEW.action='PROVISION' AND NEW.groupname IS DISTINCT FROM 'plan:'||access.plan_id::text) THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_TARGET' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER warehouse_bng_handoff_stamp ON bng_action;
CREATE TRIGGER warehouse_bng_handoff_stamp BEFORE INSERT OR UPDATE OR DELETE ON bng_action
    FOR EACH ROW EXECUTE FUNCTION warehouse_bng_handoff_stamp();

DO $$ DECLARE definition text; previous text;
BEGIN
    definition:=pg_get_functiondef('warehouse_fulfillment_owner_guard()'::regprocedure);
    previous:=E'WHEN TG_TABLE_NAME=''bng_action'' THEN EXISTS(SELECT FROM bng_fulfillment_receipt receipt WHERE receipt.tenant_id=target_tenant\n                AND receipt.id=snapshot.id AND receipt.action_ids && ids)';
    IF position(previous IN definition)=0 THEN RAISE EXCEPTION 'expected BNG action reference discovery missing'; END IF;
    EXECUTE replace(definition,previous,E'WHEN TG_TABLE_NAME=''bng_action'' THEN\n                snapshot.id IN ((before_row->>''fulfillment_approval_id'')::uuid,(after_row->>''fulfillment_approval_id'')::uuid)\n                OR EXISTS(SELECT FROM bng_fulfillment_receipt receipt WHERE receipt.tenant_id=target_tenant\n                    AND receipt.id=snapshot.id AND receipt.action_ids && ids)');
END $$;
