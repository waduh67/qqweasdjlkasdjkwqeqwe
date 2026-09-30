-- M06 completes schema enforcement without requiring any legacy tenant to be admitted at boot.
CREATE TABLE inventory_migration_finalization (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, batch_id uuid NOT NULL, request_id uuid NOT NULL,
    actor_id uuid NOT NULL, authority_epoch bigint NOT NULL CHECK(authority_epoch>=0),
    operation_key varchar(240) NOT NULL, canonical_payload text NOT NULL,
    payload_hash text NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    previous_epoch bigint NOT NULL CHECK(previous_epoch>0), resulting_epoch bigint NOT NULL,
    original_body text NOT NULL, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,batch_id), UNIQUE(tenant_id,operation_key),
    CHECK(resulting_epoch=previous_epoch+1),
    FOREIGN KEY(tenant_id,batch_id) REFERENCES inventory_migration_batch(tenant_id,id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_migration_admission(tenant_id,request_id)
);
ALTER TABLE inventory_migration_finalization ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_migration_finalization FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_migration_finalization
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_migration_finalization_immutable BEFORE UPDATE OR DELETE ON inventory_migration_finalization
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE INSERT,UPDATE,DELETE ON inventory_migration_finalization FROM warehouse_app;
        GRANT SELECT ON inventory_migration_finalization TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_migration_finalization_issues(scope uuid,target uuid) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE cutover inventory_tenant_cutover; admission inventory_migration_admission; request inventory_migration_opening_request;
    issues jsonb:='[]'; expected bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO cutover FROM inventory_tenant_cutover WHERE tenant_id=scope;
    IF cutover.migration_batch_id IS DISTINCT FROM target OR cutover.state IS DISTINCT FROM 'VALIDATING' THEN
        RETURN jsonb_build_array('CUTOVER_NOT_VALIDATING');
    END IF;
    SELECT * INTO admission FROM inventory_migration_admission WHERE tenant_id=scope AND batch_id=target;
    IF admission.request_id IS NULL THEN RETURN jsonb_build_array('APPROVED_OPENING_REQUIRED'); END IF;
    SELECT * INTO STRICT request FROM inventory_migration_opening_request WHERE tenant_id=scope AND id=admission.request_id;
    BEGIN
        PERFORM warehouse_assert_opening_approval(scope,request.id,1);
    EXCEPTION WHEN check_violation THEN issues:=issues||jsonb_build_array('OPENING_PROOF_INVALID'); END;
    IF NOT EXISTS(SELECT FROM inventory_document WHERE tenant_id=scope AND id=request.id AND kind='OPENING_BALANCE' AND state='POSTED' AND revision=1)
        OR request.review_manifest IS DISTINCT FROM warehouse_migration_review_manifest(target)
        OR request.review_hash<>admission.review_hash THEN
        issues:=issues||jsonb_build_array('APPROVED_REVIEW_CHANGED');
    END IF;
    IF NOT EXISTS(SELECT FROM inventory_approval_effect effect JOIN inventory_outbox event
        ON event.tenant_id=effect.tenant_id AND event.id=effect.event_id JOIN inventory_inbox inbox
        ON inbox.tenant_id=event.tenant_id AND inbox.event_id=event.id AND inbox.consumer='warehouse.approval.receipt'
        WHERE effect.tenant_id=scope AND effect.approval_id=admission.approval_id
            AND effect.posting_operation_id=admission.operation_id AND event.event_kind='OPENING_POSTED') THEN
        issues:=issues||jsonb_build_array('OPENING_EFFECT_RECEIPT_REQUIRED');
    END IF;
    SELECT count(*) INTO expected FROM jsonb_array_elements(request.review_manifest->'cases')
        WHERE value->'resolution'->>'kind'='CANCEL_PENDING';
    IF expected<>(SELECT count(*) FROM warehouse_migration_cancellation_receipt receipt
        JOIN jsonb_array_elements(request.review_manifest->'cases') member ON receipt.case_id=(member->>'caseId')::uuid
            AND receipt.source_table=member->>'sourceTable' AND receipt.source_id=(member->>'sourceId')::uuid
            AND receipt.source_hash=member->>'sourceHash' AND receipt.resolution_id=(member->'resolution'->>'id')::uuid
        WHERE receipt.tenant_id=scope AND receipt.request_id=request.id AND member->'resolution'->>'kind'='CANCEL_PENDING')
        OR EXISTS(SELECT FROM warehouse_live_provenance_source live WHERE live.tenant_id=scope
            AND warehouse_migration_pending(live.source_table,live.source_snapshot)
            AND NOT EXISTS(SELECT FROM warehouse_migration_cancellation_receipt receipt WHERE receipt.tenant_id=scope
                AND receipt.request_id=request.id AND receipt.source_table=live.source_table AND receipt.source_id=live.source_id)) THEN
        issues:=issues||jsonb_build_array('LEGACY_CANCELLATION_REQUIRED');
    END IF;
    IF EXISTS(SELECT FROM warehouse_current_legacy_identity current_identity WHERE current_identity.tenant_id=scope
        AND NOT EXISTS(SELECT FROM inventory_identity_candidate candidate LEFT JOIN inventory_identity_claim claim
            ON claim.tenant_id=candidate.tenant_id AND claim.id=candidate.claim_id
            WHERE candidate.tenant_id=scope AND candidate.source_table=current_identity.source_table
                AND candidate.source_id=current_identity.source_id AND candidate.identity_type=current_identity.identity_type
                AND candidate.raw_value=current_identity.raw_value AND candidate.canonical_value IS NOT DISTINCT FROM current_identity.canonical_value
                AND ((current_identity.canonical_value IS NULL AND candidate.claim_id IS NULL)
                    OR (claim.identity_type=current_identity.identity_type AND claim.canonical_value=current_identity.canonical_value
                        AND (current_identity.source_table<>'inventory_serial_tombstone' OR claim.state='RETIRED'))))) THEN
        issues:=issues||jsonb_build_array('CURRENT_IDENTITY_RESERVATION_REQUIRED');
    END IF;
    SELECT count(*) INTO expected FROM inventory_document_line WHERE tenant_id=scope AND document_id=request.id;
    IF expected<>(SELECT count(*) FROM inventory_migration_admission_line WHERE tenant_id=scope AND request_id=request.id)
        OR expected<>(SELECT count(*) FROM inventory_balance_projection WHERE tenant_id=scope AND warehouse_admission='VERIFIED' AND quantity_base>0)
        OR EXISTS(SELECT FROM inventory_document_line line LEFT JOIN inventory_migration_admission_line binding
            ON binding.tenant_id=line.tenant_id AND binding.line_id=line.id AND binding.request_id=request.id
            WHERE line.tenant_id=scope AND line.document_id=request.id AND
                (binding.line_id IS NULL OR line.stock_identity_id IS DISTINCT FROM binding.stock_identity_id
                 OR (SELECT count(*) FROM inventory_balance_projection balance WHERE balance.tenant_id=scope
                    AND balance.warehouse_admission='VERIFIED' AND balance.stock_identity_id=binding.stock_identity_id
                    AND balance.sku_id=line.sku_id AND balance.lot_id IS NOT DISTINCT FROM line.lot_id
                    AND balance.location_id=line.location_id AND balance.custody_owner_id=line.custodian_id
                    AND balance.custody_owner_kind='WAREHOUSE' AND balance.status='AVAILABLE' AND balance.condition='SERVICEABLE'
                    AND balance.legal_owner='ISP' AND balance.base_unit=line.base_unit AND balance.quantity_base=line.quantity_base)<>1)) THEN
        issues:=issues||jsonb_build_array('BASELINE_STOCK_CHANGED');
    END IF;
    IF EXISTS(SELECT FROM inventory_migration_admission_line binding JOIN inventory_document_line line
        ON line.tenant_id=binding.tenant_id AND line.id=binding.line_id LEFT JOIN inventory_serialized_asset asset
        ON asset.tenant_id=binding.tenant_id AND asset.id=binding.stock_identity_id
        WHERE binding.tenant_id=scope AND binding.request_id=request.id AND line.tracking='SERIAL'
            AND (asset.id IS NULL OR asset.warehouse_admission<>'VERIFIED' OR asset.origin_document_line_id IS DISTINCT FROM line.id
                OR NOT EXISTS(SELECT FROM inventory_identity_claim WHERE tenant_id=scope AND identity_type='SERIAL'
                    AND canonical_value=asset.canonical_serial AND state='ADMITTED' AND admitted_asset_id=asset.id)
                OR (asset.canonical_mac IS NOT NULL AND NOT EXISTS(SELECT FROM inventory_identity_claim WHERE tenant_id=scope
                    AND identity_type='MAC' AND canonical_value=asset.canonical_mac AND state='ADMITTED' AND admitted_asset_id=asset.id)))) THEN
        issues:=issues||jsonb_build_array('ADMITTED_IDENTITY_CHANGED');
    END IF;
    RETURN issues;
