ALTER TABLE bng_action ADD COLUMN fulfillment_approval_id uuid, ADD COLUMN fulfillment_xid xid8;
ALTER TABLE bng_action ADD CONSTRAINT bng_fulfillment_approval_fk FOREIGN KEY(tenant_id,fulfillment_approval_id)
    REFERENCES fulfillment_approval_snapshot(tenant_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX bng_fulfillment_approval_idx ON bng_action(tenant_id,fulfillment_approval_id,fulfillment_xid);
ALTER TABLE bng_fulfillment_receipt ADD COLUMN action_bindings jsonb;
ALTER TABLE bng_fulfillment_receipt ADD CHECK(action_bindings IS NULL OR jsonb_typeof(action_bindings)='object');

CREATE FUNCTION warehouse_bng_handoff_hash(value jsonb) RETURNS text LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT encode(sha256(convert_to(($1-ARRAY['status','detail','dispatched_at','completed_at','updated_at'])::text,'UTF8')),'hex')
$$;

CREATE FUNCTION warehouse_bng_handoff_stamp() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='INSERT' THEN
        NEW.fulfillment_approval_id:=NULLIF(current_setting('app.fulfillment_approval_id',true),'')::uuid;
        NEW.fulfillment_xid:=CASE WHEN NEW.fulfillment_approval_id IS NULL THEN NULL ELSE pg_current_xact_id() END;
        IF NEW.fulfillment_approval_id IS NOT NULL AND NOT EXISTS(SELECT FROM fulfillment_approval_snapshot snapshot JOIN fulfillment_checkpoint checkpoint
            ON checkpoint.tenant_id=snapshot.tenant_id AND checkpoint.namespace=snapshot.namespace AND checkpoint.operation_key=snapshot.operation_key
            WHERE snapshot.tenant_id=NEW.tenant_id AND snapshot.id=NEW.fulfillment_approval_id AND checkpoint.state='APPLYING'
                AND 'PROVISIONING'=ANY(snapshot.required_effects)) THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_SCOPE' USING ERRCODE='23514';
        END IF;
    ELSIF OLD.fulfillment_approval_id IS NOT NULL AND warehouse_bng_handoff_hash(to_jsonb(NEW)) IS DISTINCT FROM warehouse_bng_handoff_hash(to_jsonb(OLD)) THEN
        RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_bng_handoff_stamp BEFORE INSERT OR UPDATE ON bng_action FOR EACH ROW EXECUTE FUNCTION warehouse_bng_handoff_stamp();

CREATE FUNCTION warehouse_assert_bng_fulfillment_handoff(target_tenant uuid,target_id uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE receipt bng_fulfillment_receipt%ROWTYPE; access subscriber_access%ROWTYPE; command_row bng_action%ROWTYPE;
    handoff_id uuid; primary_found boolean:=false;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO receipt FROM bng_fulfillment_receipt WHERE tenant_id=$1 AND id=$2;
    IF NOT FOUND THEN RAISE EXCEPTION 'FULFILLMENT_BNG_RECEIPT' USING ERRCODE='23514'; END IF;
    IF cardinality(receipt.action_ids)<>(SELECT count(*) FROM jsonb_object_keys(coalesce(receipt.action_bindings,'{}'::jsonb))) THEN
        RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_BINDINGS' USING ERRCODE='23514';
    END IF;
    SELECT * INTO access FROM subscriber_access WHERE tenant_id=$1 AND id=receipt.access_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'FULFILLMENT_BNG_BINDING' USING ERRCODE='23514'; END IF;
    IF cardinality(receipt.action_ids)<>(SELECT count(*) FROM bng_action WHERE tenant_id=$1 AND fulfillment_approval_id=$2 AND fulfillment_xid=receipt.created_xid) THEN
        RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_BINDINGS' USING ERRCODE='23514';
    END IF;
    FOREACH handoff_id IN ARRAY receipt.action_ids LOOP
        SELECT * INTO command_row FROM bng_action WHERE tenant_id=$1 AND id=handoff_id;
        IF NOT FOUND OR command_row.fulfillment_approval_id IS DISTINCT FROM receipt.id OR command_row.fulfillment_xid IS DISTINCT FROM receipt.created_xid OR
            receipt.action_bindings->>handoff_id::text IS DISTINCT FROM warehouse_bng_handoff_hash(to_jsonb(command_row)) THEN
            RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_BINDINGS' USING ERRCODE='23514';
        END IF;
        IF (receipt.action='ACTIVATE' AND command_row.action='PROVISION') OR (receipt.action='TERMINATE' AND command_row.action='DEPROVISION') THEN primary_found:=true; END IF;
        IF receipt.created_xid=pg_current_xact_id() THEN
            IF command_row.nas_id IS DISTINCT FROM access.nas_id THEN RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_TARGET' USING ERRCODE='23514'; END IF;
            IF command_row.action='SYNC_GROUP' THEN
                IF receipt.action<>'ACTIVATE' OR command_row.groupname IS DISTINCT FROM 'plan:'||access.plan_id::text OR command_row.subscriber_access_id IS NOT NULL THEN
                    RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_TARGET' USING ERRCODE='23514';
                END IF;
            ELSE
                IF command_row.username<>access.username OR command_row.auth_type<>access.auth_type OR
                    (command_row.subscriber_access_id IS NOT NULL AND command_row.subscriber_access_id<>access.id) OR
                    (receipt.action='TERMINATE' AND command_row.action<>'DEPROVISION') OR
                    (receipt.action='ACTIVATE' AND command_row.action NOT IN ('PROVISION','COA','DISCONNECT')) THEN
                    RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_TARGET' USING ERRCODE='23514';
                END IF;
                IF command_row.action='PROVISION' AND command_row.groupname IS DISTINCT FROM 'plan:'||access.plan_id::text THEN
                    RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_TARGET' USING ERRCODE='23514';
                END IF;
            END IF;
        END IF;
    END LOOP;
    IF receipt.created_xid=pg_current_xact_id() AND access.nas_id IS NOT NULL AND
        ((receipt.action='ACTIVATE' AND receipt.source_state IN ('PENDING','ISOLATED')) OR (receipt.action='TERMINATE' AND receipt.source_state<>'TERMINATED')) AND NOT primary_found THEN
        RAISE EXCEPTION 'FULFILLMENT_BNG_HANDOFF_MISSING' USING ERRCODE='23514';
    END IF;
END $$;

DO $$ DECLARE definition text; previous text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_fulfillment_owner_receipts(uuid,uuid)'::regprocedure);
    previous:=E'        FOREACH target_action IN ARRAY bng_receipt.action_ids LOOP\n            IF NOT EXISTS(SELECT FROM bng_action WHERE tenant_id=target_tenant AND id=target_action AND subscriber_access_id=bng_receipt.access_id) THEN\n                RAISE EXCEPTION ''FULFILLMENT_BNG_HANDOFF'' USING ERRCODE=''23514'';\n            END IF;\n        END LOOP;';
    IF position(previous IN definition)=0 THEN RAISE EXCEPTION 'expected BNG handoff validation missing'; END IF;
    EXECUTE replace(definition,previous,'        PERFORM warehouse_assert_bng_fulfillment_handoff(target_tenant,target_id);');
END $$;
