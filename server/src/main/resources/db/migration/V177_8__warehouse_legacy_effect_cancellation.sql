-- Permanent disposition is separate from original effect/delivery history.
CREATE TABLE inventory_migration_cancellation (
    tenant_id uuid NOT NULL, case_id uuid NOT NULL, source_table text NOT NULL CHECK(source_table='inventory_movement'),
    source_id uuid NOT NULL, source_hash text NOT NULL, request_id uuid NOT NULL, resolution_id uuid NOT NULL,
    current_snapshot jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY(tenant_id,case_id), UNIQUE(tenant_id,source_table,source_id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_migration_admission(tenant_id,request_id),
    FOREIGN KEY(tenant_id,case_id) REFERENCES inventory_provenance_case(tenant_id,id),
    FOREIGN KEY(tenant_id,resolution_id) REFERENCES inventory_migration_resolution(tenant_id,id)
);
CREATE TABLE fulfillment_migration_cancellation (
    tenant_id uuid NOT NULL, case_id uuid NOT NULL,
    source_table text NOT NULL CHECK(source_table IN ('fulfillment_checkpoint','fulfillment_outbox')),
    source_id uuid NOT NULL, source_hash text NOT NULL, request_id uuid NOT NULL, resolution_id uuid NOT NULL,
    current_snapshot jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY(tenant_id,case_id), UNIQUE(tenant_id,source_table,source_id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_migration_admission(tenant_id,request_id),
    FOREIGN KEY(tenant_id,case_id) REFERENCES inventory_provenance_case(tenant_id,id),
    FOREIGN KEY(tenant_id,resolution_id) REFERENCES inventory_migration_resolution(tenant_id,id)
);
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_migration_cancellation','fulfillment_migration_cancellation'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('REVOKE INSERT,UPDATE,DELETE ON %I FROM warehouse_app',relation);
            EXECUTE format('GRANT SELECT ON %I TO warehouse_app',relation);
        END IF;
    END LOOP;
END $$;
CREATE VIEW warehouse_migration_cancellation_receipt WITH(security_invoker=true) AS
    SELECT * FROM inventory_migration_cancellation UNION ALL SELECT * FROM fulfillment_migration_cancellation;

-- Public contract: only this transaction's already checked admission may cancel.
CREATE FUNCTION warehouse_migration_cancellation_manifest(target uuid,p_operation uuid) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE scope uuid:=NULLIF(current_setting('app.tenant_id',true),'')::uuid; admission inventory_migration_admission; manifest jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO admission FROM inventory_migration_admission WHERE tenant_id=scope AND request_id=target;
    IF admission.request_id IS NULL OR admission.created_xid<>pg_current_xact_id() OR admission.operation_id<>p_operation THEN
        RAISE EXCEPTION 'cancellation requires current opening admission' USING ERRCODE='23514';
    END IF;
    PERFORM warehouse_lock_migration_history(scope,admission.batch_id);
    IF NOT EXISTS(SELECT FROM inventory_approval WHERE tenant_id=scope AND id=admission.approval_id
        AND status='APPROVED' AND business_action='OPENING_BALANCE' AND source_document_id=target) THEN
        RAISE EXCEPTION 'cancellation requires exact independently approved review' USING ERRCODE='23514';
    END IF;
    SELECT review_manifest INTO STRICT manifest FROM inventory_migration_opening_request WHERE tenant_id=scope AND id=target;
    RETURN manifest;
END $$;

CREATE FUNCTION warehouse_cancel_migration_effects(target uuid,p_operation uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE scope uuid:=NULLIF(current_setting('app.tenant_id',true),'')::uuid; manifest jsonb; entry jsonb; live jsonb;
BEGIN
    manifest:=warehouse_migration_cancellation_manifest(target,p_operation);
    FOR entry IN SELECT value FROM jsonb_array_elements(manifest->'cases')
        WHERE value->>'sourceTable'='inventory_movement' AND value->'resolution'->>'kind'='CANCEL_PENDING' ORDER BY value->>'sourceId' LOOP
        PERFORM 1 FROM inventory_movement WHERE tenant_id=scope AND id=(entry->>'sourceId')::uuid FOR UPDATE;
        SELECT source_snapshot INTO live FROM inventory_live_provenance_source
            WHERE tenant_id=scope AND source_table='inventory_movement' AND source_id=(entry->>'sourceId')::uuid;
        IF live IS NULL OR encode(sha256(convert_to(live::text,'UTF8')),'hex')<>entry->>'sourceHash' THEN
            RAISE EXCEPTION 'reviewed legacy movement changed' USING ERRCODE='23514';
        END IF;
        INSERT INTO inventory_migration_cancellation(tenant_id,case_id,source_table,source_id,source_hash,request_id,resolution_id,current_snapshot)
            VALUES(scope,(entry->>'caseId')::uuid,'inventory_movement',(entry->>'sourceId')::uuid,entry->>'sourceHash',target,
                (entry->'resolution'->>'id')::uuid,live);
    END LOOP;
END $$;

CREATE FUNCTION fulfillment_cancel_migration_effects(target uuid,p_operation uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE scope uuid:=NULLIF(current_setting('app.tenant_id',true),'')::uuid; manifest jsonb; entry jsonb; live jsonb; frozen jsonb;
BEGIN
    manifest:=warehouse_migration_cancellation_manifest(target,p_operation);
    -- Same order as the fulfillment owner: checkpoint before outbox/progress.
    PERFORM 1 FROM fulfillment_checkpoint checkpoint WHERE checkpoint.tenant_id=scope AND checkpoint.id IN (
        SELECT (value->>'sourceId')::uuid FROM jsonb_array_elements(manifest->'cases')
            WHERE value->>'sourceTable'='fulfillment_checkpoint' AND value->'resolution'->>'kind'='CANCEL_PENDING'
        UNION SELECT (source.source_snapshot->>'checkpointId')::uuid FROM jsonb_array_elements(manifest->'cases') member
            JOIN inventory_provenance_case source ON source.tenant_id=scope AND source.id=(member->>'caseId')::uuid
            WHERE member->>'sourceTable'='fulfillment_outbox' AND member->'resolution'->>'kind'='CANCEL_PENDING'
    ) ORDER BY checkpoint.id FOR UPDATE;
    PERFORM 1 FROM fulfillment_outbox message WHERE message.tenant_id=scope AND message.id IN (
        SELECT (value->>'sourceId')::uuid FROM jsonb_array_elements(manifest->'cases')
            WHERE value->>'sourceTable'='fulfillment_outbox' AND value->'resolution'->>'kind'='CANCEL_PENDING'
    ) ORDER BY message.id FOR UPDATE;
    FOR entry IN SELECT value FROM jsonb_array_elements(manifest->'cases')
        WHERE value->>'sourceTable' IN ('fulfillment_checkpoint','fulfillment_outbox') AND value->'resolution'->>'kind'='CANCEL_PENDING'
        ORDER BY value->>'sourceTable',value->>'sourceId' LOOP
        SELECT source_snapshot INTO frozen FROM inventory_provenance_case WHERE tenant_id=scope AND id=(entry->>'caseId')::uuid;
        SELECT source_snapshot INTO live FROM fulfillment_live_provenance_source WHERE tenant_id=scope
            AND source_table=entry->>'sourceTable' AND source_id=(entry->>'sourceId')::uuid;
        -- Retries/ACKs may change state or leases, but no changed effect, payload,
        -- business binding or new delivery can ride on the captured approval.
        IF live IS NULL OR (CASE WHEN entry->>'sourceTable'='fulfillment_checkpoint'
            THEN live-'state' IS DISTINCT FROM frozen-'state'
            ELSE live-ARRAY['checkpointState','publishedAt','leaseUntil'] IS DISTINCT FROM frozen-ARRAY['checkpointState','publishedAt','leaseUntil'] END) THEN
            RAISE EXCEPTION 'reviewed legacy fulfillment identity or effects changed' USING ERRCODE='23514';
        END IF;
        INSERT INTO fulfillment_migration_cancellation(tenant_id,case_id,source_table,source_id,source_hash,request_id,resolution_id,current_snapshot)
            VALUES(scope,(entry->>'caseId')::uuid,entry->>'sourceTable',(entry->>'sourceId')::uuid,entry->>'sourceHash',target,
                (entry->'resolution'->>'id')::uuid,live);
    END LOOP;
END $$;
DO $$ DECLARE signature text; BEGIN
    FOREACH signature IN ARRAY ARRAY['warehouse_cancel_migration_effects(uuid,uuid)','fulfillment_cancel_migration_effects(uuid,uuid)'] LOOP
        EXECUTE format('ALTER FUNCTION %s SET search_path TO pg_catalog,%I,pg_temp',signature,current_schema());
        EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC',signature);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO warehouse_app',signature); END IF;
    END LOOP;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN GRANT SELECT ON warehouse_migration_cancellation_receipt TO warehouse_app; END IF;
END $$;

CREATE FUNCTION warehouse_migration_cancellations_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE manifest jsonb; expected bigint; actual bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT review_manifest INTO STRICT manifest FROM inventory_migration_opening_request WHERE tenant_id=NEW.tenant_id AND id=NEW.request_id;
    SELECT count(*) INTO expected FROM jsonb_array_elements(manifest->'cases') WHERE value->'resolution'->>'kind'='CANCEL_PENDING';
    SELECT count(*) INTO actual FROM warehouse_migration_cancellation_receipt receipt
        JOIN jsonb_array_elements(manifest->'cases') entry ON receipt.case_id=(entry->>'caseId')::uuid
            AND receipt.source_id=(entry->>'sourceId')::uuid AND receipt.source_table=entry->>'sourceTable'
            AND receipt.source_hash=entry->>'sourceHash' AND receipt.resolution_id=(entry->'resolution'->>'id')::uuid
        WHERE receipt.tenant_id=NEW.tenant_id AND receipt.request_id=NEW.request_id AND entry->'resolution'->>'kind'='CANCEL_PENDING';
    IF expected<>actual OR actual<>(SELECT count(*) FROM warehouse_migration_cancellation_receipt WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id) THEN
        RAISE EXCEPTION 'opening must cancel every reviewed pending effect atomically' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_migration_cancellations_complete AFTER INSERT ON inventory_migration_admission
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_migration_cancellations_complete();

-- A stale worker or direct SQL cannot reopen a permanently canceled effect.
CREATE FUNCTION fulfillment_migration_cancellation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE data jsonb; scope uuid; checkpoint uuid;
BEGIN
    IF TG_OP='UPDATE' AND (to_jsonb(NEW)->'tenant_id',to_jsonb(NEW)->'id',to_jsonb(NEW)->'fulfillment_id') IS DISTINCT FROM
        (to_jsonb(OLD)->'tenant_id',to_jsonb(OLD)->'id',to_jsonb(OLD)->'fulfillment_id') THEN
        RAISE EXCEPTION 'fulfillment source identity is immutable' USING ERRCODE='23514';
    END IF;
    data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(data->>'tenant_id')::uuid;
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM 1 FROM inventory_tenant_cutover WHERE tenant_id=scope FOR SHARE;
    checkpoint:=CASE WHEN TG_TABLE_NAME='fulfillment_checkpoint' THEN (data->>'id')::uuid ELSE (data->>'fulfillment_id')::uuid END;
    IF TG_TABLE_NAME='fulfillment_effect_progress' OR (TG_TABLE_NAME='fulfillment_outbox' AND TG_OP='INSERT') THEN
        PERFORM 1 FROM fulfillment_checkpoint WHERE tenant_id=scope AND id=checkpoint FOR UPDATE;
    END IF;
    IF EXISTS(SELECT FROM fulfillment_migration_cancellation WHERE tenant_id=scope
        AND ((source_table='fulfillment_checkpoint' AND source_id=checkpoint)
            OR (source_table=TG_TABLE_NAME AND source_id=(data->>'id')::uuid))) THEN
        RAISE EXCEPTION 'legacy fulfillment was permanently canceled by approved migration' USING ERRCODE='23514';
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER fulfillment_migration_cancellation_guard BEFORE UPDATE OR DELETE ON fulfillment_checkpoint
    FOR EACH ROW EXECUTE FUNCTION fulfillment_migration_cancellation_guard();
CREATE TRIGGER fulfillment_migration_cancellation_guard BEFORE INSERT OR UPDATE OR DELETE ON fulfillment_outbox
    FOR EACH ROW EXECUTE FUNCTION fulfillment_migration_cancellation_guard();
CREATE TRIGGER fulfillment_migration_cancellation_guard BEFORE INSERT OR UPDATE OR DELETE ON fulfillment_effect_progress
    FOR EACH ROW EXECUTE FUNCTION fulfillment_migration_cancellation_guard();
