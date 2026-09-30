CREATE TABLE inventory_material_lifecycle (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), work_order_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision>0), previous_id uuid, actor_id uuid NOT NULL,
    work_order_revision bigint NOT NULL CHECK (work_order_revision>=0),
    action varchar(16) NOT NULL CHECK (action IN ('CANCEL','REASSIGN','REWORK','RESUBMIT','CLOSE')),
    material_state varchar(16) NOT NULL CHECK (material_state IN ('OPEN','SETTLING','CLOSED','OVERDUE')),
    operation_key text NOT NULL, payload_hash varchar(64) NOT NULL, body text NOT NULL,
    cutover_epoch bigint NOT NULL, due_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,work_order_id,revision), UNIQUE (tenant_id,action,operation_key),
    FOREIGN KEY (tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY (tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY (tenant_id,previous_id) REFERENCES inventory_material_lifecycle(tenant_id,id)
);
CREATE INDEX inventory_material_lifecycle_due_idx ON inventory_material_lifecycle(tenant_id,due_at,work_order_id,revision);

CREATE TABLE inventory_material_obligation_snapshot (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), lifecycle_id uuid NOT NULL,
    issue_line_id uuid NOT NULL, stock_identity_id uuid NOT NULL, base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')),
    issued_base bigint NOT NULL CHECK (issued_base>0), used_base bigint NOT NULL CHECK (used_base>=0),
    returned_base bigint NOT NULL CHECK (returned_base>=0), transferred_base bigint NOT NULL CHECK (transferred_base>=0),
    disposed_base bigint NOT NULL CHECK (disposed_base>=0), accountable_base bigint NOT NULL CHECK (accountable_base>=0),
    transit_base bigint NOT NULL CHECK (transit_base>=0), acknowledged_base bigint NOT NULL CHECK (acknowledged_base>=0),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,lifecycle_id,issue_line_id),
    CHECK (issued_base::numeric=used_base::numeric+returned_base::numeric+transferred_base::numeric+disposed_base::numeric+accountable_base::numeric),
    FOREIGN KEY (tenant_id,lifecycle_id) REFERENCES inventory_material_lifecycle(tenant_id,id),
    FOREIGN KEY (tenant_id,issue_line_id) REFERENCES inventory_issue_line(tenant_id,id),
    FOREIGN KEY (tenant_id,stock_identity_id) REFERENCES inventory_segment(tenant_id,id)
);

CREATE TABLE inventory_material_residual (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), work_order_id uuid NOT NULL,
    receipt_id uuid NOT NULL, issue_line_id uuid NOT NULL, usage_id uuid,
    source_identity_id uuid NOT NULL, transit_identity_id uuid NOT NULL, remainder_identity_id uuid,
    source_revision bigint NOT NULL CHECK (source_revision>=0), source_quantity_base bigint NOT NULL CHECK (source_quantity_base>0),
    quantity_base bigint NOT NULL CHECK (quantity_base>0 AND quantity_base<=source_quantity_base),
    base_unit varchar(2) NOT NULL CHECK (base_unit IN ('MM','EA')), sender_id uuid NOT NULL,
    target_location_id uuid NOT NULL, receiver_id uuid, purpose varchar(8) NOT NULL CHECK (purpose IN ('RETURN','HANDOVER')),
    source_dimension text NOT NULL, transit_dimension text NOT NULL,
    evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 500),
    dispatch_operation_id uuid NOT NULL, dispatch_posting_id uuid NOT NULL, body text NOT NULL,
    recorded_at timestamptz NOT NULL, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,dispatch_operation_id), UNIQUE (tenant_id,dispatch_posting_id),
    CHECK ((purpose='HANDOVER')=(receiver_id IS NOT NULL)), CHECK (sender_id IS DISTINCT FROM receiver_id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY (tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY (tenant_id,receipt_id,issue_line_id) REFERENCES inventory_material_receipt_line(tenant_id,receipt_id,issue_line_id),
    FOREIGN KEY (tenant_id,usage_id) REFERENCES inventory_material_usage(tenant_id,id),
    FOREIGN KEY (tenant_id,source_identity_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY (tenant_id,transit_identity_id) REFERENCES inventory_segment(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,remainder_identity_id) REFERENCES inventory_segment(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,target_location_id) REFERENCES inventory_location(tenant_id,id),
    FOREIGN KEY (tenant_id,sender_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY (tenant_id,receiver_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY (tenant_id,dispatch_operation_id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,dispatch_posting_id) REFERENCES inventory_movement(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX inventory_material_residual_source_idx ON inventory_material_residual(tenant_id,issue_line_id,source_identity_id);
CREATE INDEX inventory_material_residual_custody_idx ON inventory_material_residual(tenant_id,sender_id,receiver_id,work_order_id);

CREATE TABLE inventory_material_residual_ack (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), residual_id uuid NOT NULL,
    actor_id uuid NOT NULL, posting_id uuid NOT NULL,
    evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 500),
    body text NOT NULL, recorded_at timestamptz NOT NULL, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,residual_id), UNIQUE (tenant_id,posting_id),
    FOREIGN KEY (tenant_id,residual_id) REFERENCES inventory_material_residual(tenant_id,id),
    FOREIGN KEY (tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (tenant_id,posting_id) REFERENCES inventory_movement(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE inventory_material_cancel_release (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), lifecycle_id uuid NOT NULL,
    reservation_id uuid NOT NULL, source_revision bigint NOT NULL CHECK (source_revision>=0),
    quantity_base bigint NOT NULL CHECK (quantity_base>0), stock_identity_id uuid NOT NULL,
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,reservation_id),
    FOREIGN KEY (tenant_id,lifecycle_id) REFERENCES inventory_material_lifecycle(tenant_id,id),
    FOREIGN KEY (tenant_id,reservation_id) REFERENCES inventory_reservation(tenant_id,id),
    FOREIGN KEY (tenant_id,stock_identity_id) REFERENCES inventory_segment(tenant_id,id)
);

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_material_lifecycle','inventory_material_obligation_snapshot',
        'inventory_material_residual','inventory_material_residual_ack','inventory_material_cancel_release'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
END $$;
