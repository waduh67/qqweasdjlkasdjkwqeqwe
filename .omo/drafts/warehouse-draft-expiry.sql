-- UNAPPLIED C7 WORKING SKETCH: not a Flyway migration and not executed proof.
-- Pending service/worker/projection/UI/error/upgrade integration and runtime verification.
-- Retained idle sources keep their original rows, snapshots and command receipts.
-- Only the database creates activity; policy versions apply to new identities.
-- Block old writers before capturing any baseline or installing source triggers.
LOCK TABLE inventory_material_plan,inventory_document,inventory_operation IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE inventory_draft_policy (
    tenant_id uuid NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    version bigint NOT NULL CHECK(version>0),
    ttl_seconds integer NOT NULL CHECK(ttl_seconds BETWEEN 1 AND 31536000),
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,version)
);

DO $$ DECLARE family text; target_table text;
BEGIN
    FOREACH family IN ARRAY ARRAY['document','plan'] LOOP
        target_table:=CASE family WHEN 'document' THEN 'inventory_document' ELSE 'inventory_material_plan' END;
        EXECUTE format('CREATE TABLE inventory_%1$s_draft_activity (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id),
            %1$s_id uuid NOT NULL, source_revision bigint NOT NULL CHECK(source_revision>=0),
            policy_version bigint NOT NULL, observed_at timestamptz NOT NULL,
            activity_at timestamptz NOT NULL, deadline timestamptz NOT NULL,
            provenance text NOT NULL CHECK(provenance IN (''CREATED'',''DRAFT_SAVE'',''LEGACY_BASELINE'')),
            UNIQUE(tenant_id,id,%1$s_id), UNIQUE(tenant_id,%1$s_id,source_revision),
            FOREIGN KEY(tenant_id,%1$s_id) REFERENCES %2$I(tenant_id,id),
            FOREIGN KEY(tenant_id,policy_version) REFERENCES inventory_draft_policy(tenant_id,version),
            CHECK(isfinite(observed_at) AND isfinite(activity_at) AND isfinite(deadline)),
            CHECK(activity_at<=observed_at AND deadline>activity_at)
        )',family,target_table);
        EXECUTE format('CREATE TABLE inventory_%1$s_draft_expiry (
            tenant_id uuid NOT NULL REFERENCES tenant(id), %1$s_id uuid NOT NULL,
            activity_id uuid NOT NULL, source_revision bigint NOT NULL CHECK(source_revision>=0),
            expired_at timestamptz NOT NULL CHECK(isfinite(expired_at)),
            reason text NOT NULL CHECK(reason=''IDLE_DEADLINE''),
            PRIMARY KEY(tenant_id,%1$s_id),
            FOREIGN KEY(tenant_id,%1$s_id) REFERENCES %2$I(tenant_id,id),
            FOREIGN KEY(tenant_id,activity_id,%1$s_id)
                REFERENCES inventory_%1$s_draft_activity(tenant_id,id,%1$s_id)
        )',family,target_table);
    END LOOP;
END $$;

ALTER TABLE inventory_document_draft_activity ADD COLUMN operation_id uuid, ADD COLUMN source_snapshot jsonb,
    ADD FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    ADD CHECK((provenance='DRAFT_SAVE')=(operation_id IS NOT NULL));