END $$;

CREATE FUNCTION warehouse_migration_finalization_review(target uuid) RETURNS jsonb LANGUAGE sql AS $$
    WITH context AS (SELECT batch.tenant_id,cutover.state,cutover.epoch,batch.snapshot_watermark,batch.id,batch.cutover_epoch,
        batch.source_manifest,batch.source_count,batch.source_hash,
        admission.request_id,admission.approval_id,admission.review_hash
        FROM inventory_tenant_cutover cutover JOIN inventory_migration_batch batch
            ON batch.tenant_id=cutover.tenant_id AND batch.id=cutover.migration_batch_id
        LEFT JOIN inventory_migration_admission admission ON admission.tenant_id=batch.tenant_id AND admission.batch_id=batch.id
        WHERE cutover.tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid AND batch.id=target),
    kinds AS (SELECT unnest(ARRAY['inventory_serialized_asset','inventory_balance_projection','onu','inventory_serial_tombstone',
        'inventory_movement','inventory_movement_leg','inventory_fulfillment_effect','inventory_customer_material_fact',
        'fulfillment_checkpoint','fulfillment_outbox']) kind)
    SELECT jsonb_build_object('batchId',target,'cutover',jsonb_build_object('tenantId',context.tenant_id,'state',context.state,
        'epoch',context.epoch,'migrationBatchId',target,'snapshotWatermark',context.snapshot_watermark),
        'openingDocumentId',context.request_id,'approvalId',context.approval_id,'reviewHash',context.review_hash,
        'sourceHash',context.source_hash,'sourceCount',context.source_count,
        'sourceCounts',(SELECT jsonb_object_agg(kind,(SELECT count(*) FROM jsonb_array_elements(context.source_manifest) source
            WHERE source->>'sourceTable'=kind)) FROM kinds),
        'baselineCount',(SELECT count(*) FROM inventory_document_line WHERE tenant_id=context.tenant_id AND document_id=context.request_id),
        'baselineTotals',coalesce((SELECT jsonb_object_agg(base_unit,amount) FROM
            (SELECT base_unit,sum(quantity_base::numeric)::text amount FROM inventory_document_line
                WHERE tenant_id=context.tenant_id AND document_id=context.request_id GROUP BY base_unit) totals),'{}'::jsonb),
        'unresolvedHistoricalCount',(SELECT count(*) FROM inventory_migration_opening_request request,
            jsonb_array_elements(request.review_manifest->'cases') source
            WHERE request.tenant_id=context.tenant_id AND request.id=context.request_id AND source->'resolution'='null'::jsonb),
        'cancellationCount',(SELECT count(*) FROM warehouse_migration_cancellation_receipt
            WHERE tenant_id=context.tenant_id AND request_id=context.request_id),
        'retainedIdentityCount',(SELECT count(*) FROM inventory_identity_claim WHERE tenant_id=context.tenant_id AND state<>'ADMITTED'),
        'issues',warehouse_migration_finalization_issues(context.tenant_id,target),
        'finalization',(SELECT original_body::jsonb FROM inventory_migration_finalization WHERE tenant_id=context.tenant_id AND batch_id=target))
    FROM context
