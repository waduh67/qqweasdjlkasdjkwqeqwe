CREATE TABLE inventory_asset_removal (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, assignment_id uuid NOT NULL, asset_id uuid NOT NULL,
    customer_id uuid NOT NULL, work_order_id uuid NOT NULL, actor_id uuid NOT NULL,
    authorization_id uuid, replacement_assignment_id uuid, replacement_asset_id uuid,
    source_assignment_revision bigint NOT NULL CHECK(source_assignment_revision>=0),
    source_asset_revision bigint NOT NULL CHECK(source_asset_revision>=0), title_revision bigint NOT NULL CHECK(title_revision>=0),
    legal_owner text NOT NULL CHECK(legal_owner IN ('ISP','CUSTOMER')),
    source_location_id uuid NOT NULL, recovery_location_id uuid NOT NULL,
    evidence_id uuid NOT NULL, evidence_digest text NOT NULL CHECK(evidence_digest ~ '^[0-9a-f]{64}$'),
    work_order_revision bigint NOT NULL, authority_epoch bigint NOT NULL, cutover_epoch bigint NOT NULL,
    operation_key text NOT NULL CHECK(length(operation_key) BETWEEN 1 AND 200),
    payload_hash text NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'), result text NOT NULL,
    removed_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,assignment_id), UNIQUE(tenant_id,authorization_id), UNIQUE(tenant_id,operation_key),
    CHECK((authorization_id IS NULL AND replacement_assignment_id IS NULL AND replacement_asset_id IS NULL)
        OR (authorization_id IS NOT NULL AND replacement_assignment_id IS NOT NULL AND replacement_asset_id IS NOT NULL
            AND assignment_id<>replacement_assignment_id AND asset_id<>replacement_asset_id)),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,authorization_id) REFERENCES inventory_deployment_authorization(tenant_id,id),
    FOREIGN KEY(tenant_id,replacement_assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,replacement_asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,source_location_id) REFERENCES inventory_location(tenant_id,id),
    FOREIGN KEY(tenant_id,recovery_location_id) REFERENCES inventory_location(tenant_id,id),
    FOREIGN KEY(tenant_id,evidence_id) REFERENCES evidence_object_registry(tenant_id,revision_id),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE inventory_asset_removal_origin (
    tenant_id uuid NOT NULL, removal_id uuid NOT NULL, assignment_snapshot jsonb NOT NULL,
    asset_snapshot jsonb NOT NULL, origin_snapshot jsonb NOT NULL, evidence_snapshot jsonb NOT NULL,
    PRIMARY KEY(tenant_id,removal_id), FOREIGN KEY(tenant_id,removal_id) REFERENCES inventory_asset_removal(tenant_id,id)
);
CREATE TABLE customer_asset_retirement (
    tenant_id uuid NOT NULL, removal_id uuid NOT NULL, episode_id uuid NOT NULL, onu_id uuid,
    response text NOT NULL, retired_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,removal_id), UNIQUE(tenant_id,episode_id),
    FOREIGN KEY(tenant_id,removal_id) REFERENCES inventory_asset_removal(tenant_id,id),
    FOREIGN KEY(tenant_id,episode_id) REFERENCES customer_asset_installation(tenant_id,id),
    FOREIGN KEY(tenant_id,onu_id) REFERENCES onu(tenant_id,id)
);
CREATE TABLE fulfillment_asset_outbox (
    tenant_id uuid NOT NULL, operation_id uuid NOT NULL, customer_id uuid NOT NULL, work_order_id uuid NOT NULL,
    old_onu_id uuid, new_onu_id uuid, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(tenant_id,operation_id),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_asset_removal(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,old_onu_id) REFERENCES onu(tenant_id,id),
    FOREIGN KEY(tenant_id,new_onu_id) REFERENCES onu(tenant_id,id)
);
CREATE TABLE fulfillment_asset_delivery (
    tenant_id uuid NOT NULL, operation_id uuid NOT NULL, revision bigint NOT NULL DEFAULT 0,
    state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','DELIVERING','SUCCEEDED','RECONCILIATION_REQUIRED')),
    lease_token uuid, lease_until timestamptz, attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
    failure_code text, completed_at timestamptz,
    PRIMARY KEY(tenant_id,operation_id), FOREIGN KEY(tenant_id,operation_id) REFERENCES fulfillment_asset_outbox(tenant_id,operation_id),
    CHECK((state='DELIVERING')=(lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    CHECK((state='SUCCEEDED')=(completed_at IS NOT NULL))
);
DO $$ DECLARE table_name text; item record; definition text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_removal','inventory_asset_removal_origin','customer_asset_retirement',
        'fulfillment_asset_outbox','fulfillment_asset_delivery'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        IF table_name<>'fulfillment_asset_delivery' THEN
            EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        END IF;
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    FOR item IN SELECT conname,pg_get_constraintdef(oid) definition FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND conname IN ('inventory_document_kind_check','inventory_document_check2') LOOP
        EXECUTE format('ALTER TABLE inventory_document DROP CONSTRAINT %I',item.conname);
        EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT %I CHECK ((%s) OR (kind=''ASSET_REMOVAL'' AND state IN (''DRAFT'',''POSTED'')))',
            item.conname,substring(item.definition FROM 8 FOR length(item.definition)-8));
    END LOOP;
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF strpos(definition,'CASE OLD.kind')=0 THEN RAISE EXCEPTION 'document lifecycle missing'; END IF;
    EXECUTE replace(definition,'CASE OLD.kind','CASE OLD.kind WHEN ''ASSET_REMOVAL'' THEN (OLD.state,NEW.state)=(''DRAFT'',''POSTED'')');
END $$;
