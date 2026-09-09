CREATE UNIQUE INDEX IF NOT EXISTS warehouse_role_tenant_id_uq ON role(tenant_id,id);

CREATE TABLE inventory_approval_policy_version (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, revision bigint NOT NULL CHECK (revision>0),
    currency varchar(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    expiry_hours integer NOT NULL CHECK (expiry_hours BETWEEN 1 AND 720),
    actor_id uuid NOT NULL, authority_epoch bigint NOT NULL CHECK (authority_epoch>=0),
    snapshot text NOT NULL, snapshot_hash varchar(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,revision),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE TABLE inventory_approval_policy_warehouse (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, policy_id uuid NOT NULL, location_id uuid NOT NULL,
    revision bigint NOT NULL DEFAULT 0 CHECK(revision=0), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,policy_id,location_id),
    FOREIGN KEY(tenant_id,policy_id) REFERENCES inventory_approval_policy_version(tenant_id,id),
    FOREIGN KEY(tenant_id,location_id) REFERENCES inventory_location(tenant_id,id)
);
CREATE TABLE inventory_approval_policy_tier (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, policy_id uuid NOT NULL,
    operation varchar(24) NOT NULL CHECK(operation IN ('RECEIPT','ISSUE','ISSUE_EXCEPTION','OPENING_BALANCE','ADJUSTMENT','LOSS','SCRAP','COUNT_VARIANCE','TITLE_REACQUISITION')),
    tier integer NOT NULL CHECK(tier>0), minimum_minor numeric(38,0) NOT NULL CHECK(minimum_minor>0),
    revision bigint NOT NULL DEFAULT 0 CHECK(revision=0), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,policy_id,operation,tier), UNIQUE(tenant_id,policy_id,operation,minimum_minor),
    FOREIGN KEY(tenant_id,policy_id) REFERENCES inventory_approval_policy_version(tenant_id,id)
);
CREATE TABLE inventory_approval_policy_approver (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, policy_id uuid NOT NULL, tier_id uuid NOT NULL,
    user_id uuid, role_id uuid,
    revision bigint NOT NULL DEFAULT 0 CHECK(revision=0), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK((user_id IS NULL)<>(role_id IS NULL)),
    UNIQUE(tenant_id,id), UNIQUE NULLS NOT DISTINCT(tenant_id,tier_id,user_id,role_id),
    FOREIGN KEY(tenant_id,policy_id) REFERENCES inventory_approval_policy_version(tenant_id,id),
    FOREIGN KEY(tenant_id,tier_id) REFERENCES inventory_approval_policy_tier(tenant_id,id),
    FOREIGN KEY(tenant_id,user_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,role_id) REFERENCES role(tenant_id,id)
);
CREATE TABLE inventory_settings_operation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, namespace varchar(80) NOT NULL,
    operation_key varchar(240) NOT NULL CHECK(btrim(operation_key)<>''), actor_id uuid NOT NULL,
    resource_id uuid NOT NULL, location_ids uuid[] NOT NULL, revision bigint NOT NULL CHECK(revision>=0),
    payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    original_status integer NOT NULL CHECK(original_status IN (200,201)), original_body text NOT NULL,
    authority_epoch bigint NOT NULL CHECK(authority_epoch>=0), cutover_epoch bigint NOT NULL CHECK(cutover_epoch>=0),
    bootstrap boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,namespace,operation_key),
    UNIQUE(tenant_id,namespace,resource_id,revision),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);

ALTER TABLE inventory_approval_delegation ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN source_role_id uuid, ADD COLUMN location_id uuid,
    ADD COLUMN operation varchar(24), ADD COLUMN granted_by uuid,
    ADD COLUMN valid_from timestamptz, ADD COLUMN revoked_at timestamptz,
    ADD COLUMN revoked_by uuid, ADD COLUMN authority_epoch bigint,
    ADD UNIQUE(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,source_role_id) REFERENCES role(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,location_id) REFERENCES inventory_location(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,granted_by) REFERENCES app_user(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,revoked_by) REFERENCES app_user(tenant_id,id),
    ADD CONSTRAINT warehouse_delegation_approver_fk FOREIGN KEY(tenant_id,approver_id) REFERENCES app_user(tenant_id,id) NOT VALID,
    ADD CONSTRAINT warehouse_delegation_delegate_fk FOREIGN KEY(tenant_id,delegate_id) REFERENCES app_user(tenant_id,id) NOT VALID,
    ADD CHECK(valid_from IS NULL OR (valid_until>valid_from AND valid_until<=valid_from+interval '30 days')),
    ADD CHECK((revoked_at IS NULL)=(revoked_by IS NULL));
CREATE INDEX warehouse_delegation_live_idx ON inventory_approval_delegation(tenant_id,location_id,operation,valid_until) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX warehouse_delegation_live_uq ON inventory_approval_delegation(tenant_id,approver_id,delegate_id,location_id,operation) WHERE revoked_at IS NULL AND location_id IS NOT NULL;

