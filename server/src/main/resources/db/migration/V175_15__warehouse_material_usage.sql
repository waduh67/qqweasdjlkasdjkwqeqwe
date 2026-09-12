ALTER TABLE inventory_customer_material_fact ALTER COLUMN customer_id DROP NOT NULL;
ALTER TABLE inventory_customer_material_fact ADD COLUMN usage_id uuid;
ALTER TABLE inventory_usage_snapshot ADD COLUMN material_mode varchar(20) NOT NULL DEFAULT 'MATERIAL_REQUIRED'
    CHECK (material_mode IN ('NONE','MATERIAL_REQUIRED'));
ALTER TABLE inventory_usage_snapshot DROP CONSTRAINT inventory_usage_snapshot_posting_ids_check;
ALTER TABLE inventory_usage_snapshot ADD CHECK (
    (material_mode='NONE' AND cardinality(posting_ids)=0) OR
    (material_mode='MATERIAL_REQUIRED' AND cardinality(posting_ids)>0));

DO $$ DECLARE constraint_row record; definition text;
BEGIN
    FOR constraint_row IN SELECT conname,pg_get_expr(conbin,conrelid) expression FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND contype='c' AND
        (pg_get_constraintdef(oid) LIKE '%OPENING_BALANCE%' OR pg_get_constraintdef(oid) LIKE '%PICKED%') LOOP
        EXECUTE format('ALTER TABLE inventory_document DROP CONSTRAINT %I',constraint_row.conname);
        EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT %I CHECK ((%s) OR (kind=''USAGE'' AND state IN (''DRAFT'',''POSTED'')))',
            constraint_row.conname,constraint_row.expression);
    END LOOP;
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF position('CASE OLD.kind' IN definition)=0 OR position('NEW.kind<>''DEMAND''' IN definition)=0 THEN
        RAISE EXCEPTION 'expected document guard missing';
    END IF;
    definition:=replace(definition,'CASE OLD.kind','CASE OLD.kind WHEN ''USAGE'' THEN (OLD.state,NEW.state)=(''DRAFT'',''POSTED'')');
    EXECUTE replace(definition,'NEW.kind<>''DEMAND''','NEW.kind NOT IN (''DEMAND'',''USAGE'')');
END $$;

CREATE TABLE inventory_material_usage (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), actor_id uuid NOT NULL,
    customer_id uuid, evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 500),
    reason text CHECK (reason IS NULL OR length(btrim(reason)) BETWEEN 1 AND 1000),
    network_reference_label text CHECK (network_reference_label IS NULL OR length(btrim(network_reference_label)) BETWEEN 1 AND 500),
    recorded_at timestamptz NOT NULL, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_usage_snapshot(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE INDEX inventory_material_usage_actor_idx ON inventory_material_usage(tenant_id,actor_id,recorded_at,id);
ALTER TABLE inventory_customer_material_fact ADD FOREIGN KEY (tenant_id,usage_id)
    REFERENCES inventory_material_usage(tenant_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE inventory_customer_material_fact ADD CHECK (customer_id IS NOT NULL OR usage_id IS NOT NULL);

CREATE TABLE inventory_material_usage_line (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), usage_id uuid NOT NULL,
    receipt_id uuid NOT NULL, issue_line_id uuid NOT NULL, plan_line_id uuid NOT NULL,
    source_identity_id uuid NOT NULL, source_revision bigint NOT NULL CHECK (source_revision>=0),
    consumed_identity_id uuid NOT NULL, remainder_identity_id uuid, fact_id uuid NOT NULL,
    requested_base bigint NOT NULL CHECK (requested_base>0), acknowledged_base bigint NOT NULL CHECK (acknowledged_base>0),
    used_base bigint NOT NULL CHECK (used_base>0), residual_base bigint NOT NULL CHECK (residual_base>=0),
    base_unit varchar(2) NOT NULL CHECK (base_unit IN ('MM','EA')),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,usage_id,receipt_id,issue_line_id),
    UNIQUE (tenant_id,usage_id,source_identity_id), UNIQUE (tenant_id,fact_id),
    CHECK (used_base::numeric+residual_base::numeric=acknowledged_base),
    CHECK ((residual_base=0)=(remainder_identity_id IS NULL)),
    FOREIGN KEY (tenant_id,usage_id) REFERENCES inventory_material_usage(tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_document_line(tenant_id,id),
    FOREIGN KEY (tenant_id,receipt_id,issue_line_id) REFERENCES inventory_material_receipt_line(tenant_id,receipt_id,issue_line_id),
    FOREIGN KEY (tenant_id,plan_line_id) REFERENCES inventory_material_plan_line(tenant_id,id),
    FOREIGN KEY (tenant_id,source_identity_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY (tenant_id,consumed_identity_id) REFERENCES inventory_segment(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,remainder_identity_id) REFERENCES inventory_segment(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,fact_id) REFERENCES inventory_customer_material_fact(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX inventory_material_usage_source_idx ON inventory_material_usage_line(tenant_id,receipt_id,issue_line_id);
CREATE INDEX inventory_material_usage_identity_idx ON inventory_material_usage_line(tenant_id,source_identity_id);
CREATE INDEX inventory_material_fact_usage_idx ON inventory_customer_material_fact(tenant_id,usage_id);

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_material_usage','inventory_material_usage_line'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
END $$;

CREATE FUNCTION warehouse_material_usage_insert_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_material_usage' THEN
        IF NEW.created_xid<>pg_current_xact_id() THEN RAISE EXCEPTION 'usage transaction mismatch' USING ERRCODE='23514'; END IF;
    ELSIF NOT EXISTS (SELECT FROM inventory_material_usage WHERE tenant_id=NEW.tenant_id AND id=NEW.usage_id AND created_xid=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'usage lines must be sealed in usage transaction' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_material_usage_insert BEFORE INSERT ON inventory_material_usage
    FOR EACH ROW EXECUTE FUNCTION warehouse_material_usage_insert_guard();
CREATE TRIGGER warehouse_material_usage_line_insert BEFORE INSERT ON inventory_material_usage_line
    FOR EACH ROW EXECUTE FUNCTION warehouse_material_usage_insert_guard();
