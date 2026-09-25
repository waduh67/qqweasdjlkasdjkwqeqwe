-- Fulfillment owns the source projection. Payloads, error messages and worker identities stay private.
CREATE VIEW fulfillment_live_provenance_source WITH (security_invoker=true) AS
SELECT tenant_id,source_table,source_id,source_snapshot,
    encode(sha256(convert_to(source_snapshot::text,'UTF8')),'hex') source_hash
FROM (
    SELECT checkpoint.tenant_id,'fulfillment_checkpoint'::text source_table,checkpoint.id source_id,jsonb_build_object(
        'id',checkpoint.id,'tenantId',checkpoint.tenant_id,'namespace',checkpoint.namespace,'operationKey',checkpoint.operation_key,
        'payloadHash',checkpoint.canonical_hash,'source',checkpoint.source,'targetId',checkpoint.target_id,
        'workOrderId',coalesce(checkpoint.work_order_id,checkpoint.target_id),'state',checkpoint.state,
        'requiredEffects',checkpoint.required_effects,'createdAt',checkpoint.created_at,
        'completedEffects',coalesce((SELECT jsonb_agg(progress.effect_type ORDER BY progress.effect_type)
            FROM fulfillment_effect_progress progress WHERE progress.tenant_id=checkpoint.tenant_id AND progress.fulfillment_id=checkpoint.id
                AND progress.status='COMPLETED'),'[]'::jsonb),
        'outboxIds',coalesce((SELECT jsonb_agg(message.id ORDER BY message.id) FROM fulfillment_outbox message
            WHERE message.tenant_id=checkpoint.tenant_id AND message.fulfillment_id=checkpoint.id),'[]'::jsonb)) source_snapshot
    FROM fulfillment_checkpoint checkpoint WHERE checkpoint.source='WORK_ORDER'
        AND NOT EXISTS (SELECT FROM fulfillment_approval_snapshot frozen WHERE frozen.tenant_id=checkpoint.tenant_id
            AND frozen.namespace=checkpoint.namespace AND frozen.operation_key=checkpoint.operation_key)
    UNION ALL
    SELECT message.tenant_id,'fulfillment_outbox',message.id,jsonb_build_object(
        'id',message.id,'tenantId',message.tenant_id,'checkpointId',message.fulfillment_id,'checkpointKnown',checkpoint.id IS NOT NULL,
        'workOrderId',coalesce(checkpoint.work_order_id,checkpoint.target_id),'checkpointState',checkpoint.state,
        'payloadHash',message.payload_hash,'eventType',message.event_type,'sequence',message.sequence::text,
        'publishedAt',message.published_at,'leaseUntil',message.lease_until,'createdAt',message.created_at)
    FROM fulfillment_outbox message LEFT JOIN fulfillment_checkpoint checkpoint
        ON checkpoint.tenant_id=message.tenant_id AND checkpoint.id=message.fulfillment_id
    WHERE (checkpoint.id IS NULL OR checkpoint.source='WORK_ORDER')
        AND NOT EXISTS (SELECT FROM fulfillment_approval_snapshot frozen WHERE frozen.tenant_id=message.tenant_id
            AND frozen.namespace=checkpoint.namespace AND frozen.operation_key=checkpoint.operation_key)
) raw;

ALTER TABLE inventory_provenance_case DROP CONSTRAINT inventory_provenance_case_source_table_check;
ALTER TABLE inventory_provenance_case ADD CHECK (source_table IN (
    'inventory_serialized_asset','inventory_balance_projection','onu','inventory_serial_tombstone','inventory_movement',
    'inventory_movement_leg','inventory_fulfillment_effect','inventory_customer_material_fact','fulfillment_checkpoint','fulfillment_outbox'));

CREATE OR REPLACE VIEW warehouse_live_provenance_source WITH (security_invoker=true) AS
    SELECT * FROM inventory_live_provenance_source UNION ALL SELECT * FROM customer_live_provenance_source
    UNION ALL SELECT * FROM fulfillment_live_provenance_source;

CREATE FUNCTION warehouse_migration_pending(source_kind text, data jsonb) RETURNS boolean LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE source_kind
        WHEN 'inventory_movement' THEN coalesce(data->>'state','')<>'APPLIED'
        WHEN 'fulfillment_checkpoint' THEN coalesce(data->>'state','') NOT IN ('APPLIED','FAILED_PERMANENT','MANUAL_RESOLVED')
        WHEN 'fulfillment_outbox' THEN coalesce(data->>'checkpointState','') NOT IN ('APPLIED','FAILED_PERMANENT','MANUAL_RESOLVED')
        ELSE false END
$$;

DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT ON fulfillment_live_provenance_source TO warehouse_app;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION warehouse_migration_resolution_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source inventory_provenance_case%ROWTYPE; payload jsonb; request jsonb; body jsonb; evidence jsonb; latest bigint;
BEGIN
    IF warehouse_lock_migration_batch(NEW.tenant_id,NEW.batch_id)<>NEW.cutover_epoch THEN
        RAISE EXCEPTION 'resolution requires the current validating batch' USING ERRCODE='23514';
    END IF;
    SELECT * INTO source FROM inventory_provenance_case WHERE tenant_id=NEW.tenant_id AND id=NEW.case_id AND source_hash=NEW.source_hash;
    IF source.id IS NULL OR NOT EXISTS (SELECT FROM inventory_migration_batch batch, jsonb_array_elements(batch.source_manifest) member
        WHERE batch.tenant_id=NEW.tenant_id AND batch.id=NEW.batch_id AND member->>'caseId'=NEW.case_id::text AND member->>'sourceHash'=NEW.source_hash) THEN
        RAISE EXCEPTION 'resolution requires the captured case hash' USING ERRCODE='23514';
    END IF;
    SELECT coalesce(max(revision),0) INTO latest FROM inventory_migration_resolution WHERE tenant_id=NEW.tenant_id AND batch_id=NEW.batch_id AND case_id=NEW.case_id;
    payload:=NEW.canonical_payload::jsonb; request:=payload->'input'; body:=NEW.original_body::jsonb;
    SELECT jsonb_agg(jsonb_build_object('id',file.id,'sourceHash',file.source_hash,'sha256',file.sha256,'uploadedBy',file.actor_id) ORDER BY requested.ordinality)
        INTO evidence FROM jsonb_array_elements_text(request->'evidenceIds') WITH ORDINALITY requested(id,ordinality)
        JOIN inventory_migration_evidence file ON file.tenant_id=NEW.tenant_id AND file.batch_id=NEW.batch_id AND file.case_id=NEW.case_id
            AND file.id=requested.id::uuid AND file.source_hash=NEW.source_hash;
    IF NEW.revision<>latest+1 OR (request->>'expectedResolutionRevision')::bigint IS DISTINCT FROM latest
        OR NEW.payload_hash<>encode(sha256(convert_to(NEW.canonical_payload,'UTF8')),'hex')
        OR payload->>'batchId' IS DISTINCT FROM NEW.batch_id::text OR payload->>'caseId' IS DISTINCT FROM NEW.case_id::text
        OR (request->>'expectedEpoch')::bigint IS DISTINCT FROM NEW.cutover_epoch OR request->>'expectedCaseHash' IS DISTINCT FROM NEW.source_hash
        OR request->>'kind' IS DISTINCT FROM NEW.kind OR request->>'reason' IS DISTINCT FROM NEW.reason
        OR request->>'duplicateCaseId' IS DISTINCT FROM NEW.duplicate_case_id::text
        OR NEW.evidence_manifest IS DISTINCT FROM evidence OR jsonb_array_length(request->'evidenceIds') IS DISTINCT FROM jsonb_array_length(evidence)
        OR (SELECT count(DISTINCT value) FROM jsonb_array_elements_text(request->'evidenceIds'))<>jsonb_array_length(evidence) THEN
        RAISE EXCEPTION 'resolution requires exact command, revision and case-bound evidence' USING ERRCODE='23514';
    END IF;
    IF NEW.kind='BASELINE_STOCK' THEN
        IF NEW.stock IS DISTINCT FROM warehouse_migration_stock(NEW.case_id,(request->'stock'->>'skuId')::uuid,
            request->'stock'->>'sourceUnit',request->'stock'->>'legalOwner') THEN
            RAISE EXCEPTION 'resolution stock must be derived from the preserved source' USING ERRCODE='23514';
        END IF;
    ELSIF request->>'stock' IS NOT NULL THEN
        RAISE EXCEPTION 'non-stock resolution cannot declare stock' USING ERRCODE='23514';
    END IF;
    IF NEW.kind='DUPLICATE' AND NEW.duplicate_resolution_id IS DISTINCT FROM warehouse_migration_duplicate(NEW.batch_id,NEW.case_id,NEW.duplicate_case_id) THEN
        RAISE EXCEPTION 'duplicate requires the current winner revision' USING ERRCODE='23514';
    END IF;
    IF (NEW.kind='CANCEL_PENDING') IS DISTINCT FROM warehouse_migration_pending(source.source_table,source.source_snapshot) THEN
        RAISE EXCEPTION 'pending legacy effect requires explicit cancellation review' USING ERRCODE='23514';
    END IF;
    IF body->>'id' IS DISTINCT FROM NEW.id::text OR body->>'batchId' IS DISTINCT FROM NEW.batch_id::text
        OR body->>'caseId' IS DISTINCT FROM NEW.case_id::text OR body->>'sourceHash' IS DISTINCT FROM NEW.source_hash
        OR (body->>'revision')::bigint IS DISTINCT FROM NEW.revision OR body->>'kind' IS DISTINCT FROM NEW.kind OR body->>'reason' IS DISTINCT FROM NEW.reason
        OR body->'evidence' IS DISTINCT FROM NEW.evidence_manifest OR body->'stock' IS DISTINCT FROM coalesce(NEW.stock,'null'::jsonb)
        OR body->>'duplicateCaseId' IS DISTINCT FROM NEW.duplicate_case_id::text OR body->>'duplicateResolutionId' IS DISTINCT FROM NEW.duplicate_resolution_id::text
        OR body->>'resolvedBy' IS DISTINCT FROM NEW.actor_id::text OR (body->>'createdAt')::timestamptz IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'resolution requires its exact original response' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
