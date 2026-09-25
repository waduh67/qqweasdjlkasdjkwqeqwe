-- Owner-published reference contracts used to bind current source scope into approval.
CREATE VIEW customer_provenance_reference WITH (security_invoker=true) AS
    SELECT tenant_id,id,name,area_id FROM customer;
CREATE VIEW workorder_provenance_reference WITH (security_invoker=true) AS
    SELECT tenant_id,id,code,customer_id,area_id FROM work_order;

CREATE FUNCTION warehouse_migration_source_access(p_batch uuid) RETURNS jsonb LANGUAGE sql STABLE AS $$
    WITH batch AS (SELECT * FROM inventory_migration_batch
        WHERE tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid AND id=p_batch),
    sources AS (SELECT source.source_snapshot FROM batch,jsonb_array_elements(batch.source_manifest) member
        JOIN inventory_provenance_case source ON source.id=(member->>'caseId')::uuid
        WHERE source.tenant_id=batch.tenant_id AND source.source_hash=member->>'sourceHash'),
    orders AS (SELECT reference.* FROM workorder_provenance_reference reference JOIN batch ON reference.tenant_id=batch.tenant_id
        WHERE reference.id IN (SELECT (source_snapshot->>'workOrderId')::uuid FROM sources)),
    customers AS (SELECT reference.* FROM customer_provenance_reference reference JOIN batch ON reference.tenant_id=batch.tenant_id
        WHERE reference.id IN (SELECT (source_snapshot->>'customerId')::uuid FROM sources UNION SELECT customer_id FROM orders))
    SELECT jsonb_build_object('workOrders',coalesce((SELECT jsonb_agg(jsonb_build_object('id',id,'code',code,
        'customerId',customer_id,'areaId',area_id) ORDER BY id) FROM orders),'[]'::jsonb),
        'customers',coalesce((SELECT jsonb_agg(jsonb_build_object('id',id,'name',name,'areaId',area_id) ORDER BY id) FROM customers),'[]'::jsonb))
$$;

-- Immutable approval/history reads must also work after the successful epoch transition.
CREATE FUNCTION warehouse_lock_migration_history(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM 1 FROM inventory_tenant_cutover WHERE tenant_id=scope AND migration_batch_id=target FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'migration batch is not the current tenant history' USING ERRCODE='23514'; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(scope::text||'|migration-batch|'||target::text,0));
    IF NOT EXISTS (SELECT FROM inventory_migration_batch WHERE tenant_id=scope AND id=target) THEN
        RAISE EXCEPTION 'captured migration batch required' USING ERRCODE='23514';
    END IF;
END $$;

