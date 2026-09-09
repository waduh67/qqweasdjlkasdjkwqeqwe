CREATE TABLE inventory_reservation_allocation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    reservation_id uuid NOT NULL, plan_line_id uuid NOT NULL, operation_id uuid NOT NULL,
    origin_line_id uuid NOT NULL, origin_revision bigint NOT NULL CHECK (origin_revision>=0),
    stock_revision bigint NOT NULL CHECK (stock_revision>=0),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,reservation_id),
    FOREIGN KEY (tenant_id,reservation_id) REFERENCES inventory_reservation(tenant_id,id),
    FOREIGN KEY (tenant_id,plan_line_id) REFERENCES inventory_material_plan_line(tenant_id,id),
    FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY (tenant_id,origin_line_id) REFERENCES inventory_document_line(tenant_id,id)
);

CREATE TABLE inventory_demand_supply_snapshot (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    operation_id uuid NOT NULL, document_line_id uuid NOT NULL, plan_line_id uuid NOT NULL,
    requested_base bigint NOT NULL CHECK (requested_base>0),
    reserved_unpicked_base bigint NOT NULL CHECK (reserved_unpicked_base>=0),
    reserved_picked_base bigint NOT NULL CHECK (reserved_picked_base>=0),
    backorder_base bigint NOT NULL CHECK (backorder_base>=0),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,operation_id,document_line_id),
    FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY (tenant_id,document_line_id) REFERENCES inventory_document_line(tenant_id,id),
    FOREIGN KEY (tenant_id,plan_line_id) REFERENCES inventory_material_plan_line(tenant_id,id),
    CHECK (requested_base::numeric=reserved_unpicked_base::numeric+reserved_picked_base::numeric+backorder_base::numeric)
);

DO $$ DECLARE table_name text; constraint_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_reservation_allocation','inventory_demand_supply_snapshot'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    SELECT conname INTO STRICT constraint_name FROM pg_constraint
        WHERE conrelid='inventory_reservation'::regclass AND contype='u' AND cardinality(conkey)>2;
    EXECUTE format('ALTER TABLE inventory_reservation DROP CONSTRAINT %I',constraint_name);
END $$;
CREATE UNIQUE INDEX inventory_reservation_open_dimension_uq ON inventory_reservation
    (tenant_id,document_line_id,stock_identity_id,lot_id,location_id,custodian_id,custodian_kind,condition,legal_owner)
    NULLS NOT DISTINCT WHERE state='OPEN';
CREATE INDEX inventory_allocation_plan_idx ON inventory_reservation_allocation(tenant_id,plan_line_id,id);

CREATE FUNCTION warehouse_allocation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NOT EXISTS (
        SELECT FROM inventory_reservation reservation
        JOIN inventory_document_line demand ON demand.tenant_id=reservation.tenant_id AND demand.id=reservation.document_line_id
        JOIN inventory_document document ON document.tenant_id=demand.tenant_id AND document.id=demand.document_id
        JOIN inventory_material_plan_line line ON line.tenant_id=reservation.tenant_id AND line.id=NEW.plan_line_id
        JOIN inventory_material_plan plan ON plan.tenant_id=line.tenant_id AND plan.id=line.plan_id
        JOIN inventory_segment segment ON segment.tenant_id=reservation.tenant_id AND segment.id=reservation.stock_identity_id
        LEFT JOIN inventory_lot lot ON lot.tenant_id=segment.tenant_id AND lot.id=segment.lot_id
        LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
        JOIN inventory_document_line origin ON origin.tenant_id=segment.tenant_id AND origin.id=coalesce(lot.origin_document_line_id,asset.origin_document_line_id)
        JOIN inventory_document source ON source.tenant_id=origin.tenant_id AND source.id=origin.document_id
        WHERE reservation.tenant_id=NEW.tenant_id AND reservation.id=NEW.reservation_id
        AND document.kind='DEMAND' AND document.work_order_id=plan.work_order_id AND document.plan_revision=plan.plan_revision
        AND plan.state='SUBMITTED' AND plan.material_mode='MATERIAL_REQUIRED'
        AND demand.line_number=line.line_number AND demand.sku_id=line.sku_id AND demand.base_unit=line.base_unit
        AND demand.quantity_base=line.quantity_base AND demand.continuous_cut=line.continuous_cut
        AND origin.id=NEW.origin_line_id AND source.revision=NEW.origin_revision AND segment.revision=NEW.stock_revision
    ) THEN RAISE EXCEPTION 'allocation must bind actual demand plan and origin' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_allocation_binding BEFORE INSERT ON inventory_reservation_allocation
    FOR EACH ROW EXECUTE FUNCTION warehouse_allocation_guard();