$$;

CREATE FUNCTION warehouse_finalize_migration(target uuid,p_actor uuid,p_authority_epoch bigint,p_key text,p_payload text)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE scope uuid:=NULLIF(current_setting('app.tenant_id',true),'')::uuid; cutover inventory_tenant_cutover;
    review jsonb; payload jsonb:=p_payload::jsonb; input jsonb:=payload->'input'; response jsonb;
    receipt_id uuid:=gen_random_uuid(); recorded_at timestamptz:=transaction_timestamp();
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO cutover FROM inventory_tenant_cutover WHERE tenant_id=scope FOR UPDATE;
    PERFORM warehouse_lock_migration_history(scope,target);
    IF cutover.state IS DISTINCT FROM 'VALIDATING' OR cutover.migration_batch_id IS DISTINCT FROM target
        OR cutover.epoch IS DISTINCT FROM (input->>'expectedEpoch')::bigint THEN
        RAISE EXCEPTION 'finalization requires the current validating epoch' USING ERRCODE='23514';
    END IF;
    review:=warehouse_migration_finalization_review(target);
    IF review IS NULL OR review->'issues'<>'[]'::jsonb
        OR review->>'openingDocumentId' IS DISTINCT FROM input->>'openingDocumentId'
        OR review->>'reviewHash' IS DISTINCT FROM input->>'expectedReviewHash' THEN
        RAISE EXCEPTION 'finalization requires the complete independently approved baseline' USING ERRCODE='23514';
    END IF;
    IF p_actor IS NULL OR p_authority_epoch IS NULL OR p_authority_epoch<0
        OR p_key IS NULL OR length(p_key) NOT BETWEEN 1 AND 240 OR p_key !~ '^[!-~]+$'
        OR length(btrim(coalesce(input->>'reason',''))) NOT BETWEEN 1 AND 2000 OR input->>'reason' ~ '[[:cntrl:]]'
        OR payload IS DISTINCT FROM jsonb_build_object('batchId',target,'input',jsonb_build_object(
            'expectedEpoch',cutover.epoch,'openingDocumentId',(review->>'openingDocumentId')::uuid,
            'expectedReviewHash',review->>'reviewHash','reason',input->>'reason')) THEN
        RAISE EXCEPTION 'finalization requires an exact command and actor audit' USING ERRCODE='23514';
    END IF;
    response:=(review-ARRAY['issues','finalization'])||jsonb_build_object(
        'id',receipt_id,'cutover',jsonb_build_object('tenantId',scope,'state','ENFORCED','epoch',cutover.epoch+1,
            'migrationBatchId',target,'snapshotWatermark',cutover.snapshot_watermark),
        'finalizedBy',p_actor,'authorityEpoch',p_authority_epoch,'reason',input->>'reason','finalizedAt',recorded_at);
    INSERT INTO inventory_migration_finalization(id,tenant_id,batch_id,request_id,actor_id,authority_epoch,
        operation_key,canonical_payload,payload_hash,previous_epoch,resulting_epoch,original_body,created_at)
        VALUES(receipt_id,scope,target,(review->>'openingDocumentId')::uuid,p_actor,p_authority_epoch,p_key,p_payload,
            encode(sha256(convert_to(p_payload,'UTF8')),'hex'),cutover.epoch,cutover.epoch+1,response::text,recorded_at);
    UPDATE inventory_tenant_cutover SET state='ENFORCED',epoch=epoch+1,revision=revision+1,updated_at=clock_timestamp()
        WHERE tenant_id=scope AND epoch=cutover.epoch AND state='VALIDATING';
    IF NOT FOUND THEN RAISE EXCEPTION 'locked cutover changed unexpectedly' USING ERRCODE='40001'; END IF;
    RETURN response::text;
