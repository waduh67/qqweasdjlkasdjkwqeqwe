CREATE TABLE inventory_issue_snapshot (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    demand_document_id uuid NOT NULL, plan_id uuid NOT NULL, operation_id uuid NOT NULL,
    demand_revision bigint NOT NULL CHECK (demand_revision>=0),
    sender_id uuid NOT NULL, receiver_id uuid NOT NULL,
    snapshot text NOT NULL CHECK (jsonb_typeof(snapshot::jsonb)='object'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY (tenant_id,demand_document_id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY (tenant_id,plan_id) REFERENCES inventory_material_plan(tenant_id,id),
    FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id)
);

CREATE TABLE inventory_issue_line (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    issue_id uuid NOT NULL, reservation_id uuid NOT NULL, stock_identity_id uuid NOT NULL,
    reservation_revision bigint NOT NULL CHECK (reservation_revision>=0),
    quantity_base bigint NOT NULL CHECK (quantity_base>0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,issue_id,reservation_id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_document_line(tenant_id,id),
    FOREIGN KEY (tenant_id,issue_id) REFERENCES inventory_issue_snapshot(tenant_id,id),
    FOREIGN KEY (tenant_id,reservation_id) REFERENCES inventory_reservation(tenant_id,id),
    FOREIGN KEY (tenant_id,stock_identity_id) REFERENCES inventory_segment(tenant_id,id)
);

CREATE TABLE inventory_issue_unpick (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    operation_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_issue_snapshot(tenant_id,id),
    FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id)
);

DO $$ DECLARE table_name text; constraint_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_issue_snapshot','inventory_issue_line','inventory_issue_unpick'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    SELECT conname INTO STRICT constraint_name FROM pg_constraint WHERE conrelid='inventory_demand_supply_snapshot'::regclass
        AND contype='c' AND pg_get_constraintdef(oid) LIKE '%requested_base%reserved_unpicked_base%';
    EXECUTE format('ALTER TABLE inventory_demand_supply_snapshot DROP CONSTRAINT %I',constraint_name);
END $$;
ALTER TABLE inventory_demand_supply_snapshot ADD COLUMN issued_base bigint NOT NULL DEFAULT 0 CHECK (issued_base>=0);
ALTER TABLE inventory_demand_supply_snapshot ADD CONSTRAINT warehouse_supply_conservation_ck CHECK
    (requested_base::numeric=reserved_unpicked_base::numeric+reserved_picked_base::numeric+issued_base::numeric+backorder_base::numeric);

CREATE FUNCTION warehouse_issue_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_issue_snapshot' THEN
        IF NOT EXISTS (SELECT FROM inventory_document issue
            JOIN inventory_document demand ON demand.tenant_id=issue.tenant_id AND demand.id=NEW.demand_document_id
            JOIN inventory_material_submission submission ON submission.tenant_id=demand.tenant_id AND submission.document_id=demand.id
            JOIN inventory_operation operation ON operation.tenant_id=issue.tenant_id AND operation.id=NEW.operation_id
            WHERE issue.tenant_id=NEW.tenant_id AND issue.id=NEW.id AND issue.kind='ISSUE' AND issue.state='PICKED'
                AND demand.kind='DEMAND' AND demand.work_order_id=issue.work_order_id AND demand.customer_id IS NOT DISTINCT FROM issue.customer_id
                AND demand.plan_revision=issue.plan_revision AND submission.id=NEW.plan_id
                AND operation.document_id=issue.id AND operation.business_action='PICK' AND operation.document_revision=1
                AND operation.actor_id=NEW.sender_id) THEN
            RAISE EXCEPTION 'issue requires a posted pick and submitted demand binding' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='inventory_issue_line' THEN
        IF NOT EXISTS (SELECT FROM inventory_issue_snapshot snapshot
            JOIN inventory_document_line line ON line.tenant_id=snapshot.tenant_id AND line.document_id=snapshot.id
            JOIN inventory_reservation reservation ON reservation.tenant_id=line.tenant_id AND reservation.id=NEW.reservation_id
            WHERE snapshot.tenant_id=NEW.tenant_id AND snapshot.id=NEW.issue_id AND line.id=NEW.id
                AND line.source_line_id=reservation.document_line_id AND line.quantity_base=NEW.quantity_base
                AND reservation.stock_identity_id=NEW.stock_identity_id AND reservation.revision=NEW.reservation_revision
                AND reservation.state='OPEN' AND reservation.reserved_picked_base=NEW.quantity_base) THEN
            RAISE EXCEPTION 'issue line requires its exact picked reservation' USING ERRCODE='23514';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT FROM inventory_operation WHERE tenant_id=NEW.tenant_id AND id=NEW.operation_id
            AND document_id=NEW.id AND business_action='UNPICK') THEN
            RAISE EXCEPTION 'unpick requires its posted operation' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_issue_binding BEFORE INSERT ON inventory_issue_snapshot FOR EACH ROW EXECUTE FUNCTION warehouse_issue_binding_guard();
CREATE TRIGGER warehouse_issue_line_binding BEFORE INSERT ON inventory_issue_line FOR EACH ROW EXECUTE FUNCTION warehouse_issue_binding_guard();
CREATE TRIGGER warehouse_issue_unpick_binding BEFORE INSERT ON inventory_issue_unpick FOR EACH ROW EXECUTE FUNCTION warehouse_issue_binding_guard();