CREATE OR REPLACE FUNCTION warehouse_policy_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT FROM inventory_approval_policy_version WHERE tenant_id=NEW.tenant_id AND id=NEW.policy_id AND created_xid=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'policy history is sealed' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='inventory_approval_policy_approver' AND NOT EXISTS(
        SELECT FROM inventory_approval_policy_tier WHERE tenant_id=NEW.tenant_id AND id=NEW.tier_id AND policy_id=NEW.policy_id) THEN
        RAISE EXCEPTION 'tier belongs to another policy' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION warehouse_delegation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.location_id IS NULL OR NEW.operation IS NULL OR NEW.granted_by IS NULL OR NEW.valid_from IS NULL OR NEW.authority_epoch IS NULL
            OR NEW.operation NOT IN ('RECEIPT','ISSUE','ISSUE_EXCEPTION','OPENING_BALANCE','ADJUSTMENT','LOSS','SCRAP','COUNT_VARIANCE','TITLE_REACQUISITION') THEN
            RAISE EXCEPTION 'durable scoped delegation required' USING ERRCODE='23514';
        END IF;
    ELSE
        IF (to_jsonb(NEW)-ARRAY['revoked_at','revoked_by','revision','updated_at','authority_epoch']) IS DISTINCT FROM
            (to_jsonb(OLD)-ARRAY['revoked_at','revoked_by','revision','updated_at','authority_epoch']) OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL THEN
            RAISE EXCEPTION 'delegation grants are immutable except revocation' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_delegation_guard BEFORE INSERT OR UPDATE ON inventory_approval_delegation FOR EACH ROW EXECUTE FUNCTION warehouse_delegation_guard();
CREATE TRIGGER warehouse_delegation_revision BEFORE UPDATE ON inventory_approval_delegation FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard();
CREATE TRIGGER warehouse_delegation_no_delete BEFORE DELETE ON inventory_approval_delegation FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

ALTER TABLE inventory_approval ADD CONSTRAINT warehouse_approval_tenant_id_uq UNIQUE(tenant_id,id),
    ADD COLUMN policy_version_id uuid, ADD COLUMN source_document_id uuid,
    ADD COLUMN source_document_revision bigint CHECK(source_document_revision>=0),
    ADD COLUMN source_snapshot_hash varchar(64), ADD COLUMN business_action varchar(40),
    ADD COLUMN value_numerator numeric, ADD COLUMN value_denominator numeric CHECK(value_denominator>0),
    ADD COLUMN currency varchar(3), ADD COLUMN counter_id uuid,
    ADD COLUMN independence_snapshot jsonb, ADD COLUMN authority_epoch bigint,
    ADD FOREIGN KEY(tenant_id,policy_version_id) REFERENCES inventory_approval_policy_version(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,source_document_id) REFERENCES inventory_document(tenant_id,id),
    ADD CHECK((source_document_id IS NULL)=(source_document_revision IS NULL)),
    ADD CHECK((value_numerator IS NULL)=(value_denominator IS NULL)),
    ADD CHECK(currency IS NULL OR currency ~ '^[A-Z]{3}$');
CREATE UNIQUE INDEX warehouse_approval_source_uq ON inventory_approval(tenant_id,source_document_id,source_document_revision,business_action) WHERE source_document_id IS NOT NULL;
ALTER TABLE inventory_approval_decision ADD UNIQUE(tenant_id,id),
    ADD COLUMN delegation_id uuid, ADD COLUMN policy_version_id uuid, ADD COLUMN authority_epoch bigint,
    ADD COLUMN independence_snapshot jsonb,
    ADD FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id) NOT VALID,
    ADD FOREIGN KEY(tenant_id,delegation_id) REFERENCES inventory_approval_delegation(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,policy_version_id) REFERENCES inventory_approval_policy_version(tenant_id,id);
ALTER TABLE inventory_approval_effect ADD UNIQUE(tenant_id,id),
    ADD COLUMN source_document_id uuid, ADD COLUMN source_document_revision bigint,
    ADD COLUMN posting_operation_id uuid, ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    ADD FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id) NOT VALID,
    ADD FOREIGN KEY(tenant_id,source_document_id) REFERENCES inventory_document(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,posting_operation_id) REFERENCES inventory_operation(tenant_id,id);
CREATE TRIGGER warehouse_approval_effect_immutable BEFORE UPDATE OR DELETE ON inventory_approval_effect FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