CREATE OR REPLACE FUNCTION warehouse_document_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE permitted boolean;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.state<>'DRAFT' THEN RAISE EXCEPTION 'documents must start as drafts' USING ERRCODE='23514'; END IF;
        RETURN NEW;
    END IF;
    IF TG_OP='DELETE' THEN
        IF OLD.state<>'DRAFT' THEN RAISE EXCEPTION 'posted document is immutable' USING ERRCODE='23514'; END IF;
        RETURN OLD;
    END IF;
    IF OLD.state<>'DRAFT' AND (to_jsonb(NEW)-ARRAY['state','revision','updated_at','closed_at']) IS DISTINCT FROM
                            (to_jsonb(OLD)-ARRAY['state','revision','updated_at','closed_at']) THEN
        RAISE EXCEPTION 'posted document snapshot is immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.kind<>OLD.kind THEN RAISE EXCEPTION 'document kind is immutable' USING ERRCODE='23514'; END IF;
    IF NEW.state=OLD.state THEN RETURN NEW; END IF;
    permitted := CASE OLD.kind
        WHEN 'RECEIPT' THEN (OLD.state,NEW.state) IN (('DRAFT','RECEIVED_IN_INSPECTION'),('RECEIVED_IN_INSPECTION','PUTAWAY'),('RECEIVED_IN_INSPECTION','CLOSED'),('PUTAWAY','CLOSED'))
        WHEN 'DEMAND' THEN (OLD.state,NEW.state) IN (('DRAFT','SUBMITTED'),('DRAFT','CANCELLED'),('SUBMITTED','PART_RESERVED'),('SUBMITTED','RESERVED'),('PART_RESERVED','RESERVED'),('PART_RESERVED','PART_ISSUED'),('RESERVED','PART_ISSUED'),('RESERVED','ISSUED'),('PART_ISSUED','ISSUED'),('PART_ISSUED','SETTLING'),('ISSUED','SETTLING'),('SETTLING','CLOSED'),('SUBMITTED','CANCELLED'),('PART_RESERVED','CANCELLED'),('RESERVED','CANCELLED'),('RESERVED','PART_RESERVED'),('RESERVED','SUBMITTED'),('PART_RESERVED','SUBMITTED'))
        WHEN 'ISSUE' THEN (OLD.state,NEW.state) IN (('DRAFT','PICKED'),('PICKED','DISPATCHED'),('DISPATCHED','PART_RECEIVED'),('DISPATCHED','RECEIVED'),('PART_RECEIVED','RECEIVED'))
        WHEN 'TRANSFER' THEN (OLD.state,NEW.state) IN (('DRAFT','DISPATCHED'),('DISPATCHED','PART_RECEIVED'),('DISPATCHED','RECEIVED'),('DISPATCHED','DISCREPANCY'),('PART_RECEIVED','RECEIVED'),('PART_RECEIVED','DISCREPANCY'))
        WHEN 'RETURN' THEN (OLD.state,NEW.state) IN (('DRAFT','DISPATCHED'),('DISPATCHED','RECEIVED_IN_INSPECTION'),('RECEIVED_IN_INSPECTION','ACCEPTED'),('RECEIVED_IN_INSPECTION','REPAIR'),('RECEIVED_IN_INSPECTION','SUPPLIER_RETURN'),('RECEIVED_IN_INSPECTION','SCRAP'))
        WHEN 'COUNT' THEN (OLD.state,NEW.state) IN (('DRAFT','COUNTING'),('COUNTING','SUBMITTED'),('SUBMITTED','APPROVED'),('APPROVED','POSTED'),('APPROVED','RECOUNT_REQUIRED'),('RECOUNT_REQUIRED','COUNTING'))
        ELSE (OLD.state,NEW.state) IN (('DRAFT','SUBMITTED'),('SUBMITTED','APPROVED'),('APPROVED','POSTED'),('POSTED','CLOSED')) END;
    IF NOT permitted THEN RAISE EXCEPTION 'invalid document lifecycle' USING ERRCODE='23514'; END IF;
    IF OLD.state='DRAFT' AND NEW.state<>'CANCELLED' AND NEW.kind<>'DEMAND' AND
       (NOT EXISTS (SELECT FROM inventory_document_line WHERE tenant_id=NEW.tenant_id AND document_id=NEW.id) OR
        EXISTS (SELECT FROM inventory_document_line WHERE tenant_id=NEW.tenant_id AND document_id=NEW.id AND
            (stock_identity_id IS NULL OR location_id IS NULL OR custodian_id IS NULL OR custodian_kind IS NULL OR condition IS NULL OR legal_owner IS NULL))) THEN
        RAISE EXCEPTION 'posting requires explicit physical dimensions' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