END $$;
DO $$ BEGIN
    EXECUTE format('ALTER FUNCTION warehouse_finalize_migration(uuid,uuid,bigint,text,text) SET search_path TO pg_catalog,%I,pg_temp',current_schema());
    REVOKE ALL ON FUNCTION warehouse_finalize_migration(uuid,uuid,bigint,text,text) FROM PUBLIC;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT EXECUTE ON FUNCTION warehouse_finalize_migration(uuid,uuid,bigint,text,text) TO warehouse_app;
    END IF;
END $$;

-- Keep all prior initialization/epoch checks and replace only the closed final transition.
DO $$ DECLARE definition text; fragment text; replacement text; BEGIN
    definition:=pg_get_functiondef('warehouse_cutover_guard()'::regprocedure);
    fragment:='RAISE EXCEPTION ''independent migration approval unavailable until approval owner is installed'' USING ERRCODE=''42501'';';
    replacement:='IF NEW.migration_batch_id IS DISTINCT FROM OLD.migration_batch_id
                OR NEW.snapshot_watermark IS DISTINCT FROM OLD.snapshot_watermark
                OR NEW.pending_legacy_effect_ids IS DISTINCT FROM OLD.pending_legacy_effect_ids
                OR NOT EXISTS(SELECT FROM inventory_migration_finalization receipt WHERE receipt.tenant_id=NEW.tenant_id
                    AND receipt.batch_id=OLD.migration_batch_id AND receipt.previous_epoch=OLD.epoch
                    AND receipt.resulting_epoch=NEW.epoch AND receipt.created_xid=pg_current_xact_id()) THEN
                RAISE EXCEPTION ''cutover requires its current owner finalization receipt'' USING ERRCODE=''42501'';
            END IF;';
    IF strpos(definition,fragment)=0 THEN RAISE EXCEPTION 'cutover guard layout changed'; END IF;
    EXECUTE replace(definition,fragment,replacement);
END $$;

