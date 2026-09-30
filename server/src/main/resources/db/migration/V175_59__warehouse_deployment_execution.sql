ALTER TABLE inventory_serialized_asset DROP CONSTRAINT inventory_asset_status_ck;
ALTER TABLE inventory_serialized_asset ADD CONSTRAINT inventory_asset_status_ck CHECK
    (status IN ('AVAILABLE','RESERVED','ISSUED','IN_TRANSIT','CONSUMED','RETURNED','QUARANTINE','LOST','DISPOSED','CUSTOMER_INSTALLED'));

DO $$ DECLARE rule record;
BEGIN
    FOR rule IN SELECT conname,pg_get_expr(conbin,conrelid) expression FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND contype='c' AND pg_get_expr(conbin,conrelid) LIKE '%kind%' LOOP
        EXECUTE format('ALTER TABLE inventory_document DROP CONSTRAINT %I',rule.conname);
        EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT %I CHECK ((%s) OR (kind=''DEPLOYMENT'' AND state IN (''DRAFT'',''POSTED'')))',rule.conname,rule.expression);
    END LOOP;
END $$;

CREATE TABLE inventory_deployment_execution (
    tenant_id uuid NOT NULL, authorization_id uuid NOT NULL, receipt_id uuid NOT NULL,
    receipt_revision bigint NOT NULL CHECK(receipt_revision>=0), use_revision bigint NOT NULL CHECK(use_revision>=0),
    plan_id uuid NOT NULL, mint_key text NOT NULL CHECK(length(mint_key) BETWEEN 1 AND 200), mint_hash text NOT NULL,
    binding text NOT NULL, source text NOT NULL,
    PRIMARY KEY(tenant_id,authorization_id), UNIQUE(tenant_id,mint_key),
    FOREIGN KEY(tenant_id,authorization_id) REFERENCES inventory_deployment_authorization(tenant_id,id),
    FOREIGN KEY(tenant_id,receipt_id) REFERENCES inventory_material_receipt(tenant_id,id),
    FOREIGN KEY(tenant_id,plan_id) REFERENCES inventory_material_plan(tenant_id,id)
);
CREATE TABLE inventory_deployment_result (
    tenant_id uuid NOT NULL, authorization_id uuid NOT NULL, assignment_id uuid NOT NULL,
    posting_id uuid NOT NULL, operation_id uuid NOT NULL, use_revision bigint NOT NULL CHECK(use_revision>0),
    consume_key text NOT NULL CHECK(length(consume_key) BETWEEN 1 AND 200), consume_hash text NOT NULL,
    result text NOT NULL, creates_onu boolean NOT NULL,
    PRIMARY KEY(tenant_id,authorization_id), UNIQUE(tenant_id,assignment_id), UNIQUE(tenant_id,operation_id),
    UNIQUE(tenant_id,consume_key), UNIQUE(tenant_id,posting_id),
    FOREIGN KEY(tenant_id,authorization_id) REFERENCES inventory_deployment_execution(tenant_id,authorization_id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY(tenant_id,posting_id) REFERENCES inventory_movement(tenant_id,id)
);
CREATE TABLE customer_asset_installation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, customer_id uuid NOT NULL, asset_id uuid NOT NULL,
    assignment_id uuid NOT NULL, operation_id uuid NOT NULL, onu_id uuid,
    response text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,assignment_id), UNIQUE(tenant_id,operation_id), UNIQUE(tenant_id,onu_id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY(tenant_id,onu_id) REFERENCES onu(tenant_id,id)
);
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_deployment_execution','inventory_deployment_result','customer_asset_installation'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
END $$;
