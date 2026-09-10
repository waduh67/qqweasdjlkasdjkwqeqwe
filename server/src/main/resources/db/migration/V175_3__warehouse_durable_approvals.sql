ALTER TABLE inventory_document ADD COLUMN approval_disposition varchar(24)
    CHECK(approval_disposition IS NULL OR approval_disposition='REWORK_REQUIRED');

ALTER TABLE inventory_approval ADD COLUMN evaluation_snapshot text,
    ADD COLUMN source_snapshot text, ADD COLUMN location_ids uuid[],
    ADD COLUMN cutover_epoch bigint CHECK(cutover_epoch>=0),
    ADD COLUMN created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    ADD COLUMN terminal_body text,
    DROP CONSTRAINT inventory_approval_status_ck,
    ADD CONSTRAINT inventory_approval_status_ck CHECK(status IN ('PENDING','APPROVED','REJECTED','EXPIRED','REWORK_REQUIRED','STALE'));

CREATE TABLE inventory_approval_requirement (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), approval_id uuid NOT NULL,
    tier integer NOT NULL CHECK(tier>0), candidates jsonb NOT NULL CHECK(jsonb_array_length(candidates)>0),
    requirement jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,approval_id,tier),
    FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id)
);
CREATE TABLE inventory_approval_command (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), namespace varchar(80) NOT NULL,
    operation_key varchar(240) NOT NULL CHECK(btrim(operation_key)<>''), actor_id uuid NOT NULL,
    approval_id uuid NOT NULL, payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    original_status integer NOT NULL CHECK(original_status IN (200,201,409)), original_body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(tenant_id,id),
    UNIQUE(tenant_id,namespace,operation_key),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id)
);
ALTER TABLE inventory_approval_decision ADD COLUMN evidence_reference uuid,
    ADD FOREIGN KEY(tenant_id,approver_id) REFERENCES app_user(tenant_id,id) NOT VALID;
CREATE UNIQUE INDEX warehouse_durable_decision_tier_uq ON inventory_approval_decision(tenant_id,approval_id,tier) WHERE policy_version_id IS NOT NULL;
CREATE UNIQUE INDEX warehouse_durable_decision_actor_uq ON inventory_approval_decision(tenant_id,approval_id,approver_id) WHERE policy_version_id IS NOT NULL;
ALTER TABLE inventory_approval_effect ADD COLUMN original_body text,
    ADD COLUMN event_id uuid,
    ADD FOREIGN KEY(tenant_id,event_id) REFERENCES inventory_outbox(tenant_id,id);
CREATE UNIQUE INDEX warehouse_approval_effect_source_uq ON inventory_approval_effect(tenant_id,source_document_id,source_document_revision)
    WHERE source_document_id IS NOT NULL;

CREATE FUNCTION warehouse_approval_queue_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.source_document_id IS NULL OR NEW.evaluation_snapshot IS NULL OR NEW.source_snapshot IS NULL
            OR NEW.location_ids IS NULL OR cardinality(NEW.location_ids)=0 OR NEW.cutover_epoch IS NULL
            OR NEW.status<>'PENDING' OR NEW.revision<>0 OR NEW.expires_at<=NEW.requested_at OR NEW.terminal_body IS NOT NULL THEN
            RAISE EXCEPTION 'durable pending approval snapshot required' USING ERRCODE='23514';
        END IF;
    ELSE
        IF OLD.source_document_id IS NULL OR OLD.status<>'PENDING' OR NEW.revision<>OLD.revision+1 OR
            (to_jsonb(NEW)-ARRAY['status','revision','updated_at','terminal_body']) IS DISTINCT FROM
            (to_jsonb(OLD)-ARRAY['status','revision','updated_at','terminal_body']) OR
            ((NEW.status='PENDING')<>(NEW.terminal_body IS NULL)) THEN
            RAISE EXCEPTION 'approval snapshot and terminal outcome are sealed' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_approval_queue BEFORE INSERT OR UPDATE ON inventory_approval FOR EACH ROW EXECUTE FUNCTION warehouse_approval_queue_guard();
CREATE TRIGGER warehouse_approval_no_delete BEFORE DELETE ON inventory_approval FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE FUNCTION warehouse_approval_requirement_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT FROM inventory_approval WHERE tenant_id=NEW.tenant_id AND id=NEW.approval_id AND created_xid=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'approval requirements are sealed' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_approval_requirement_guard BEFORE INSERT ON inventory_approval_requirement FOR EACH ROW EXECUTE FUNCTION warehouse_approval_requirement_guard();

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_approval_requirement','inventory_approval_command'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
END $$;
