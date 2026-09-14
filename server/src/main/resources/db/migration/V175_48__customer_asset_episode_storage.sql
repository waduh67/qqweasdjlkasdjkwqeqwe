CREATE TABLE inventory_asset_assignment (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    asset_id uuid, customer_id uuid, work_order_id uuid, issue_line_id uuid,
    purpose text CHECK (purpose IN ('INSTALL','REPLACE','REMOVE','RETURN_CUSTOMER_RMA')),
    ownership_mode text CHECK (ownership_mode IN ('LOAN','SALE')),
    legal_owner text CHECK (legal_owner IN ('ISP','CUSTOMER','UNKNOWN')),
    provenance text NOT NULL DEFAULT 'UNKNOWN' CHECK (provenance IN ('RECEIPT','OPENING_BALANCE','UNKNOWN')),
    actor_id uuid, previous_assignment_id uuid,
    started_at timestamptz, ended_at timestamptz,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at timestamptz NOT NULL DEFAULT now(),
    warehouse_admission text NOT NULL DEFAULT 'VERIFIED' CHECK (warehouse_admission IN ('VERIFIED','LEGACY_UNRESOLVED')),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY (tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY (tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY (tenant_id,issue_line_id) REFERENCES inventory_issue_line(tenant_id,id),
    FOREIGN KEY (tenant_id,previous_assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    CHECK (ended_at IS NULL OR (started_at IS NOT NULL AND ended_at>started_at)),
    CHECK (warehouse_admission<>'VERIFIED' OR (asset_id IS NOT NULL AND customer_id IS NOT NULL
        AND work_order_id IS NOT NULL AND actor_id IS NOT NULL AND started_at IS NOT NULL
        AND purpose IS NOT NULL AND ownership_mode IS NOT NULL AND legal_owner IS NOT NULL
        AND provenance<>'UNKNOWN' AND (purpose NOT IN ('INSTALL','REPLACE') OR issue_line_id IS NOT NULL)))
);
CREATE UNIQUE INDEX inventory_assignment_active_asset_uq ON inventory_asset_assignment(tenant_id,asset_id)
    WHERE warehouse_admission='VERIFIED' AND ended_at IS NULL;
CREATE INDEX inventory_assignment_customer_idx ON inventory_asset_assignment(tenant_id,customer_id,started_at);
CREATE INDEX inventory_assignment_asset_interval_idx ON inventory_asset_assignment(tenant_id,asset_id,started_at,ended_at);
CREATE INDEX inventory_assignment_issue_idx ON inventory_asset_assignment(tenant_id,issue_line_id);
CREATE INDEX inventory_assignment_work_order_idx ON inventory_asset_assignment(tenant_id,work_order_id);

CREATE TABLE inventory_asset_handover (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    assignment_id uuid NOT NULL, asset_id uuid NOT NULL, work_order_id uuid NOT NULL,
    customer_id uuid NOT NULL, actor_id uuid NOT NULL, evidence_id uuid NOT NULL,
    evidence_reference text NOT NULL CHECK (btrim(evidence_reference)<>''),
    ownership_mode text NOT NULL CHECK (ownership_mode IN ('LOAN','SALE')),
    accepted_at timestamptz NOT NULL, assignment_revision bigint NOT NULL CHECK (assignment_revision>=0),
    warehouse_admission text NOT NULL DEFAULT 'VERIFIED' CHECK (warehouse_admission IN ('VERIFIED','LEGACY_UNRESOLVED')),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,assignment_id),
    FOREIGN KEY (tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY (tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY (tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY (tenant_id,customer_id) REFERENCES customer(tenant_id,id)
);

CREATE TABLE inventory_deployment_authorization (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    asset_id uuid NOT NULL, issue_line_id uuid, work_order_id uuid NOT NULL, customer_id uuid NOT NULL,
    actor_id uuid NOT NULL, purpose text NOT NULL CHECK (purpose IN ('INSTALL','REPLACE','REMOVE','RETURN_CUSTOMER_RMA')),
    ownership_mode text NOT NULL CHECK (ownership_mode IN ('LOAN','SALE')),
    operation_id uuid NOT NULL,
    expected_asset_revision bigint NOT NULL CHECK (expected_asset_revision>=0),
    expected_work_order_revision bigint NOT NULL CHECK (expected_work_order_revision>=0),
    expected_plan_revision bigint NOT NULL CHECK (expected_plan_revision>=0),
    expected_issue_revision bigint CHECK (expected_issue_revision>=0),
    expected_assignment_revision bigint CHECK (expected_assignment_revision>=0),
    previous_assignment_id uuid,
    authority_epoch bigint NOT NULL CHECK (authority_epoch>=0), cutover_epoch bigint NOT NULL CHECK (cutover_epoch>=0),
    consumed boolean NOT NULL DEFAULT false, consumed_at timestamptz,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0), created_at timestamptz NOT NULL DEFAULT now(),
    warehouse_admission text NOT NULL DEFAULT 'VERIFIED' CHECK (warehouse_admission IN ('VERIFIED','LEGACY_UNRESOLVED')),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,operation_id),
    FOREIGN KEY (tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY (tenant_id,issue_line_id) REFERENCES inventory_issue_line(tenant_id,id),
    FOREIGN KEY (tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY (tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY (tenant_id,previous_assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    CHECK (consumed=(consumed_at IS NOT NULL)),
    CHECK (consumed_at IS NULL OR consumed_at>=created_at),
    CHECK ((previous_assignment_id IS NULL)=(expected_assignment_revision IS NULL)),
    CHECK (purpose NOT IN ('INSTALL','REPLACE') OR (issue_line_id IS NOT NULL AND expected_issue_revision IS NOT NULL))
);

CREATE TABLE inventory_asset_assignment_history (
    tenant_id uuid NOT NULL, assignment_id uuid NOT NULL, revision bigint NOT NULL,
    snapshot jsonb NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,assignment_id,revision),
    FOREIGN KEY (tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id)
);
CREATE TABLE inventory_deployment_authorization_history (
    tenant_id uuid NOT NULL, authorization_id uuid NOT NULL, revision bigint NOT NULL,
    snapshot jsonb NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,authorization_id,revision),
    FOREIGN KEY (tenant_id,authorization_id) REFERENCES inventory_deployment_authorization(tenant_id,id)
);

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_assignment','inventory_asset_handover',
        'inventory_deployment_authorization','inventory_asset_assignment_history','inventory_deployment_authorization_history'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_assignment','inventory_asset_handover','inventory_deployment_authorization'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_admission BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_admission_guard()',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_assignment_history','inventory_deployment_authorization_history','inventory_asset_handover'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_immutable_history BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
END $$;