-- New receipt saves retain their canonical request separately from the existing
-- intake identity, whose format and historic replay contract remain unchanged.
CREATE TABLE inventory_receipt_draft_command (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    document_id uuid NOT NULL, canonical_request text NOT NULL,
    UNIQUE(tenant_id,id),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY(tenant_id,document_id) REFERENCES inventory_document(tenant_id,id)
);
ALTER TABLE inventory_receipt_draft_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_receipt_draft_command FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_receipt_draft_command
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_receipt_draft_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
REVOKE ALL ON TABLE inventory_receipt_draft_command FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE ALL ON TABLE inventory_receipt_draft_command FROM warehouse_app;
        GRANT SELECT,INSERT ON TABLE inventory_receipt_draft_command TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_draft_policy_capture() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(NEW.tenant_id);
    NEW.recorded_at:=clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_draft_policy_capture BEFORE INSERT ON inventory_draft_policy
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_policy_capture();
CREATE TRIGGER warehouse_draft_policy_immutable BEFORE UPDATE ON inventory_draft_policy
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE TRIGGER warehouse_draft_policy_tenant_cleanup BEFORE DELETE ON inventory_draft_policy
    FOR EACH ROW EXECUTE FUNCTION warehouse_tenant_control_delete_guard();

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_draft_policy','inventory_document_draft_activity',
        'inventory_plan_draft_activity','inventory_document_draft_expiry','inventory_plan_draft_expiry'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I
            USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        IF table_name<>'inventory_draft_policy' THEN
            EXECUTE format('CREATE TRIGGER warehouse_draft_immutable BEFORE UPDATE OR DELETE ON %I
                FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        END IF;
        EXECUTE format('REVOKE ALL ON TABLE %I FROM PUBLIC',table_name);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            -- Includes TRUNCATE and the DML privileges inherited from owner defaults.
            EXECUTE format('REVOKE ALL ON TABLE %I FROM warehouse_app',table_name);
            EXECUTE format('GRANT SELECT ON TABLE %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
END $$;

CREATE FUNCTION warehouse_has_draft_clock(kind text) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE SET search_path=pg_catalog,public AS $$
    SELECT kind IN ('RECEIPT','TRANSFER','COUNT','LOSS','SCRAP','DISPOSITION_REVERSAL',
        'ASSET_LOSS','TITLE_CORRECTION','RETURN_TITLE','ADJUSTMENT','OPENING_BALANCE')
$$;

CREATE FUNCTION warehouse_draft_policy_current(scope uuid) RETURNS bigint
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE result bigint;
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(scope);
    SELECT max(version) INTO result FROM public.inventory_draft_policy WHERE tenant_id=scope;
    IF result IS NULL THEN
        INSERT INTO public.inventory_draft_policy(tenant_id,version,ttl_seconds) VALUES(scope,1,604800)
            ON CONFLICT(tenant_id,version) DO NOTHING;
        SELECT max(version) INTO STRICT result FROM public.inventory_draft_policy WHERE tenant_id=scope;
    END IF;
    RETURN result;
END $$;
REVOKE ALL ON FUNCTION warehouse_draft_policy_current(uuid) FROM PUBLIC;

-- These projections deliberately fail if an eligible retained source lacks activity.
-- They neither create activity nor change an original source/replay body.
CREATE FUNCTION warehouse_document_draft_expired_at(scope uuid,target uuid) RETURNS timestamptz
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
DECLARE document public.inventory_document; result timestamptz; due_at timestamptz;
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(scope);
    SELECT activity.deadline INTO result FROM public.inventory_document_draft_expiry expiry
        JOIN public.inventory_document_draft_activity activity ON activity.tenant_id=expiry.tenant_id AND activity.id=expiry.activity_id
        WHERE expiry.tenant_id=scope AND expiry.document_id=target;
    IF result IS NOT NULL THEN RETURN result; END IF;
    SELECT * INTO document FROM public.inventory_document WHERE tenant_id=scope AND id=target;
    IF document.id IS NULL OR document.state<>'DRAFT' OR NOT public.warehouse_has_draft_clock(document.kind) THEN RETURN NULL; END IF;
    PERFORM public.warehouse_assert_idle_source_effects(scope,target);
    SELECT deadline INTO due_at FROM public.inventory_document_draft_activity
        WHERE tenant_id=scope AND document_id=target ORDER BY source_revision DESC LIMIT 1;
    IF due_at IS NULL THEN RAISE EXCEPTION 'retained document has no trusted draft activity'
        USING ERRCODE='23514',CONSTRAINT='warehouse_draft_activity_missing_ck'; END IF;
    RETURN CASE WHEN due_at<=clock_timestamp() THEN due_at ELSE NULL END;
END $$;

CREATE FUNCTION warehouse_plan_draft_expired_at(scope uuid,target uuid) RETURNS timestamptz
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
DECLARE plan public.inventory_material_plan; result timestamptz; due_at timestamptz;
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(scope);
    SELECT activity.deadline INTO result FROM public.inventory_plan_draft_expiry expiry
        JOIN public.inventory_plan_draft_activity activity ON activity.tenant_id=expiry.tenant_id AND activity.id=expiry.activity_id
        WHERE expiry.tenant_id=scope AND expiry.plan_id=target;
    IF result IS NOT NULL THEN RETURN result; END IF;
    SELECT * INTO plan FROM public.inventory_material_plan WHERE tenant_id=scope AND id=target;
    IF plan.id IS NULL OR plan.state<>'DRAFT' THEN RETURN NULL; END IF;
    SELECT deadline INTO due_at FROM public.inventory_plan_draft_activity
        WHERE tenant_id=scope AND plan_id=target ORDER BY source_revision DESC LIMIT 1;
    IF due_at IS NULL THEN RAISE EXCEPTION 'retained plan has no trusted draft activity'
        USING ERRCODE='23514',CONSTRAINT='warehouse_draft_activity_missing_ck'; END IF;
    RETURN CASE WHEN due_at<=clock_timestamp() THEN due_at ELSE NULL END;
END $$;

CREATE FUNCTION warehouse_document_current_state(scope uuid,target uuid,stored_state text) RETURNS text
LANGUAGE sql VOLATILE SET search_path=pg_catalog,public AS $$
    SELECT CASE WHEN public.warehouse_document_draft_expired_at(scope,target) IS NOT NULL THEN 'EXPIRED' ELSE stored_state END
$$;
CREATE FUNCTION warehouse_plan_current_state(scope uuid,target uuid,stored_state text) RETURNS text
LANGUAGE sql VOLATILE SET search_path=pg_catalog,public AS $$
    SELECT CASE WHEN public.warehouse_plan_draft_expired_at(scope,target) IS NOT NULL THEN 'EXPIRED' ELSE stored_state END
$$;

CREATE FUNCTION warehouse_assert_document_draft_live(scope uuid,target uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(scope);
    PERFORM 1 FROM public.inventory_document WHERE tenant_id=scope AND id=target FOR UPDATE;
    IF public.warehouse_document_draft_expired_at(scope,target) IS NOT NULL THEN
        RAISE EXCEPTION 'draft idle deadline has expired' USING ERRCODE='23514',CONSTRAINT='warehouse_draft_expired_ck';
    END IF;
END $$;
CREATE FUNCTION warehouse_assert_plan_draft_live(scope uuid,target uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(scope);
    PERFORM 1 FROM public.inventory_material_plan WHERE tenant_id=scope AND id=target FOR UPDATE;
    IF public.warehouse_plan_draft_expired_at(scope,target) IS NOT NULL THEN
        RAISE EXCEPTION 'draft idle deadline has expired' USING ERRCODE='23514',CONSTRAINT='warehouse_draft_expired_ck';
    END IF;
END $$;

CREATE FUNCTION warehouse_draft_created() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE policy bigint; ttl integer; observed timestamptz;
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.state<>'DRAFT' THEN RETURN NEW; END IF;
    IF TG_TABLE_NAME='inventory_document' THEN
        IF NOT public.warehouse_has_draft_clock(NEW.kind) THEN RETURN NEW; END IF;
    END IF;
    policy:=public.warehouse_draft_policy_current(NEW.tenant_id);
    SELECT ttl_seconds INTO STRICT ttl FROM public.inventory_draft_policy WHERE tenant_id=NEW.tenant_id AND version=policy;
    observed:=clock_timestamp();
    IF TG_TABLE_NAME='inventory_document' THEN
        INSERT INTO public.inventory_document_draft_activity(tenant_id,document_id,source_revision,policy_version,observed_at,activity_at,deadline,provenance)
            VALUES(NEW.tenant_id,NEW.id,NEW.revision,policy,observed,observed,observed+make_interval(secs=>ttl),'CREATED');
    ELSE
        INSERT INTO public.inventory_plan_draft_activity(tenant_id,plan_id,source_revision,policy_version,observed_at,activity_at,deadline,provenance)
            VALUES(NEW.tenant_id,NEW.id,NEW.plan_revision,policy,observed,observed,observed+make_interval(secs=>ttl),'CREATED');
    END IF;
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION warehouse_draft_created() FROM PUBLIC;

-- A successful command receipt, rather than a header touch, renews a mutable draft.
-- Existing deferred command/snapshot bindings must still succeed at commit.
CREATE FUNCTION warehouse_draft_saved() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE document public.inventory_document; previous public.inventory_document_draft_activity; ttl integer; observed timestamptz;
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.namespace NOT IN ('warehouse.receipt.update','warehouse.transfer.update','warehouse.count.update') THEN RETURN NEW; END IF;
    PERFORM public.warehouse_assert_document_draft_live(NEW.tenant_id,NEW.document_id);
    SELECT * INTO STRICT document FROM public.inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id;
    SELECT * INTO previous FROM public.inventory_document_draft_activity WHERE tenant_id=NEW.tenant_id AND document_id=NEW.document_id
        ORDER BY source_revision DESC LIMIT 1;
    IF document.state<>'DRAFT' OR document.kind NOT IN ('RECEIPT','TRANSFER','COUNT') OR
        NEW.namespace<>'warehouse.'||lower(document.kind)||'.update' OR
        (document.kind IN ('TRANSFER','COUNT') AND NEW.actor_id<>document.actor_id) OR
        NEW.document_revision<>document.revision OR NEW.document_revision<=previous.source_revision OR
        NEW.original_status<>200 OR NEW.original_body::jsonb->>'state' IS DISTINCT FROM 'DRAFT' OR
        EXISTS(SELECT FROM public.inventory_repair_replacement_request WHERE tenant_id=NEW.tenant_id AND receipt_id=NEW.document_id) THEN
        RAISE EXCEPTION 'activity requires an accepted editable draft command' USING ERRCODE='23514';
    END IF;
    SELECT ttl_seconds INTO STRICT ttl FROM public.inventory_draft_policy WHERE tenant_id=NEW.tenant_id AND version=previous.policy_version;
    observed:=clock_timestamp();
    IF observed>=previous.deadline THEN RAISE EXCEPTION 'draft idle deadline has expired'
        USING ERRCODE='23514',CONSTRAINT='warehouse_draft_expired_ck'; END IF;
    INSERT INTO public.inventory_document_draft_activity(tenant_id,document_id,source_revision,policy_version,observed_at,activity_at,deadline,provenance,operation_id,source_snapshot)
        VALUES(NEW.tenant_id,NEW.document_id,NEW.document_revision,previous.policy_version,observed,observed,observed+make_interval(secs=>ttl),'DRAFT_SAVE',NEW.id,
            CASE WHEN document.kind='RECEIPT' THEN jsonb_build_object('document',to_jsonb(document),
                'intake',(SELECT to_jsonb(intake) FROM public.inventory_receipt_intake intake WHERE tenant_id=NEW.tenant_id AND id=document.id),
                'lines',(SELECT jsonb_agg(to_jsonb(line) ORDER BY line.line_number) FROM public.inventory_document_line line
                    WHERE line.tenant_id=NEW.tenant_id AND line.document_id=document.id)) END);
    RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION warehouse_draft_saved() FROM PUBLIC;

CREATE FUNCTION warehouse_receipt_draft_save_seal() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
DECLARE operation public.inventory_operation;
    document public.inventory_document; intake jsonb; canonical text; request jsonb; body jsonb;
    expected_lines jsonb; expected_body jsonb; input_line jsonb; expanded jsonb; line_count integer;
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.provenance<>'DRAFT_SAVE' THEN RETURN NEW; END IF;
    SELECT * INTO STRICT operation FROM public.inventory_operation WHERE tenant_id=NEW.tenant_id AND id=NEW.operation_id;
    IF operation.namespace<>'warehouse.receipt.update' THEN RETURN NEW; END IF;
    SELECT * INTO document FROM jsonb_populate_record(NULL::public.inventory_document,NEW.source_snapshot->'document');
    SELECT canonical_request INTO canonical FROM public.inventory_receipt_draft_command
        WHERE tenant_id=NEW.tenant_id AND id=operation.id AND document_id=document.id;
    SELECT canonical_payload::jsonb INTO intake FROM public.inventory_command_identity WHERE tenant_id=NEW.tenant_id AND id=operation.id;
    body:=operation.original_body::jsonb;
    request:=canonical::jsonb->'input';
    IF canonical IS NULL OR intake IS NULL OR request IS NULL OR document.id IS DISTINCT FROM NEW.document_id OR
        document.tenant_id IS DISTINCT FROM NEW.tenant_id OR document.kind IS DISTINCT FROM 'RECEIPT' OR document.state IS DISTINCT FROM 'DRAFT' OR
        operation.namespace<>'warehouse.receipt.update' OR operation.business_action<>'UPDATE' OR
        operation.document_id<>document.id OR operation.resource_id<>document.id OR
        operation.resource_scope<>'receipt:'||document.id OR operation.document_revision<>NEW.source_revision OR operation.original_status<>200 OR
        operation.payload_hash<>encode(sha256(convert_to(canonical,'UTF8')),'hex') OR
        canonical::jsonb->>'id' IS DISTINCT FROM document.id::text OR
        (request->>'expectedRevision')::bigint IS DISTINCT FROM NEW.source_revision-1 OR
        request->>'supplierId' IS DISTINCT FROM intake#>>'{supplier,id}' OR
        request->>'sourceLocationId' IS DISTINCT FROM intake#>>'{source,id}' OR
        request->>'inspectionLocationId' IS DISTINCT FROM intake#>>'{inspection,id}' OR
        request->>'externalReference' IS DISTINCT FROM intake->>'externalReference' OR
        jsonb_typeof(request->'lines') IS DISTINCT FROM 'array' OR
        jsonb_array_length(request->'lines') NOT BETWEEN 1 AND 100 OR
        jsonb_typeof(intake->'lines') IS DISTINCT FROM 'array' OR jsonb_array_length(intake->'lines') NOT BETWEEN 1 AND 500 THEN
        RAISE EXCEPTION 'receipt draft save requires canonical command and intake binding' USING ERRCODE='23514';
    END IF;
    FOR input_line,line_count IN SELECT value,ordinality::integer FROM jsonb_array_elements(request->'lines') WITH ORDINALITY LOOP
        SELECT jsonb_agg(value ORDER BY ordinality) INTO expanded FROM jsonb_array_elements(intake->'lines') WITH ORDINALITY
            WHERE (value->>'inputLineNumber')::integer=line_count;
        IF expanded IS NULL OR
            (SELECT sum((value->>'quantityBase')::numeric) FROM jsonb_array_elements(expanded)) IS DISTINCT FROM (input_line->>'quantityBase')::numeric OR
            EXISTS(SELECT FROM jsonb_array_elements(expanded) item WHERE
                item#>>'{sku,id}' IS DISTINCT FROM input_line->>'skuId' OR
                item->'conversion' IS DISTINCT FROM input_line->'conversion' OR item->'lotCode' IS DISTINCT FROM input_line->'lotCode' OR
                (item->'cost'='null'::jsonb) IS DISTINCT FROM (input_line->'cost'='null'::jsonb) OR
                (item->'cost'<>'null'::jsonb AND (
                    (item#>>'{cost,totalMinor}')::numeric IS DISTINCT FROM (input_line#>>'{cost,totalMinor}')::numeric OR
                    item#>>'{cost,currency}' IS DISTINCT FROM input_line#>>'{cost,currency}' OR
                    (item#>>'{cost,costBasisQuantityBase}')::numeric IS DISTINCT FROM (input_line->>'quantityBase')::numeric))) OR
            coalesce((SELECT jsonb_agg(jsonb_build_object('serial',value->'serial','mac',value->'mac') ORDER BY ordinality)
                FROM jsonb_array_elements(expanded) WITH ORDINALITY WHERE value#>>'{sku,tracking}'='SERIAL'),'[]'::jsonb)
                IS DISTINCT FROM input_line->'serials' THEN
            RAISE EXCEPTION 'receipt intake does not match the saved request lines' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF EXISTS(SELECT FROM jsonb_array_elements(intake->'lines') item
        WHERE (item->>'inputLineNumber')::integer NOT BETWEEN 1 AND jsonb_array_length(request->'lines')) THEN
        RAISE EXCEPTION 'receipt intake has an extra input line' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_agg(jsonb_build_object('id',item->'id','inputLineNumber',item->'inputLineNumber','skuId',item#>'{sku,id}',
        'skuCode',item#>'{sku,code}','skuName',item#>'{sku,name}','tracking',item#>'{sku,tracking}','baseUnit',item#>'{sku,baseUnit}',
        'quantityBase',item->'quantityBase','serial',item->'serial','mac',item->'mac','lotCode',item->'lotCode',
        'inspectionRequired',item#>'{sku,inspectionRequired}','conversion',item->'conversion','cost',NULL,
        'pieces','[]'::jsonb,'acceptedBase','0','rejectedBase','0','putawayBase','0') ORDER BY ordinality)
        INTO expected_lines FROM jsonb_array_elements(intake->'lines') WITH ORDINALITY AS expanded(item,ordinality);
    expected_body:=jsonb_build_object('id',document.id,'revision',operation.document_revision,'state','DRAFT',
        'supplierId',intake#>'{supplier,id}','supplierName',intake#>'{supplier,name}','externalReference',intake->'externalReference',
        'sourceLocationId',intake#>'{source,id}','inspectionLocationId',intake#>'{inspection,id}',
        'sourceLocationName',coalesce(intake#>>'{source,name}',intake#>>'{source,code}'),
        'inspectionLocationName',coalesce(intake#>>'{inspection,name}',intake#>>'{inspection,code}'),
        'lines',expected_lines,'inspections','[]'::jsonb,'costVisible',false);
    IF body-'createdAt' IS DISTINCT FROM expected_body OR (body->>'createdAt')::timestamptz IS DISTINCT FROM document.created_at THEN
        RAISE EXCEPTION 'receipt draft response is not the exact redacted intake view' USING ERRCODE='23514';
    END IF;
    -- Compare the database-captured save boundary, even if another control update
    -- later advances the header in this transaction. No final-revision bypass.
        IF document.revision IS DISTINCT FROM operation.document_revision OR
            document.supplier_id::text IS DISTINCT FROM intake#>>'{supplier,id}' OR
            document.source_reference IS DISTINCT FROM intake->>'externalReference' OR
            (NEW.source_snapshot#>>'{intake,snapshot}')::jsonb IS DISTINCT FROM intake OR
            NEW.source_snapshot#>>'{intake,source_location_id}' IS DISTINCT FROM intake#>>'{source,id}' OR
            NEW.source_snapshot#>>'{intake,inspection_location_id}' IS DISTINCT FROM intake#>>'{inspection,id}' OR
            jsonb_array_length(NEW.source_snapshot->'lines') IS DISTINCT FROM jsonb_array_length(intake->'lines') OR
            EXISTS(SELECT FROM jsonb_array_elements(intake->'lines') WITH ORDINALITY AS expanded(item,ordinality)
                LEFT JOIN jsonb_populate_recordset(NULL::public.inventory_document_line,NEW.source_snapshot->'lines') line
                    ON line.tenant_id=NEW.tenant_id AND line.id=(item->>'id')::uuid
                WHERE line.id IS NULL OR line.document_id<>document.id OR line.document_revision<>operation.document_revision OR
                    line.line_number<>ordinality OR line.sku_id::text IS DISTINCT FROM item#>>'{sku,id}' OR
                    line.base_unit IS DISTINCT FROM item#>>'{sku,baseUnit}' OR line.tracking IS DISTINCT FROM item#>>'{sku,tracking}' OR
                    line.quantity_base::numeric IS DISTINCT FROM (item->>'quantityBase')::numeric OR
                    line.stock_identity_id IS NOT NULL OR line.lot_id IS NOT NULL OR line.source_line_id IS NOT NULL OR
                    line.accepted_base<>0 OR line.rejected_base<>0 OR line.missing_base<>0 OR line.continuous_cut IS DISTINCT FROM true OR
                    line.inspection_required_snapshot IS DISTINCT FROM (item#>>'{sku,inspectionRequired}')::boolean OR
                    line.location_id::text IS DISTINCT FROM intake#>>'{inspection,id}' OR line.destination_location_id::text IS DISTINCT FROM intake#>>'{source,id}' OR
                    line.custodian_id::text IS DISTINCT FROM intake#>>'{inspection,id}' OR line.custodian_kind IS DISTINCT FROM 'WAREHOUSE' OR
                    line.condition IS DISTINCT FROM 'QUARANTINE' OR line.legal_owner IS DISTINCT FROM 'ISP' OR
                    line.conversion_numerator::numeric IS DISTINCT FROM CASE WHEN line.tracking<>'SERIAL' THEN (item#>>'{conversion,numerator}')::numeric END OR
                    line.conversion_denominator::numeric IS DISTINCT FROM CASE WHEN line.tracking<>'SERIAL' THEN (item#>>'{conversion,denominator}')::numeric END OR
                    line.package_quantity::numeric IS DISTINCT FROM CASE WHEN line.tracking<>'SERIAL' THEN (item#>>'{conversion,packageQuantity}')::numeric END OR
                    line.cost_total_minor::numeric IS DISTINCT FROM (item#>>'{cost,totalMinor}')::numeric OR
                    line.cost_basis_quantity_base::numeric IS DISTINCT FROM (item#>>'{cost,costBasisQuantityBase}')::numeric OR
                    line.currency IS DISTINCT FROM item#>>'{cost,currency}') THEN
            RAISE EXCEPTION 'receipt draft save does not match its current header and lines' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

-- Backfill is an explicitly inferred baseline, not fabricated historic activity.
-- Every deadline is bounded to [migration time, migration time + pinned TTL].
DO $$ DECLARE baseline timestamptz:=clock_timestamp(); original_scope text:=current_setting('app.tenant_id',true);
    scope uuid; policy bigint; ttl integer;
BEGIN
    FOR scope IN SELECT DISTINCT tenant_id FROM inventory_document WHERE state='DRAFT' AND warehouse_has_draft_clock(kind)
        UNION SELECT DISTINCT tenant_id FROM inventory_material_plan WHERE state='DRAFT' LOOP
        PERFORM set_config('app.tenant_id',scope::text,true);
        IF EXISTS(SELECT FROM inventory_document document WHERE tenant_id=scope AND state='DRAFT' AND warehouse_has_draft_clock(kind) AND
            (EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=document.id AND state='APPLIED') OR
             EXISTS(SELECT FROM inventory_approval_effect WHERE tenant_id=scope AND source_document_id=document.id))) THEN
            RAISE EXCEPTION 'effected legacy draft requires integrity remediation before expiry migration' USING ERRCODE='23514';
        END IF;
        policy:=warehouse_draft_policy_current(scope);
        SELECT ttl_seconds INTO STRICT ttl FROM inventory_draft_policy WHERE tenant_id=scope AND version=policy;
        INSERT INTO inventory_document_draft_activity(tenant_id,document_id,source_revision,policy_version,observed_at,activity_at,deadline,provenance)
            SELECT scope,id,revision,policy,baseline,activity,activity+make_interval(secs=>ttl),'LEGACY_BASELINE'
            FROM (SELECT document.*,CASE WHEN isfinite(coalesce(updated_at,created_at))
                THEN greatest(baseline-make_interval(secs=>ttl),least(baseline,coalesce(updated_at,created_at)))
                ELSE baseline-make_interval(secs=>ttl) END activity FROM inventory_document document
                WHERE tenant_id=scope AND state='DRAFT' AND warehouse_has_draft_clock(kind)) candidate;
        INSERT INTO inventory_plan_draft_activity(tenant_id,plan_id,source_revision,policy_version,observed_at,activity_at,deadline,provenance)
            SELECT scope,id,plan_revision,policy,baseline,activity,activity+make_interval(secs=>ttl),'LEGACY_BASELINE'
            FROM (SELECT plan.*,CASE WHEN isfinite(coalesce(updated_at,created_at))
                THEN greatest(baseline-make_interval(secs=>ttl),least(baseline,coalesce(updated_at,created_at)))
                ELSE baseline-make_interval(secs=>ttl) END activity FROM inventory_material_plan plan
                WHERE tenant_id=scope AND state='DRAFT') candidate;
    END LOOP;
    PERFORM set_config('app.tenant_id',coalesce(original_scope,''),true);
END $$;

CREATE TRIGGER warehouse_draft_created AFTER INSERT ON inventory_document
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_created();
CREATE TRIGGER warehouse_draft_created AFTER INSERT ON inventory_material_plan
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_created();
CREATE TRIGGER warehouse_draft_saved AFTER INSERT ON inventory_operation
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_saved();
CREATE CONSTRAINT TRIGGER warehouse_receipt_draft_save_seal AFTER INSERT ON inventory_document_draft_activity
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_receipt_draft_save_seal();

CREATE FUNCTION warehouse_draft_document_write_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        PERFORM public.warehouse_assert_document_draft_live(OLD.tenant_id,(to_jsonb(OLD)->>TG_ARGV[0])::uuid);
    END IF;
    IF TG_OP<>'DELETE' THEN
        PERFORM public.warehouse_assert_document_draft_live(NEW.tenant_id,(to_jsonb(NEW)->>TG_ARGV[0])::uuid);
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE FUNCTION warehouse_draft_plan_write_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN
        PERFORM public.warehouse_assert_plan_draft_live(OLD.tenant_id,(to_jsonb(OLD)->>TG_ARGV[0])::uuid);
    END IF;
    IF TG_OP<>'DELETE' THEN
        PERFORM public.warehouse_assert_plan_draft_live(NEW.tenant_id,(to_jsonb(NEW)->>TG_ARGV[0])::uuid);
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;

CREATE TRIGGER warehouse_aaa_draft_live BEFORE UPDATE OR DELETE ON inventory_document
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_document_write_guard('id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT OR UPDATE OR DELETE ON inventory_document_line
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_document_write_guard('document_id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT OR UPDATE OR DELETE ON inventory_receipt_intake
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_document_write_guard('id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT ON inventory_receipt_evidence
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_document_write_guard('document_id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT OR UPDATE OR DELETE ON inventory_count_scope
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_document_write_guard('id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT OR UPDATE OR DELETE ON inventory_count_entry
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_document_write_guard('document_id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE UPDATE OR DELETE ON inventory_material_plan
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_plan_write_guard('id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT OR UPDATE OR DELETE ON inventory_material_plan_line
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_plan_write_guard('plan_id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT ON inventory_material_submission
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_plan_write_guard('id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT ON inventory_approval
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_document_write_guard('source_document_id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT ON inventory_movement
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_document_write_guard('document_id');
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT ON inventory_approval_effect
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_document_write_guard('source_document_id');

-- Structural inverse only: wall time must never invalidate admitted history.
CREATE FUNCTION warehouse_assert_idle_source_effects(scope uuid,target uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
DECLARE document public.inventory_document;
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(scope);
    SELECT * INTO document FROM public.inventory_document WHERE tenant_id=scope AND id=target;
    IF document.state='DRAFT' AND public.warehouse_has_draft_clock(document.kind) AND
        (EXISTS(SELECT FROM public.inventory_movement WHERE tenant_id=scope AND document_id=target AND state='APPLIED') OR
         EXISTS(SELECT FROM public.inventory_approval_effect WHERE tenant_id=scope AND source_document_id=target)) THEN
        RAISE EXCEPTION 'an effected document cannot remain an idle draft' USING ERRCODE='23514';
    END IF;
END $$;
CREATE FUNCTION warehouse_idle_source_effect_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
BEGIN
    IF TG_OP<>'INSERT' THEN PERFORM public.warehouse_assert_deferred_scope(OLD.tenant_id); END IF;
    IF TG_OP<>'DELETE' THEN PERFORM public.warehouse_assert_deferred_scope(NEW.tenant_id); END IF;
    IF TG_OP<>'INSERT' THEN PERFORM public.warehouse_assert_idle_source_effects(OLD.tenant_id,(to_jsonb(OLD)->>TG_ARGV[0])::uuid); END IF;
    IF TG_OP<>'DELETE' THEN PERFORM public.warehouse_assert_idle_source_effects(NEW.tenant_id,(to_jsonb(NEW)->>TG_ARGV[0])::uuid); END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_idle_source_effect AFTER INSERT OR UPDATE ON inventory_document
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_idle_source_effect_guard('id');
CREATE CONSTRAINT TRIGGER warehouse_idle_source_effect AFTER INSERT OR UPDATE ON inventory_movement
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_idle_source_effect_guard('document_id');
CREATE CONSTRAINT TRIGGER warehouse_idle_source_effect AFTER INSERT ON inventory_approval_effect
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_idle_source_effect_guard('source_document_id');

-- This BEFORE trigger sorts before decision_binding, acquiring source before approval.
-- It is intentionally absent from deferred historical validators and status updates.
CREATE FUNCTION warehouse_draft_decision_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
DECLARE target uuid;
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT source_document_id INTO target FROM public.inventory_approval WHERE tenant_id=NEW.tenant_id AND id=NEW.approval_id;
    IF target IS NOT NULL THEN PERFORM public.warehouse_assert_document_draft_live(NEW.tenant_id,target); END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_aaa_draft_live BEFORE INSERT ON inventory_approval_decision
    FOR EACH ROW EXECUTE FUNCTION warehouse_draft_decision_guard();

CREATE FUNCTION warehouse_draft_expiry_final_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog,public AS $$
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(NEW.tenant_id);
    PERFORM public.warehouse_assert_idle_source_effects(NEW.tenant_id,NEW.document_id);
    IF EXISTS(SELECT FROM public.inventory_approval WHERE tenant_id=NEW.tenant_id AND source_document_id=NEW.document_id AND status='PENDING') THEN
        RAISE EXCEPTION 'draft expiry must atomically terminate pending approval' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_draft_expiry_final AFTER INSERT ON inventory_document_draft_expiry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_draft_expiry_final_guard();

CREATE FUNCTION warehouse_expire_document_draft(scope uuid,target uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE document public.inventory_document; activity public.inventory_document_draft_activity; observed timestamptz;
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(scope);
    SELECT * INTO document FROM public.inventory_document WHERE tenant_id=scope AND id=target FOR UPDATE;
    IF document.id IS NULL OR document.state<>'DRAFT' OR NOT public.warehouse_has_draft_clock(document.kind) OR
        EXISTS(SELECT FROM public.inventory_document_draft_expiry WHERE tenant_id=scope AND document_id=target) THEN RETURN false; END IF;
    PERFORM public.warehouse_assert_idle_source_effects(scope,target);
    SELECT * INTO activity FROM public.inventory_document_draft_activity WHERE tenant_id=scope AND document_id=target
        ORDER BY source_revision DESC LIMIT 1;
    IF activity.id IS NULL THEN RAISE EXCEPTION 'retained document has no trusted draft activity'
        USING ERRCODE='23514',CONSTRAINT='warehouse_draft_activity_missing_ck'; END IF;
    observed:=clock_timestamp();
    IF observed<activity.deadline THEN RETURN false; END IF;
    INSERT INTO public.inventory_document_draft_expiry(tenant_id,document_id,activity_id,source_revision,expired_at,reason)
        VALUES(scope,target,activity.id,document.revision,observed,'IDLE_DEADLINE');
    RETURN true;
END $$;
CREATE FUNCTION warehouse_expire_plan_draft(scope uuid,target uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE plan public.inventory_material_plan; activity public.inventory_plan_draft_activity; observed timestamptz;
BEGIN
    PERFORM public.warehouse_assert_deferred_scope(scope);
    SELECT * INTO plan FROM public.inventory_material_plan WHERE tenant_id=scope AND id=target FOR UPDATE;
    IF plan.id IS NULL OR plan.state<>'DRAFT' OR
        EXISTS(SELECT FROM public.inventory_plan_draft_expiry WHERE tenant_id=scope AND plan_id=target) THEN RETURN false; END IF;
    SELECT * INTO activity FROM public.inventory_plan_draft_activity WHERE tenant_id=scope AND plan_id=target
        ORDER BY source_revision DESC LIMIT 1;
    IF activity.id IS NULL THEN RAISE EXCEPTION 'retained plan has no trusted draft activity'
        USING ERRCODE='23514',CONSTRAINT='warehouse_draft_activity_missing_ck'; END IF;
    observed:=clock_timestamp();
    IF observed<activity.deadline THEN RETURN false; END IF;
    INSERT INTO public.inventory_plan_draft_expiry(tenant_id,plan_id,activity_id,source_revision,expired_at,reason)
        VALUES(scope,target,activity.id,plan.plan_revision,observed,'IDLE_DEADLINE');
    RETURN true;
END $$;
REVOKE ALL ON FUNCTION warehouse_expire_document_draft(uuid,uuid),warehouse_expire_plan_draft(uuid,uuid) FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT EXECUTE ON FUNCTION warehouse_expire_document_draft(uuid,uuid),warehouse_expire_plan_draft(uuid,uuid) TO warehouse_app;
    END IF;
END $$;