ALTER TABLE inventory_cycle_count ADD UNIQUE(tenant_id,id),
    ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN document_id uuid, ADD COLUMN document_revision bigint CHECK(document_revision>=0),
    ADD COLUMN stock_identity_id uuid, ADD COLUMN balance_id uuid, ADD COLUMN observed_dimension_revision bigint CHECK(observed_dimension_revision>=0),
    ADD COLUMN prior_quantity_base bigint CHECK(prior_quantity_base>=0), ADD COLUMN observed_quantity_base bigint CHECK(observed_quantity_base>=0),
    ADD COLUMN base_unit varchar(2) CHECK(base_unit IN ('EA','MM')), ADD COLUMN counter_id uuid,
    ADD COLUMN approval_id uuid, ADD COLUMN posting_operation_id uuid,
    ADD FOREIGN KEY(tenant_id,document_id) REFERENCES inventory_document(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,stock_identity_id) REFERENCES inventory_segment(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,balance_id) REFERENCES inventory_balance_projection(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,posting_operation_id) REFERENCES inventory_operation(tenant_id,id),
    ADD FOREIGN KEY(tenant_id,counter_id) REFERENCES app_user(tenant_id,id),
    ADD CHECK(document_id IS NULL OR (document_revision IS NOT NULL AND stock_identity_id IS NOT NULL AND balance_id IS NOT NULL
        AND observed_dimension_revision IS NOT NULL AND prior_quantity_base IS NOT NULL AND observed_quantity_base IS NOT NULL AND base_unit IS NOT NULL AND counter_id IS NOT NULL));
CREATE UNIQUE INDEX warehouse_count_dimension_uq ON inventory_cycle_count(tenant_id,document_id,balance_id,document_revision) WHERE document_id IS NOT NULL;
CREATE TRIGGER warehouse_count_revision BEFORE UPDATE ON inventory_cycle_count FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard();

CREATE TABLE inventory_repair_case (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, return_document_id uuid NOT NULL, asset_id uuid NOT NULL,
    legal_owner varchar(8) NOT NULL CHECK(legal_owner IN ('ISP','CUSTOMER','UNKNOWN')),
    vendor_id uuid, vendor_reference varchar(500), outbound_document_id uuid, inspected_return_document_id uuid,
    replacement_asset_id uuid, result varchar(32), state varchar(24) NOT NULL CHECK(state IN ('DRAFT','OUTBOUND','IN_REPAIR','RETURNED','CLOSED')),
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,return_document_id,asset_id),
    FOREIGN KEY(tenant_id,return_document_id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,outbound_document_id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,inspected_return_document_id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,replacement_asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,vendor_id) REFERENCES inventory_supplier(tenant_id,id),
    CHECK(replacement_asset_id IS NULL OR replacement_asset_id<>asset_id)
);
CREATE TABLE inventory_replenishment_rule (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, sku_id uuid NOT NULL, location_id uuid NOT NULL,
    minimum_base bigint NOT NULL CHECK(minimum_base>=0), maximum_base bigint NOT NULL CHECK(maximum_base>minimum_base),
    base_unit varchar(2) NOT NULL CHECK(base_unit IN ('EA','MM')), active boolean NOT NULL DEFAULT true,
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,sku_id,location_id),
    FOREIGN KEY(tenant_id,sku_id,base_unit) REFERENCES inventory_sku(tenant_id,id,base_unit),
    FOREIGN KEY(tenant_id,location_id) REFERENCES inventory_location(tenant_id,id)
);
CREATE TABLE inventory_replenishment_request (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, rule_id uuid NOT NULL, rule_revision bigint NOT NULL CHECK(rule_revision>=0),
    business_key varchar(240) NOT NULL, quantity_base bigint NOT NULL CHECK(quantity_base>0),
    state varchar(16) NOT NULL CHECK(state IN ('PENDING','FULFILLED','CANCELLED')), source_document_id uuid,
    revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), created_at timestamptz NOT NULL DEFAULT clock_timestamp(), updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,business_key),
    FOREIGN KEY(tenant_id,rule_id) REFERENCES inventory_replenishment_rule(tenant_id,id),
    FOREIGN KEY(tenant_id,source_document_id) REFERENCES inventory_document(tenant_id,id)
);
CREATE UNIQUE INDEX warehouse_replenishment_pending_uq ON inventory_replenishment_request(tenant_id,rule_id) WHERE state='PENDING';

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_approval_policy_version','inventory_approval_policy_warehouse','inventory_approval_policy_tier',
        'inventory_approval_policy_approver','inventory_settings_operation','inventory_repair_case','inventory_replenishment_rule','inventory_replenishment_request'] LOOP
        EXECUTE format('ALTER TABLE %I ADD FOREIGN KEY(tenant_id) REFERENCES tenant(id)',table_name);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO warehouse_app',table_name);
        END IF;
        EXECUTE format('CREATE TRIGGER warehouse_no_delete BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_approval_policy_version','inventory_approval_policy_warehouse','inventory_approval_policy_tier','inventory_approval_policy_approver','inventory_settings_operation'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_immutable BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_approval_policy_warehouse','inventory_approval_policy_tier','inventory_approval_policy_approver'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_policy_child BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_policy_child_guard()',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_repair_case','inventory_replenishment_rule','inventory_replenishment_request'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_revision BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard()',table_name);
    END LOOP;
END $$;
