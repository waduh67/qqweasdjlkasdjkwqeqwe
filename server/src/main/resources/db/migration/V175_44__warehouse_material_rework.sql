CREATE TABLE inventory_material_rework (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), work_order_id uuid NOT NULL,
    work_order_revision bigint NOT NULL CHECK (work_order_revision>=0), plan_revision bigint NOT NULL CHECK (plan_revision>1),
    previous_plan_id uuid NOT NULL, previous_plan_revision bigint NOT NULL CHECK (previous_plan_revision>0),
    previous_usage_id uuid NOT NULL, previous_usage_revision bigint NOT NULL CHECK (previous_usage_revision>0),
    previous_evidence_revision varchar(64) NOT NULL CHECK (previous_evidence_revision ~ '^[0-9a-f]{64}$'),
    evidence_revision varchar(64) NOT NULL CHECK (evidence_revision ~ '^[0-9a-f]{64}$'),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 1000), actor_id uuid NOT NULL,
    operation_key text NOT NULL, payload_hash varchar(64) NOT NULL, body text NOT NULL, cutover_epoch bigint NOT NULL,
    recorded_at timestamptz NOT NULL, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,operation_key), UNIQUE (tenant_id,previous_plan_id),
    CHECK (plan_revision=previous_plan_revision+1), CHECK (id<>previous_plan_id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_material_plan(tenant_id,id),
    FOREIGN KEY (tenant_id,previous_plan_id) REFERENCES inventory_material_plan(tenant_id,id),
    FOREIGN KEY (tenant_id,previous_usage_id) REFERENCES inventory_usage_snapshot(tenant_id,id),
    FOREIGN KEY (tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY (tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE INDEX inventory_material_rework_order_idx ON inventory_material_rework(tenant_id,work_order_id,plan_revision);
CREATE TABLE inventory_material_rework_inherited_line (
    tenant_id uuid NOT NULL REFERENCES tenant(id), rework_id uuid NOT NULL, plan_line_id uuid NOT NULL, snapshot text NOT NULL,
    PRIMARY KEY (tenant_id,rework_id,plan_line_id),
    FOREIGN KEY (tenant_id,rework_id) REFERENCES inventory_material_rework(tenant_id,id),
    FOREIGN KEY (tenant_id,plan_line_id) REFERENCES inventory_material_plan_line(tenant_id,id)
);
CREATE TABLE inventory_material_rework_evidence (
    tenant_id uuid NOT NULL REFERENCES tenant(id), rework_id uuid NOT NULL, revision_id uuid NOT NULL,
    kind varchar(32) NOT NULL, source varchar(12) NOT NULL CHECK (source IN ('PHOTO','SIGNATURE')),
    PRIMARY KEY (tenant_id,rework_id,revision_id),
    FOREIGN KEY (tenant_id,rework_id) REFERENCES inventory_material_rework(tenant_id,id),
    FOREIGN KEY (tenant_id,revision_id) REFERENCES evidence_object_registry(tenant_id,revision_id)
);
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_material_rework','inventory_material_rework_inherited_line','inventory_material_rework_evidence'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
END $$;

CREATE FUNCTION warehouse_rework_evidence(target_tenant uuid,target_work_order uuid)
RETURNS TABLE(revision_id uuid,kind text,source text) LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    RETURN QUERY SELECT photo.id,photo.kind::text,'PHOTO'::text FROM wo_evidence photo
        WHERE photo.tenant_id=target_tenant AND photo.work_order_id=target_work_order AND photo.revision_state='COMMITTED' AND photo.purge_state='ACTIVE'
            AND photo.kind IN ('FAT','ODP','DROPCORE','ONT','ONU','OPTICAL_BEFORE','OPTICAL_AFTER','TECHNICIAN_SIGNATURE','CUSTOMER_ACKNOWLEDGEMENT','LOCATION')
    UNION ALL SELECT signature.id,'CUSTOMER_ACKNOWLEDGEMENT'::text,'SIGNATURE'::text FROM (
        SELECT id FROM wo_signature WHERE tenant_id=target_tenant AND work_order_id=target_work_order
            AND revision_state='COMMITTED' AND purge_state='ACTIVE' ORDER BY created_at DESC LIMIT 1) signature;
END $$;

CREATE FUNCTION warehouse_assert_rework_live(target_tenant uuid,target_plan uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE rework inventory_material_rework%ROWTYPE; current_evidence text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO rework FROM inventory_material_rework WHERE tenant_id=target_tenant AND id=target_plan;
    IF NOT FOUND THEN RETURN; END IF;
    SELECT encode(sha256(convert_to(string_agg(revision_id::text,'|' ORDER BY revision_id::text COLLATE "C"),'UTF8')),'hex') INTO current_evidence
        FROM warehouse_rework_evidence(target_tenant,rework.work_order_id);
    IF NOT EXISTS (SELECT FROM work_order WHERE tenant_id=target_tenant AND id=rework.work_order_id
        AND status='IN_PROGRESS' AND approval_status='REJECTED' AND warehouse_revision=rework.work_order_revision
        AND proof_of_work_hash=rework.previous_evidence_revision) OR current_evidence IS DISTINCT FROM rework.evidence_revision OR
        EXISTS (SELECT FROM inventory_material_plan WHERE tenant_id=target_tenant AND work_order_id=rework.work_order_id AND plan_revision>rework.plan_revision) OR
        (SELECT material_state FROM inventory_material_lifecycle WHERE tenant_id=target_tenant AND work_order_id=rework.work_order_id ORDER BY revision DESC LIMIT 1)='CLOSED' THEN
        RAISE EXCEPTION 'rework requires current rejected WO plan and evidence revisions' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_assert_material_rework(target_tenant uuid,target_plan uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE rework inventory_material_rework%ROWTYPE; plan inventory_material_plan%ROWTYPE; previous inventory_material_plan%ROWTYPE;
    expected uuid[]; actual uuid[]; frozen jsonb; entry record;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO STRICT rework FROM inventory_material_rework WHERE tenant_id=target_tenant AND id=target_plan;
    SELECT * INTO STRICT plan FROM inventory_material_plan WHERE tenant_id=target_tenant AND id=rework.id;
    SELECT * INTO STRICT previous FROM inventory_material_plan WHERE tenant_id=target_tenant AND id=rework.previous_plan_id;
    frozen:=rework.body::jsonb;
    PERFORM warehouse_assert_material_submission(target_tenant,plan.id);
    IF plan.work_order_id<>rework.work_order_id OR previous.work_order_id<>rework.work_order_id OR plan.plan_revision<>rework.plan_revision
        OR previous.plan_revision<>rework.previous_plan_revision OR plan.work_order_revision<>rework.work_order_revision
        OR plan.actor_id<>rework.actor_id OR plan.reason IS DISTINCT FROM rework.reason OR plan.state<>'SUBMITTED' OR previous.state<>'SUBMITTED'
        OR plan.material_mode<>'MATERIAL_REQUIRED' OR previous.material_mode<>'MATERIAL_REQUIRED' OR
        NOT EXISTS (SELECT FROM inventory_usage_snapshot WHERE tenant_id=target_tenant AND id=rework.previous_usage_id
            AND work_order_id=rework.work_order_id AND use_revision=rework.previous_usage_revision) OR
        frozen->>'reworkId' IS DISTINCT FROM rework.id::text OR frozen->>'actorId' IS DISTINCT FROM rework.actor_id::text
        OR frozen->>'reason' IS DISTINCT FROM rework.reason OR frozen->>'previousUsageId' IS DISTINCT FROM rework.previous_usage_id::text
        OR frozen->>'previousUsageRevision' IS DISTINCT FROM rework.previous_usage_revision::text
        OR frozen->>'previousEvidenceRevision' IS DISTINCT FROM rework.previous_evidence_revision
        OR frozen->>'evidenceRevision' IS DISTINCT FROM rework.evidence_revision
        OR frozen->'plan' IS DISTINCT FROM (SELECT snapshot::jsonb FROM inventory_material_plan_snapshot WHERE tenant_id=target_tenant AND id=plan.id)
        OR frozen->'previousPlan' IS DISTINCT FROM (SELECT snapshot::jsonb FROM inventory_material_plan_snapshot WHERE tenant_id=target_tenant AND id=previous.id) THEN
        RAISE EXCEPTION 'rework requires exact immutable predecessor plan usage and snapshot' USING ERRCODE='23514';
    END IF;
    SELECT array_agg(id ORDER BY id) INTO expected FROM (SELECT id FROM inventory_material_plan_line WHERE tenant_id=target_tenant AND plan_id=previous.id
        UNION SELECT plan_line_id FROM inventory_material_rework_inherited_line WHERE tenant_id=target_tenant AND rework_id=previous.id) inherited;
    SELECT array_agg(plan_line_id ORDER BY plan_line_id) INTO actual FROM inventory_material_rework_inherited_line WHERE tenant_id=target_tenant AND rework_id=plan.id;
    IF actual IS DISTINCT FROM expected OR cardinality(actual) IS DISTINCT FROM jsonb_array_length(frozen->'inheritedLines') THEN
        RAISE EXCEPTION 'rework must preserve every inherited line once' USING ERRCODE='23514';
    END IF;
    FOR entry IN SELECT inherited.*,line.plan_id FROM inventory_material_rework_inherited_line inherited
        JOIN inventory_material_plan_line line ON line.tenant_id=inherited.tenant_id AND line.id=inherited.plan_line_id
        WHERE inherited.tenant_id=target_tenant AND inherited.rework_id=plan.id LOOP
        IF entry.snapshot::jsonb IS DISTINCT FROM (SELECT element FROM inventory_material_plan_snapshot source,
            LATERAL jsonb_array_elements(source.snapshot::jsonb->'lines') element WHERE source.tenant_id=target_tenant AND source.id=entry.plan_id
                AND element->>'id'=entry.plan_line_id::text) OR NOT (frozen->'inheritedLines' @> jsonb_build_array(entry.snapshot::jsonb)) THEN
            RAISE EXCEPTION 'rework cannot rewrite inherited quantities or source links' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF (SELECT encode(sha256(convert_to(string_agg(revision_id::text,'|' ORDER BY revision_id::text COLLATE "C"),'UTF8')),'hex')
            FROM inventory_material_rework_evidence WHERE tenant_id=target_tenant AND rework_id=plan.id) IS DISTINCT FROM rework.evidence_revision OR
        (SELECT count(*) FROM inventory_material_rework_evidence WHERE tenant_id=target_tenant AND rework_id=plan.id)<>jsonb_array_length(frozen->'evidence') OR
        EXISTS (SELECT FROM inventory_material_rework_evidence evidence WHERE evidence.tenant_id=target_tenant AND evidence.rework_id=plan.id
            AND NOT (frozen->'evidence' @> jsonb_build_array(jsonb_build_object('revisionId',evidence.revision_id,'kind',evidence.kind,'source',evidence.source)))) THEN
        RAISE EXCEPTION 'rework requires exact evidence references' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_material_rework_insert_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_material_rework' THEN
        IF NEW.created_xid<>pg_current_xact_id() OR NOT EXISTS (SELECT FROM work_order WHERE tenant_id=NEW.tenant_id AND id=NEW.work_order_id
            AND status='IN_PROGRESS' AND approval_status='REJECTED' AND warehouse_revision=NEW.work_order_revision
            AND proof_of_work_hash=NEW.previous_evidence_revision) OR
            NEW.previous_usage_revision<>(SELECT max(use_revision) FROM inventory_usage_snapshot WHERE tenant_id=NEW.tenant_id AND work_order_id=NEW.work_order_id) THEN
            RAISE EXCEPTION 'rework requires live rejected WO and latest usage' USING ERRCODE='23514';
        END IF;
    ELSIF NOT EXISTS (SELECT FROM inventory_material_rework WHERE tenant_id=NEW.tenant_id AND id=NEW.rework_id AND created_xid=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'rework children must be sealed in origin transaction' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_material_rework_final_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target uuid; target_order uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_material_plan' THEN
        target:=NEW.id;
        IF NOT EXISTS (SELECT FROM inventory_material_rework WHERE tenant_id=NEW.tenant_id AND id=target) THEN
            IF EXISTS (SELECT FROM inventory_usage_snapshot usage JOIN inventory_material_plan source ON source.tenant_id=usage.tenant_id AND source.id=usage.plan_id
                WHERE usage.tenant_id=NEW.tenant_id AND usage.work_order_id=NEW.work_order_id AND source.plan_revision<NEW.plan_revision) THEN
                RAISE EXCEPTION 'post-use plans require explicit rework lineage' USING ERRCODE='23514';
            END IF;
            RETURN NEW;
        END IF;
    ELSIF TG_TABLE_NAME='inventory_material_rework' THEN target:=NEW.id;
    ELSE target:=(to_jsonb(NEW)->>'rework_id')::uuid;
    END IF;
    PERFORM warehouse_assert_material_rework(NEW.tenant_id,target);
    PERFORM warehouse_assert_rework_live(NEW.tenant_id,target);
    IF EXISTS (SELECT FROM inventory_material_rework_evidence evidence WHERE evidence.tenant_id=NEW.tenant_id AND evidence.rework_id=target
        AND NOT EXISTS (SELECT FROM inventory_material_rework rework,
            LATERAL warehouse_rework_evidence(rework.tenant_id,rework.work_order_id) live WHERE rework.tenant_id=NEW.tenant_id AND rework.id=target
                AND (live.revision_id,live.kind,live.source)=(evidence.revision_id,evidence.kind,evidence.source))) THEN
        RAISE EXCEPTION 'rework evidence must belong to the current owner revision' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_material_rework','inventory_material_rework_inherited_line','inventory_material_rework_evidence'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_material_rework_insert BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_material_rework_insert_guard()',table_name);
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_material_rework_final AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_rework_final_guard()',table_name);
    END LOOP;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_material_rework_plan AFTER INSERT ON inventory_material_plan
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_rework_final_guard();