CREATE FUNCTION warehouse_migration_finalization_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE cutover inventory_tenant_cutover; request inventory_migration_opening_request; body jsonb:=NEW.original_body::jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO cutover FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id;
    SELECT * INTO request FROM inventory_migration_opening_request WHERE tenant_id=NEW.tenant_id AND id=NEW.request_id;
    IF NEW.created_xid<>pg_current_xact_id() OR cutover.state IS DISTINCT FROM 'ENFORCED'
        OR cutover.epoch<>NEW.resulting_epoch OR cutover.migration_batch_id<>NEW.batch_id
        OR request.batch_id IS DISTINCT FROM NEW.batch_id
        OR NEW.payload_hash<>encode(sha256(convert_to(NEW.canonical_payload,'UTF8')),'hex')
        OR body->>'id' IS DISTINCT FROM NEW.id::text OR body->>'batchId' IS DISTINCT FROM NEW.batch_id::text
        OR body->>'openingDocumentId' IS DISTINCT FROM NEW.request_id::text
        OR body->>'finalizedBy' IS DISTINCT FROM NEW.actor_id::text OR body->>'reviewHash' IS DISTINCT FROM request.review_hash
        OR (body->>'authorityEpoch')::bigint IS DISTINCT FROM NEW.authority_epoch
        OR (body->>'finalizedAt')::timestamptz IS DISTINCT FROM NEW.created_at
        OR body->'cutover' IS DISTINCT FROM jsonb_build_object('tenantId',NEW.tenant_id,'state','ENFORCED',
            'epoch',NEW.resulting_epoch,'migrationBatchId',NEW.batch_id,'snapshotWatermark',cutover.snapshot_watermark) THEN
        RAISE EXCEPTION 'finalization receipt requires its exact atomic epoch transition' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_migration_finalization_complete AFTER INSERT ON inventory_migration_finalization
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_migration_finalization_complete();

-- Older NOT VALID legacy FKs cannot be validated globally: preserved orphan references
-- remain staged. Check the exact reference scope for VERIFIED rows, including old verified
-- rows, without adding fields or rewriting serialized source snapshots.
CREATE FUNCTION warehouse_verified_reference_valid(scope uuid,source_kind text,data jsonb) RETURNS boolean LANGUAGE plpgsql STABLE AS $$
BEGIN
    IF data->>'warehouse_admission' IS DISTINCT FROM 'VERIFIED' THEN RETURN true; END IF;
    IF source_kind IN ('inventory_serialized_asset','inventory_movement_leg','inventory_balance_projection')
        AND data->>'location_id' IS NOT NULL
        AND NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=(data->>'location_id')::uuid) THEN RETURN false; END IF;
    IF source_kind='onu' AND NOT EXISTS(SELECT FROM customer_provenance_reference
        WHERE tenant_id=scope AND id=(data->>'customer_id')::uuid) THEN RETURN false; END IF;
    IF source_kind='inventory_movement_leg' AND NOT EXISTS(SELECT FROM inventory_movement
        WHERE tenant_id=scope AND id=(data->>'movement_id')::uuid) THEN RETURN false; END IF;
    IF source_kind='inventory_movement' AND data->>'compensates_movement_id' IS NOT NULL AND NOT EXISTS(
        SELECT FROM inventory_movement WHERE tenant_id=scope AND id=(data->>'compensates_movement_id')::uuid) THEN RETURN false; END IF;
    RETURN true;
END $$;
CREATE FUNCTION warehouse_verified_reference_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NOT warehouse_verified_reference_valid(NEW.tenant_id,TG_TABLE_NAME,to_jsonb(NEW)) THEN
        RAISE EXCEPTION 'verified source reference must exist in the same tenant' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
DO $$ DECLARE relation text; tenant_scope uuid; valid boolean; previous_scope text:=current_setting('app.tenant_id',true); BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_serialized_asset','inventory_movement','inventory_movement_leg','inventory_balance_projection','onu'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_verified_reference AFTER INSERT OR UPDATE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_verified_reference_guard()',relation);
        FOR tenant_scope IN SELECT id FROM tenant LOOP
            PERFORM set_config('app.tenant_id',tenant_scope::text,true);
            EXECUTE format('SELECT NOT EXISTS(SELECT FROM %I source WHERE tenant_id=$1 AND warehouse_admission=''VERIFIED''
                AND NOT warehouse_verified_reference_valid($1,$2,to_jsonb(source)))',relation) INTO valid USING tenant_scope,relation;
            IF NOT valid THEN RAISE EXCEPTION 'verified source reference validation failed for %',relation; END IF;
        END LOOP;
    END LOOP;
    PERFORM set_config('app.tenant_id',coalesce(previous_scope,''),true);
END $$;