-- Only the checked admission function below may insert these witnesses. Their
-- deferred checks require the single posting owner and durable approval effect.
CREATE TABLE inventory_migration_admission (
    tenant_id uuid NOT NULL, batch_id uuid NOT NULL, request_id uuid NOT NULL, approval_id uuid NOT NULL,
    operation_id uuid NOT NULL, cutover_epoch bigint NOT NULL, review_hash text NOT NULL,
    source_access jsonb NOT NULL, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY(tenant_id,batch_id), UNIQUE(tenant_id,request_id), UNIQUE(tenant_id,approval_id), UNIQUE(tenant_id,operation_id),
    FOREIGN KEY(tenant_id,batch_id) REFERENCES inventory_migration_batch(tenant_id,id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_migration_opening_request(tenant_id,id),
    FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE inventory_migration_admission_line (
    tenant_id uuid NOT NULL, request_id uuid NOT NULL, case_id uuid NOT NULL, line_id uuid NOT NULL,
    stock_identity_id uuid NOT NULL, lot_id uuid, original_asset jsonb,
    PRIMARY KEY(tenant_id,request_id,case_id), UNIQUE(tenant_id,line_id), UNIQUE(tenant_id,stock_identity_id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_migration_admission(tenant_id,request_id),
    FOREIGN KEY(tenant_id,case_id) REFERENCES inventory_provenance_case(tenant_id,id),
    FOREIGN KEY(tenant_id,line_id) REFERENCES inventory_document_line(tenant_id,id),
    FOREIGN KEY(tenant_id,stock_identity_id) REFERENCES inventory_segment(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);

DO $$ DECLARE table_name text; BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_migration_admission','inventory_migration_admission_line'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('REVOKE INSERT,UPDATE,DELETE ON %I FROM warehouse_app',table_name);
            EXECUTE format('GRANT SELECT ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT ON customer_provenance_reference,workorder_provenance_reference TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_migration_opening_approval_current(scope uuid,target uuid,p_approval uuid,decided boolean)
RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE request inventory_migration_opening_request; approval inventory_approval; document inventory_document;
    manifest jsonb; access_snapshot jsonb; tiers jsonb; participant uuid; needed integer;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO request FROM inventory_migration_opening_request WHERE tenant_id=scope AND id=target;
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=scope AND id=p_approval;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=target;
    IF request.id IS NULL OR approval.id IS NULL OR document.id IS NULL OR document.kind<>'OPENING_BALANCE'
        OR document.state<>'DRAFT' OR document.revision<>0 OR document.approval_disposition IS NOT NULL
        OR approval.source_document_id IS DISTINCT FROM target OR approval.source_document_revision<>0
        OR approval.requester_id IS DISTINCT FROM request.actor_id OR approval.business_action IS DISTINCT FROM 'OPENING_BALANCE'
        OR approval.status IS DISTINCT FROM (CASE WHEN decided THEN 'APPROVED' ELSE 'PENDING' END)
        OR approval.expires_at<=clock_timestamp() OR approval.cutover_epoch IS DISTINCT FROM request.cutover_epoch
        OR approval.amount IS NOT NULL OR approval.value_numerator IS NOT NULL OR approval.value_denominator IS NOT NULL OR approval.currency IS NOT NULL
        OR NOT EXISTS (SELECT FROM inventory_location WHERE tenant_id=scope AND id=request.review_location_id
            AND revision=request.review_location_revision AND state='ACTIVE' AND kind IN ('WAREHOUSE','BIN'))
        OR NOT EXISTS (SELECT FROM inventory_tenant_cutover WHERE tenant_id=scope AND state='VALIDATING'
            AND epoch=request.cutover_epoch AND migration_batch_id=request.batch_id) THEN RETURN false; END IF;
    manifest:=warehouse_migration_review_manifest(request.batch_id);
    access_snapshot:=warehouse_migration_source_access(request.batch_id);
    IF manifest IS DISTINCT FROM request.review_manifest OR warehouse_migration_review_issues(manifest)<>'[]'::jsonb
        OR approval.source_snapshot::jsonb->'document' IS DISTINCT FROM to_jsonb(document)
        OR approval.source_snapshot::jsonb->'lines' IS DISTINCT FROM coalesce((SELECT jsonb_agg(to_jsonb(line) ORDER BY line.id)
            FROM inventory_document_line line WHERE line.tenant_id=scope AND line.document_id=target),'null'::jsonb)
        OR approval.source_snapshot::jsonb->'opening' IS DISTINCT FROM request.original_body::jsonb
        OR approval.source_snapshot::jsonb->'openingCurrentReview' IS DISTINCT FROM manifest
        OR approval.source_snapshot::jsonb->'openingCurrentAccess' IS DISTINCT FROM access_snapshot
        OR approval.source_snapshot_hash IS DISTINCT FROM encode(sha256(convert_to(approval.source_snapshot,'UTF8')),'hex')
        OR NOT EXISTS (SELECT FROM inventory_approval_policy_version policy WHERE policy.tenant_id=scope
            AND policy.id=approval.policy_version_id AND policy.snapshot::jsonb=approval.policy_snapshot) THEN RETURN false; END IF;
    SELECT rule->'tiers' INTO tiers FROM jsonb_array_elements(approval.policy_snapshot->'rules') rule WHERE rule->>'operation'='OPENING_BALANCE';
    needed:=jsonb_array_length(tiers);
    IF needed IS NULL OR needed<1 OR jsonb_array_length(approval.evaluation_snapshot::jsonb#>'{evaluation,tiers}') IS DISTINCT FROM needed
        OR (SELECT count(*) FROM inventory_approval_requirement WHERE tenant_id=scope AND approval_id=p_approval)<>needed
        OR EXISTS (SELECT FROM jsonb_array_elements(tiers) WITH ORDINALITY required(tier,position)
            LEFT JOIN inventory_approval_requirement captured ON captured.tenant_id=scope AND captured.approval_id=p_approval AND captured.tier=required.position
            WHERE captured.id IS NULL OR captured.requirement->>'minimumMinor' IS DISTINCT FROM tier->>'minimumMinor'
                OR captured.requirement IS DISTINCT FROM approval.evaluation_snapshot::jsonb#>'{evaluation,tiers}'->(required.position::integer-1)) THEN
        RETURN false;
    END IF;
    FOR participant IN
        SELECT request.actor_id UNION SELECT requested_by FROM inventory_migration_batch WHERE tenant_id=scope AND id=request.batch_id
        UNION SELECT (entry->'resolution'->>'resolvedBy')::uuid FROM jsonb_array_elements(manifest->'cases') entry WHERE entry->'resolution'->>'id' IS NOT NULL
        UNION SELECT (file->>'uploadedBy')::uuid FROM jsonb_array_elements(manifest->'cases') entry,
            jsonb_array_elements(coalesce(nullif(entry->'resolution'->'evidence','null'::jsonb),'[]'::jsonb)) file
    LOOP
        IF NOT coalesce(approval.independence_snapshot ? participant::text,false) THEN RETURN false; END IF;
    END LOOP;
    IF decided AND (SELECT count(*) FROM inventory_approval_decision WHERE tenant_id=scope AND approval_id=p_approval AND decision='APPROVE')<>needed THEN
        RETURN false;
    END IF;
    RETURN true;
END $$;

CREATE OR REPLACE FUNCTION warehouse_assert_opening_approval(owner_tenant uuid,document_id uuid,document_revision bigint)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(owner_tenant);
    IF document_revision<>1 OR NOT EXISTS (
        SELECT FROM inventory_migration_admission admitted
        JOIN inventory_approval approval ON approval.tenant_id=admitted.tenant_id AND approval.id=admitted.approval_id
        JOIN inventory_operation operation ON operation.tenant_id=admitted.tenant_id AND operation.id=admitted.operation_id
        WHERE admitted.tenant_id=owner_tenant AND admitted.request_id=document_id
            AND approval.status='APPROVED' AND approval.business_action='OPENING_BALANCE'
            AND approval.source_document_id=document_id AND approval.source_document_revision=0
            AND operation.document_id=document_id AND operation.document_revision=1 AND operation.namespace='warehouse.approval.effect'
            AND operation.operation_key=approval.id::text AND operation.business_action='OPENING_BALANCE'
            AND operation.payload_hash=approval.source_snapshot_hash AND operation.original_body=approval.terminal_body
    ) THEN RAISE EXCEPTION 'opening origin requires its actual independently approved posting' USING ERRCODE='23514'; END IF;
END $$;

CREATE OR REPLACE FUNCTION warehouse_migration_opening_document_seal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; scope uuid; admitted inventory_migration_admission; binding inventory_migration_admission_line;
BEGIN
    IF TG_TABLE_NAME='inventory_document' THEN target:=OLD.id; scope:=OLD.tenant_id;
    ELSIF TG_OP='INSERT' THEN target:=NEW.document_id; scope:=NEW.tenant_id;
    ELSE target:=OLD.document_id; scope:=OLD.tenant_id; END IF;
    IF NOT EXISTS (SELECT FROM inventory_migration_opening_request WHERE tenant_id=scope AND id=target) THEN
        IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
    END IF;
    SELECT * INTO admitted FROM inventory_migration_admission WHERE tenant_id=scope AND request_id=target AND created_xid=pg_current_xact_id();
    IF TG_TABLE_NAME='inventory_document_line' AND TG_OP='UPDATE' AND admitted.request_id IS NOT NULL THEN
        SELECT * INTO binding FROM inventory_migration_admission_line WHERE tenant_id=scope AND request_id=target AND line_id=OLD.id;
        IF binding.line_id IS NOT NULL AND OLD.stock_identity_id IS NULL AND OLD.lot_id IS NULL
            AND NEW.stock_identity_id=binding.stock_identity_id AND NEW.lot_id IS NOT DISTINCT FROM binding.lot_id
            AND (to_jsonb(NEW)-ARRAY['stock_identity_id','lot_id','revision','updated_at'])=
                (to_jsonb(OLD)-ARRAY['stock_identity_id','lot_id','revision','updated_at']) THEN RETURN NEW; END IF;
    ELSIF TG_TABLE_NAME='inventory_document' AND TG_OP='UPDATE' THEN
        IF OLD.state='DRAFT' AND OLD.revision=0 AND NEW.state='POSTED' AND NEW.revision=1 AND admitted.request_id IS NOT NULL
            AND (to_jsonb(NEW)-ARRAY['state','revision','updated_at'])=(to_jsonb(OLD)-ARRAY['state','revision','updated_at']) THEN
            PERFORM warehouse_assert_opening_approval(scope,target,NEW.revision);
            RETURN NEW;
        END IF;
        IF OLD.state='DRAFT' AND OLD.revision=0 AND OLD.approval_disposition IS NULL AND NEW.state='DRAFT'
            AND NEW.revision=1 AND NEW.approval_disposition='REWORK_REQUIRED'
            AND (to_jsonb(NEW)-ARRAY['approval_disposition','revision','updated_at'])=(to_jsonb(OLD)-ARRAY['approval_disposition','revision','updated_at'])
            AND EXISTS (SELECT FROM inventory_approval approval JOIN inventory_approval_decision decision
                ON decision.tenant_id=approval.tenant_id AND decision.approval_id=approval.id
                WHERE approval.tenant_id=scope AND approval.source_document_id=target AND approval.source_document_revision=0
                    AND approval.status='REWORK_REQUIRED' AND decision.decision='REJECT') THEN RETURN NEW; END IF;
    END IF;
    RAISE EXCEPTION 'sealed opening requires its actual approved admission or rejected outcome' USING ERRCODE='23514';
END $$;

CREATE OR REPLACE FUNCTION warehouse_origin_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target_sku uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.warehouse_admission<>'VERIFIED' THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND NEW.origin_document_line_id IS DISTINCT FROM OLD.origin_document_line_id AND NOT (
        TG_TABLE_NAME='inventory_serialized_asset' AND OLD.warehouse_admission='LEGACY_UNRESOLVED' AND OLD.origin_document_line_id IS NULL
        AND EXISTS (SELECT FROM inventory_migration_admission_line binding JOIN inventory_migration_admission admitted
            ON admitted.tenant_id=binding.tenant_id AND admitted.request_id=binding.request_id
            WHERE binding.tenant_id=NEW.tenant_id AND binding.stock_identity_id=NEW.id AND binding.line_id=NEW.origin_document_line_id
                AND binding.original_asset=to_jsonb(OLD) AND admitted.created_xid=pg_current_xact_id())) THEN
        RAISE EXCEPTION 'warehouse origin is immutable' USING ERRCODE='23514';
    END IF;
    target_sku:=CASE WHEN TG_TABLE_NAME='inventory_lot' THEN to_jsonb(NEW)->>'sku_id' ELSE to_jsonb(NEW)->>'warehouse_sku_id' END;
    PERFORM warehouse_assert_stock_origin(NEW.tenant_id,NEW.origin_document_line_id,target_sku,NEW.base_unit);
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_admit_migration_opening(target uuid,p_approval uuid,p_operation uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER AS $$
DECLARE scope uuid:=NULLIF(current_setting('app.tenant_id',true),'')::uuid; request inventory_migration_opening_request;
    entry jsonb; stock jsonb; line inventory_document_line; asset inventory_serialized_asset; claim inventory_identity_claim;
    identity_kind text; identity_value text; stock_id uuid; lot_id uuid; number integer:=0; cutoff timestamptz;
BEGIN
    IF scope IS NULL THEN RAISE EXCEPTION 'opening admission requires tenant context' USING ERRCODE='23514'; END IF;
    PERFORM 1 FROM inventory_tenant_cutover WHERE tenant_id=scope FOR SHARE;
    SELECT * INTO request FROM inventory_migration_opening_request WHERE tenant_id=scope AND id=target;
    IF request.id IS NULL THEN RAISE EXCEPTION 'opening request missing' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_lock_migration_batch(scope,request.batch_id);
    PERFORM 1 FROM inventory_document WHERE tenant_id=scope AND id=target FOR UPDATE;
    PERFORM 1 FROM inventory_approval WHERE tenant_id=scope AND id=p_approval FOR UPDATE;
    IF NOT warehouse_migration_opening_approval_current(scope,target,p_approval,true) THEN
        RAISE EXCEPTION 'opening admission requires current independent approval and exact source' USING ERRCODE='23514';
    END IF;
    INSERT INTO inventory_migration_admission(tenant_id,batch_id,request_id,approval_id,operation_id,cutover_epoch,review_hash,source_access)
        VALUES(scope,request.batch_id,target,p_approval,p_operation,request.cutover_epoch,request.review_hash,warehouse_migration_source_access(request.batch_id));
    SELECT created_at INTO cutoff FROM inventory_migration_batch WHERE tenant_id=scope AND id=request.batch_id;
    FOR entry IN SELECT value FROM jsonb_array_elements(request.review_manifest->'cases') LOOP
        IF entry->'resolution'->>'kind' IS DISTINCT FROM 'BASELINE_STOCK' THEN CONTINUE; END IF;
        number:=number+1; stock:=entry->'resolution'->'stock'; lot_id:=NULL;
        SELECT * INTO STRICT line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target AND line_number=number FOR UPDATE;
        IF entry->>'sourceTable'='inventory_serialized_asset' THEN
            stock_id:=(entry->>'sourceId')::uuid;
            SELECT * INTO STRICT asset FROM inventory_serialized_asset WHERE tenant_id=scope AND id=stock_id FOR UPDATE;
            IF asset.warehouse_admission<>'LEGACY_UNRESOLVED' OR asset.origin_document_line_id IS NOT NULL THEN
                RAISE EXCEPTION 'opening must preserve the original unresolved physical asset' USING ERRCODE='23514';
            END IF;
            FOR identity_kind,identity_value IN SELECT kind,value FROM (VALUES
                ('MAC',warehouse_canonical_mac(asset.mac_address)),('SERIAL',warehouse_canonical_serial(asset.serial_number))) identities(kind,value)
                WHERE value IS NOT NULL ORDER BY kind,value LOOP
                PERFORM pg_advisory_xact_lock(hashtextextended(scope::text||'|receipt-identity|'||identity_kind||'|'||identity_value,0));
                SELECT * INTO claim FROM inventory_identity_claim WHERE tenant_id=scope AND identity_type=identity_kind AND canonical_value=identity_value FOR UPDATE;
                IF claim.state='RETIRED' OR (claim.admitted_asset_id IS NOT NULL AND claim.admitted_asset_id<>stock_id) THEN
                    RAISE EXCEPTION 'opening cannot steal an admitted or retired identity' USING ERRCODE='23514';
                END IF;
                -- Old candidates remain reservations even when their physical source has since
                -- changed/disappeared. Only this exact asset or a reviewed duplicate may agree.
                IF EXISTS (SELECT FROM inventory_identity_candidate candidate WHERE candidate.tenant_id=scope AND candidate.identity_type=identity_kind
                    AND (candidate.canonical_value=identity_value OR CASE identity_kind WHEN 'SERIAL' THEN warehouse_canonical_serial(candidate.raw_value)
                        ELSE warehouse_canonical_mac(candidate.raw_value) END=identity_value)
                    AND NOT (candidate.source_table='inventory_serialized_asset' AND candidate.source_id=stock_id)
                    AND NOT EXISTS (SELECT FROM jsonb_array_elements(request.review_manifest->'cases') peer
                        WHERE peer->>'sourceTable'=candidate.source_table AND peer->>'sourceId'=candidate.source_id::text AND
                            ((peer->'resolution'->>'kind'='DUPLICATE' AND peer->'resolution'->>'duplicateCaseId'=entry->>'caseId'
                                AND peer->'resolution'->>'duplicateResolutionId'=entry->'resolution'->>'id')
                            OR (candidate.source_table='onu' AND peer->'sourceSnapshot'->>'assetId'=stock_id::text
                                AND peer->'sourceSnapshot'->>'retiredAt' IS NOT NULL)))) THEN
                    RAISE EXCEPTION 'opening identity has unresolved historical reservations' USING ERRCODE='23514';
                END IF;
                IF claim.id IS NULL THEN
                    INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state,admitted_asset_id)
                        VALUES(gen_random_uuid(),scope,identity_kind,identity_value,'ADMITTED',stock_id);
                ELSE
                    UPDATE inventory_identity_claim SET state='ADMITTED',admitted_asset_id=stock_id,revision=revision+1,updated_at=clock_timestamp()
                        WHERE tenant_id=scope AND id=claim.id;
                END IF;
            END LOOP;
            INSERT INTO inventory_migration_admission_line(tenant_id,request_id,case_id,line_id,stock_identity_id,original_asset)
                VALUES(scope,target,(entry->>'caseId')::uuid,line.id,stock_id,to_jsonb(asset));
            UPDATE inventory_serialized_asset SET warehouse_sku_id=line.sku_id,canonical_serial=warehouse_canonical_serial(serial_number),
                canonical_mac=warehouse_canonical_mac(mac_address),base_unit='EA',quantity_base=1,condition='SERVICEABLE',legal_owner='ISP',
                origin_document_line_id=line.id,warehouse_admission='VERIFIED',revision=revision+1,updated_at=clock_timestamp()
                WHERE tenant_id=scope AND id=stock_id;
            INSERT INTO inventory_segment(id,tenant_id,sku_id,asset_id,kind,base_unit,quantity_base)
                VALUES(stock_id,scope,line.sku_id,stock_id,'SERIAL','EA',1);
        ELSE
            stock_id:=gen_random_uuid(); lot_id:=gen_random_uuid();
            INSERT INTO inventory_migration_admission_line(tenant_id,request_id,case_id,line_id,stock_identity_id,lot_id)
                VALUES(scope,target,(entry->>'caseId')::uuid,line.id,stock_id,lot_id);
            INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,origin_document_line_id)
                VALUES(lot_id,scope,line.sku_id,'OPEN-'||target::text||'-'||(entry->>'caseId'),line.base_unit,line.quantity_base,cutoff,line.id);
            INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base)
                VALUES(stock_id,scope,line.sku_id,lot_id,CASE WHEN line.base_unit='MM' THEN 'REEL' ELSE 'BULK' END,line.base_unit,line.quantity_base);
        END IF;
        UPDATE inventory_document_line SET stock_identity_id=stock_id,lot_id=warehouse_admit_migration_opening.lot_id,revision=revision+1,updated_at=clock_timestamp()
            WHERE tenant_id=scope AND id=line.id;
    END LOOP;
END $$;

-- Resolve names in the migration's actual schema, including isolated upgrade tests;
-- put pg_temp last so callers cannot shadow referenced owner tables/functions.
DO $$ BEGIN
    EXECUTE format('ALTER FUNCTION warehouse_admit_migration_opening(uuid,uuid,uuid) SET search_path=pg_catalog,%I,pg_temp',current_schema());
    REVOKE ALL ON FUNCTION warehouse_admit_migration_opening(uuid,uuid,uuid) FROM PUBLIC;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT EXECUTE ON FUNCTION warehouse_admit_migration_opening(uuid,uuid,uuid) TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_migration_admission_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE request inventory_migration_opening_request; approval inventory_approval; document inventory_document;
    operation inventory_operation; movement inventory_movement; baseline integer; entry jsonb; binding inventory_migration_admission_line;
    line inventory_document_line; piece inventory_segment; asset inventory_serialized_asset; lot inventory_lot;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT request FROM inventory_migration_opening_request WHERE tenant_id=NEW.tenant_id AND id=NEW.request_id;
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=NEW.tenant_id AND id=NEW.approval_id;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.request_id;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=NEW.tenant_id AND id=NEW.operation_id;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=NEW.tenant_id AND operation_id=NEW.operation_id;
    SELECT count(*) INTO baseline FROM jsonb_array_elements(request.review_manifest->'cases') source WHERE source->'resolution'->>'kind'='BASELINE_STOCK';
    IF NEW.created_xid<>pg_current_xact_id() OR request.batch_id<>NEW.batch_id OR request.review_hash<>NEW.review_hash
        OR document.id IS NULL OR document.state<>'POSTED' OR document.revision<>1
        OR approval.id IS NULL OR approval.status<>'APPROVED' OR approval.source_document_id<>request.id OR approval.source_document_revision<>0
        OR approval.business_action<>'OPENING_BALANCE' OR approval.cutover_epoch<>NEW.cutover_epoch
        OR operation.id IS NULL OR operation.namespace<>'warehouse.approval.effect' OR operation.operation_key<>approval.id::text
        OR operation.document_id<>request.id OR operation.document_revision<>1 OR operation.business_action<>'OPENING_BALANCE'
        OR operation.cutover_epoch<>NEW.cutover_epoch OR operation.payload_hash<>approval.source_snapshot_hash
        OR operation.original_status<>200 OR operation.original_body<>approval.terminal_body
        OR movement.id IS NULL OR movement.document_id<>request.id OR movement.document_revision<>1
        OR movement.operation_namespace<>operation.namespace OR movement.operation_key<>operation.operation_key
        OR movement.payload_hash<>operation.payload_hash OR movement.actor_id<>operation.actor_id
        OR movement.kind<>'OPENING_BALANCE' OR movement.state<>'APPLIED' OR movement.warehouse_admission<>'VERIFIED'
        OR (SELECT count(*) FROM inventory_document_line WHERE tenant_id=NEW.tenant_id AND document_id=request.id)<>baseline
        OR (SELECT count(*) FROM inventory_migration_admission_line WHERE tenant_id=NEW.tenant_id AND request_id=request.id)<>baseline
        OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=NEW.tenant_id AND movement_id=movement.id)<>baseline*2 THEN
        RAISE EXCEPTION 'opening admission requires its unique atomic baseline posting' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT FROM inventory_approval_effect effect JOIN inventory_outbox event
        ON event.tenant_id=effect.tenant_id AND event.id=effect.event_id
        JOIN inventory_inbox inbox ON inbox.tenant_id=event.tenant_id AND inbox.event_id=event.id AND inbox.consumer='warehouse.approval.receipt'
        WHERE effect.tenant_id=NEW.tenant_id AND effect.approval_id=approval.id AND effect.posting_operation_id=operation.id
            AND effect.source_document_id=request.id AND effect.source_document_revision=0 AND effect.original_body=operation.original_body
            AND event.operation_id=operation.id AND event.document_id=request.id AND event.document_revision=1 AND event.event_kind='OPENING_POSTED'
            AND event.payload::jsonb->>'postingId'=movement.id::text
            AND jsonb_array_length(event.payload::jsonb->'legs')=baseline*2
            AND event.payload::jsonb->'reservations'='[]'::jsonb AND event.payload::jsonb->'splits'='[]'::jsonb) THEN
        RAISE EXCEPTION 'opening admission requires durable approval event and delivery receipts' USING ERRCODE='23514';
    END IF;
    FOR entry IN SELECT value FROM jsonb_array_elements(request.review_manifest->'cases') LOOP
        IF entry->'resolution'->>'kind' IS DISTINCT FROM 'BASELINE_STOCK' THEN CONTINUE; END IF;
        SELECT * INTO binding FROM inventory_migration_admission_line WHERE tenant_id=NEW.tenant_id AND request_id=request.id AND case_id=(entry->>'caseId')::uuid;
        SELECT * INTO line FROM inventory_document_line WHERE tenant_id=NEW.tenant_id AND id=binding.line_id;
        SELECT * INTO piece FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=binding.stock_identity_id;
        IF binding.case_id IS NULL OR line.id IS NULL OR piece.id IS NULL OR line.document_id<>request.id
            OR line.stock_identity_id<>binding.stock_identity_id OR line.lot_id IS DISTINCT FROM binding.lot_id
            OR line.sku_id::text IS DISTINCT FROM entry->'resolution'->'stock'->>'skuId'
            OR line.base_unit IS DISTINCT FROM entry->'resolution'->'stock'->>'baseUnit'
            OR line.tracking IS DISTINCT FROM entry->'resolution'->'stock'->>'tracking'
            OR line.quantity_base::text IS DISTINCT FROM entry->'resolution'->'stock'->>'quantityBase'
            OR line.location_id::text IS DISTINCT FROM entry->'resolution'->'stock'->>'locationId'
            OR line.custodian_id IS DISTINCT FROM line.location_id OR line.custodian_kind<>'WAREHOUSE'
            OR line.condition<>'SERVICEABLE' OR line.legal_owner<>'ISP' OR line.cost_total_minor IS NOT NULL
            OR line.cost_basis_quantity_base IS NOT NULL OR line.currency IS NOT NULL
            OR piece.sku_id<>line.sku_id OR piece.base_unit<>line.base_unit OR piece.quantity_base<>line.quantity_base
            OR piece.lot_id IS DISTINCT FROM binding.lot_id OR piece.warehouse_admission<>'VERIFIED' OR piece.state<>'ACTIVE' THEN
            RAISE EXCEPTION 'admitted opening identity must match its reviewed original physical stock' USING ERRCODE='23514';
        END IF;
        IF (SELECT count(*) FROM inventory_movement_leg leg WHERE leg.tenant_id=NEW.tenant_id AND leg.movement_id=movement.id
            AND leg.document_line_id=line.id AND leg.stock_identity_id=piece.id AND leg.sku_id=line.sku_id AND leg.base_unit=line.base_unit
            AND leg.quantity_base=line.quantity_base AND leg.lot_id IS NOT DISTINCT FROM binding.lot_id AND leg.location_id=line.location_id
            AND leg.custody_owner_id=line.location_id AND leg.custody_owner_kind='WAREHOUSE' AND leg.condition='SERVICEABLE' AND leg.legal_owner='ISP'
            AND ((leg.direction='OUT' AND leg.status='RECEIPT_SOURCE' AND leg.revision=0) OR
                (leg.direction='IN' AND leg.status='AVAILABLE' AND leg.revision=1)))<>2 THEN
            RAISE EXCEPTION 'opening legs must conserve the exact reviewed stock at its original warehouse' USING ERRCODE='23514';
        END IF;
        IF (SELECT count(*) FROM inventory_balance_projection balance WHERE balance.tenant_id=NEW.tenant_id
            AND balance.stock_identity_id=piece.id AND balance.warehouse_admission='VERIFIED')<>1 OR NOT EXISTS (
            SELECT FROM inventory_balance_projection balance WHERE balance.tenant_id=NEW.tenant_id AND balance.stock_identity_id=piece.id
                AND balance.warehouse_admission='VERIFIED' AND balance.quantity_base=line.quantity_base AND balance.base_unit=line.base_unit
                AND balance.status='AVAILABLE' AND balance.location_id=line.location_id AND balance.custody_owner_id=line.location_id
                AND balance.custody_owner_kind='WAREHOUSE' AND balance.condition='SERVICEABLE' AND balance.legal_owner='ISP') THEN
            RAISE EXCEPTION 'opening availability must equal its single physical baseline' USING ERRCODE='23514';
        END IF;
        IF entry->>'sourceTable'='inventory_serialized_asset' THEN
            SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=piece.asset_id;
            IF asset.id IS NULL OR asset.id::text IS DISTINCT FROM entry->>'sourceId' OR binding.original_asset IS NULL
                OR asset.origin_document_line_id<>line.id OR asset.warehouse_admission<>'VERIFIED'
                OR (to_jsonb(asset)-ARRAY['warehouse_sku_id','canonical_serial','canonical_mac','base_unit','quantity_base','condition','legal_owner',
                    'origin_document_line_id','warehouse_admission','revision','updated_at']) IS DISTINCT FROM
                    (binding.original_asset-ARRAY['warehouse_sku_id','canonical_serial','canonical_mac','base_unit','quantity_base','condition','legal_owner',
                    'origin_document_line_id','warehouse_admission','revision','updated_at']) THEN
                RAISE EXCEPTION 'opening cannot rewrite original physical identity or legacy history' USING ERRCODE='23514';
            END IF;
        ELSE
            SELECT * INTO lot FROM inventory_lot WHERE tenant_id=NEW.tenant_id AND id=binding.lot_id;
            IF lot.id IS NULL OR lot.origin_document_line_id<>line.id OR lot.received_quantity_base<>line.quantity_base
                OR lot.supplier_id IS NOT NULL OR lot.cost_total_minor IS NOT NULL OR lot.cost_basis_quantity_base IS NOT NULL OR lot.currency IS NOT NULL
                OR lot.received_at IS DISTINCT FROM (SELECT created_at FROM inventory_migration_batch WHERE tenant_id=NEW.tenant_id AND id=NEW.batch_id) THEN
                RAISE EXCEPTION 'opening lot requires cutoff registration without invented purchase or cost' USING ERRCODE='23514';
            END IF;
        END IF;
    END LOOP;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_migration_admission_complete AFTER INSERT ON inventory_migration_admission
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_migration_admission_complete();

DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    anchor:='NEW.kind NOT IN (''DEMAND'',''USAGE'',''ASSET_HANDOVER'')';
    IF strpos(definition,'CASE OLD.kind')=0 OR strpos(definition,anchor)=0 THEN
        RAISE EXCEPTION 'opening document lifecycle extension point missing';
    END IF;
    definition:=replace(definition,'CASE OLD.kind','CASE OLD.kind WHEN ''OPENING_BALANCE'' THEN (OLD.state,NEW.state)=(''DRAFT'',''POSTED'')');
    EXECUTE replace(definition,anchor,'NEW.kind NOT IN (''DEMAND'',''USAGE'',''ASSET_HANDOVER'',''OPENING_BALANCE'')');

    definition:=pg_get_functiondef('warehouse_approval_posting_guard()'::regprocedure);
    anchor:='(approval.business_action=''RECEIPT'' AND NEW.kind=''RECEIVE'')';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'opening approval posting extension point missing'; END IF;
    EXECUTE replace(definition,anchor,anchor || $opening$ OR (approval.business_action='OPENING_BALANCE' AND NEW.kind='OPENING_BALANCE'
        AND EXISTS (SELECT FROM inventory_migration_admission admitted WHERE admitted.tenant_id=NEW.tenant_id
            AND admitted.request_id=NEW.document_id AND admitted.approval_id=approval.id AND admitted.operation_id=NEW.operation_id
            AND admitted.created_xid=pg_current_xact_id()))$opening$);

    SELECT pg_get_expr(conbin,conrelid) INTO STRICT definition FROM pg_constraint
        WHERE conrelid='inventory_outbox'::regclass AND conname='inventory_outbox_event_kind_check';
    ALTER TABLE inventory_outbox DROP CONSTRAINT inventory_outbox_event_kind_check;
    EXECUTE format('ALTER TABLE inventory_outbox ADD CONSTRAINT inventory_outbox_event_kind_check CHECK ((%s) OR event_kind=''OPENING_POSTED'')',definition);
END $$;

CREATE FUNCTION warehouse_migration_approval_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE approval inventory_approval;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
    IF approval.business_action='OPENING_BALANCE' AND approval.status='PENDING'
        AND NOT warehouse_migration_opening_approval_current(approval.tenant_id,approval.source_document_id,approval.id,false) THEN
        RAISE EXCEPTION 'opening approval requires exact current source and every independent unvalued tier' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_migration_approval_snapshot AFTER INSERT OR UPDATE ON inventory_approval
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_migration_approval_snapshot_guard();
