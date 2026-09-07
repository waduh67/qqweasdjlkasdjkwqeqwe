CREATE TABLE inventory_document (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
    code varchar(64) NOT NULL CHECK (btrim(code)<>''),
    kind varchar(20) NOT NULL CHECK (kind IN ('RECEIPT','OPENING_BALANCE','DEMAND','ISSUE','TRANSFER','RETURN','REPAIR','COUNT','LOSS','SCRAP','ADJUSTMENT')),
    state varchar(32) NOT NULL DEFAULT 'DRAFT', actor_id uuid NOT NULL,
    work_order_id uuid, customer_id uuid, supplier_id uuid, source_document_id uuid,
    source_revision bigint CHECK (source_revision>=0), work_order_revision bigint CHECK (work_order_revision>=0),
    plan_revision bigint CHECK (plan_revision>=0), use_revision bigint CHECK (use_revision>=0),
    customer_label_snapshot varchar(255), work_order_code_snapshot varchar(120),
    source_reference varchar(500), reason varchar(1000), migration_batch_id uuid,
    cutover_epoch bigint NOT NULL CHECK (cutover_epoch>=0), authority_epoch bigint NOT NULL CHECK (authority_epoch>=0),
    submitted_at timestamptz, closed_at timestamptz,
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,code),
    FOREIGN KEY (tenant_id,supplier_id) REFERENCES inventory_supplier(tenant_id,id),
    FOREIGN KEY (tenant_id,source_document_id) REFERENCES inventory_document(tenant_id,id),
    CHECK ((source_document_id IS NULL)=(source_revision IS NULL)),
    CHECK (source_document_id IS NULL OR source_document_id<>id),
    CHECK (state='DRAFT' OR
        (kind='RECEIPT' AND state IN ('RECEIVED_IN_INSPECTION','PUTAWAY','CLOSED')) OR
        (kind='DEMAND' AND state IN ('SUBMITTED','PART_RESERVED','RESERVED','PART_ISSUED','ISSUED','SETTLING','CLOSED','CANCELLED')) OR
        (kind='ISSUE' AND state IN ('PICKED','DISPATCHED','PART_RECEIVED','RECEIVED')) OR
        (kind='TRANSFER' AND state IN ('DISPATCHED','PART_RECEIVED','RECEIVED','DISCREPANCY')) OR
        (kind='RETURN' AND state IN ('DISPATCHED','RECEIVED_IN_INSPECTION','ACCEPTED','REPAIR','SUPPLIER_RETURN','SCRAP')) OR
        (kind='COUNT' AND state IN ('COUNTING','SUBMITTED','APPROVED','POSTED','RECOUNT_REQUIRED')) OR
        (kind IN ('OPENING_BALANCE','LOSS','SCRAP','ADJUSTMENT','REPAIR') AND state IN ('SUBMITTED','APPROVED','POSTED','CLOSED')))
);

CREATE TABLE inventory_document_line (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, document_id uuid NOT NULL,
    line_number integer NOT NULL CHECK (line_number>0), document_revision bigint NOT NULL CHECK (document_revision>=0),
    sku_id uuid NOT NULL, stock_identity_id uuid, lot_id uuid, source_line_id uuid,
    base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')),
    tracking varchar(8) NOT NULL CHECK (tracking IN ('SERIAL','LOT','BULK')),
    quantity_base bigint NOT NULL CHECK (quantity_base>0), continuous_cut boolean NOT NULL DEFAULT true,
    location_id uuid, destination_location_id uuid, custodian_id uuid, custodian_kind varchar(24),
    condition varchar(12) CHECK (condition IN ('SERVICEABLE','QUARANTINE','DAMAGED','SCRAP')),
    legal_owner varchar(8) CHECK (legal_owner IN ('ISP','CUSTOMER','UNKNOWN')),
    accepted_base bigint NOT NULL DEFAULT 0 CHECK (accepted_base>=0),
    rejected_base bigint NOT NULL DEFAULT 0 CHECK (rejected_base>=0), missing_base bigint NOT NULL DEFAULT 0 CHECK (missing_base>=0),
    cost_total_minor bigint CHECK (cost_total_minor>=0), cost_basis_quantity_base bigint CHECK (cost_basis_quantity_base>0), currency varchar(3),
    conversion_numerator bigint CHECK (conversion_numerator>0), conversion_denominator bigint CHECK (conversion_denominator>0), package_quantity bigint CHECK (package_quantity>0),
    inspection_required_snapshot boolean NOT NULL DEFAULT true,
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,document_id,line_number),
    FOREIGN KEY (tenant_id,document_id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY (tenant_id,sku_id,base_unit) REFERENCES inventory_sku(tenant_id,id,base_unit),
    FOREIGN KEY (tenant_id,stock_identity_id,sku_id,base_unit) REFERENCES inventory_segment(tenant_id,id,sku_id,base_unit),
    FOREIGN KEY (tenant_id,lot_id,sku_id,base_unit) REFERENCES inventory_lot(tenant_id,id,sku_id,base_unit),
    FOREIGN KEY (tenant_id,source_line_id) REFERENCES inventory_document_line(tenant_id,id),
    FOREIGN KEY (tenant_id,location_id) REFERENCES inventory_location(tenant_id,id),
    FOREIGN KEY (tenant_id,destination_location_id) REFERENCES inventory_location(tenant_id,id),
    CHECK (tracking<>'SERIAL' OR (base_unit='EA' AND (stock_identity_id IS NULL OR quantity_base=1))),
    CHECK (accepted_base::numeric+rejected_base::numeric+missing_base::numeric<=quantity_base),
    CHECK ((cost_total_minor IS NULL AND cost_basis_quantity_base IS NULL AND currency IS NULL) OR
        (cost_total_minor IS NOT NULL AND cost_basis_quantity_base IS NOT NULL AND currency IS NOT NULL AND currency ~ '^[A-Z]{3}$')),
    CHECK ((conversion_numerator IS NULL AND conversion_denominator IS NULL AND package_quantity IS NULL) OR
        (conversion_numerator IS NOT NULL AND conversion_denominator IS NOT NULL AND package_quantity IS NOT NULL AND
         package_quantity::numeric*conversion_numerator=quantity_base::numeric*conversion_denominator))
);

ALTER TABLE inventory_lot ADD COLUMN origin_document_line_id uuid,
    ADD FOREIGN KEY (tenant_id,origin_document_line_id) REFERENCES inventory_document_line(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    ADD CHECK (warehouse_admission<>'VERIFIED' OR origin_document_line_id IS NOT NULL);
ALTER TABLE inventory_serialized_asset ADD COLUMN origin_document_line_id uuid,
    ADD FOREIGN KEY (tenant_id,origin_document_line_id) REFERENCES inventory_document_line(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    ADD CHECK (warehouse_admission<>'VERIFIED' OR origin_document_line_id IS NOT NULL);

CREATE TABLE inventory_operation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
    namespace varchar(120) NOT NULL CHECK (btrim(namespace)<>''), operation_key varchar(240) NOT NULL CHECK (btrim(operation_key)<>''),
    actor_id uuid NOT NULL, resource_id uuid NOT NULL, resource_scope text NOT NULL CHECK (btrim(resource_scope)<>''),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    document_id uuid NOT NULL, document_revision bigint NOT NULL CHECK (document_revision>=0),
    business_action varchar(64) NOT NULL CHECK (btrim(business_action)<>''),
    original_status integer NOT NULL CHECK (original_status BETWEEN 100 AND 599), original_body text NOT NULL,
    cutover_epoch bigint NOT NULL CHECK (cutover_epoch>=0), authority_epoch bigint NOT NULL CHECK (authority_epoch>=0),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,namespace,operation_key),
    CONSTRAINT inventory_operation_action_uq UNIQUE (tenant_id,document_id,business_action,document_revision),
    FOREIGN KEY (tenant_id,document_id) REFERENCES inventory_document(tenant_id,id)
);

CREATE TABLE inventory_outbox (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, operation_id uuid NOT NULL, document_id uuid NOT NULL,
    document_revision bigint NOT NULL CHECK (document_revision>=0), event_kind varchar(40) NOT NULL,
    payload text NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,operation_id,event_kind,document_id,document_revision),
    FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY (tenant_id,document_id) REFERENCES inventory_document(tenant_id,id),
    CHECK (event_kind IN ('RECEIVED','INSPECTED','PUTAWAY','RESERVED','RELEASED','RESERVATION_EXPIRED','PICKED','UNPICKED',
        'DISPATCHED','ACKNOWLEDGED','SEGMENT_SPLIT','USE_POSTED','USE_COMPENSATED','SETTLEMENT_VERIFIED',
        'RETURN_RECEIVED','ASSIGNMENT_OPENED','ASSIGNMENT_CLOSED','HANDOVER_ACCEPTED','TITLE_REACQUIRED','COUNT_POSTED','DISPOSED','CUTOVER_CHANGED'))
);

CREATE TABLE inventory_inbox (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, event_id uuid NOT NULL,
    consumer varchar(120) NOT NULL CHECK (btrim(consumer)<>''), operation_id uuid NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,event_id,consumer),
    FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id)
);

CREATE TABLE inventory_reservation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, document_line_id uuid NOT NULL,
    sku_id uuid NOT NULL, stock_identity_id uuid NOT NULL, lot_id uuid,
    base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')),
    location_id uuid NOT NULL, custodian_id uuid NOT NULL, custodian_kind varchar(24) NOT NULL,
    condition varchar(12) NOT NULL CHECK (condition IN ('SERVICEABLE','QUARANTINE','DAMAGED','SCRAP')),
    legal_owner varchar(8) NOT NULL CHECK (legal_owner IN ('ISP','CUSTOMER','UNKNOWN')),
    reserved_unpicked_base bigint NOT NULL CHECK (reserved_unpicked_base>=0), reserved_picked_base bigint NOT NULL CHECK (reserved_picked_base>=0),
    state varchar(12) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','DISPATCHED','RELEASED','EXPIRED')),
    submitted_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
    UNIQUE (tenant_id,id), UNIQUE NULLS NOT DISTINCT (tenant_id,document_line_id,stock_identity_id,lot_id,location_id,custodian_id,custodian_kind,condition,legal_owner),
    FOREIGN KEY (tenant_id,document_line_id) REFERENCES inventory_document_line(tenant_id,id),
    FOREIGN KEY (tenant_id,stock_identity_id,sku_id,base_unit) REFERENCES inventory_segment(tenant_id,id,sku_id,base_unit),
    FOREIGN KEY (tenant_id,lot_id,sku_id,base_unit) REFERENCES inventory_lot(tenant_id,id,sku_id,base_unit),
    FOREIGN KEY (tenant_id,location_id) REFERENCES inventory_location(tenant_id,id),
    CHECK (expires_at>submitted_at), CHECK (reserved_unpicked_base::numeric+reserved_picked_base::numeric<=9223372036854775807),
    CHECK (state='OPEN' OR (reserved_unpicked_base=0 AND reserved_picked_base=0))
);

CREATE TABLE inventory_material_plan (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, work_order_id uuid NOT NULL,
    plan_revision bigint NOT NULL CHECK (plan_revision>0), work_order_revision bigint NOT NULL CHECK (work_order_revision>=0),
    material_mode varchar(20) NOT NULL CHECK (material_mode IN ('NONE','MATERIAL_REQUIRED')),
    state varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK (state IN ('DRAFT','SUBMITTED')), reason varchar(1000),
    actor_id uuid NOT NULL, submitted_at timestamptz,
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,work_order_id,plan_revision),
    CHECK (material_mode<>'NONE' OR (reason IS NOT NULL AND btrim(reason)<>'')),
    CHECK ((state='SUBMITTED')=(submitted_at IS NOT NULL))
);

CREATE TABLE inventory_material_plan_line (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, plan_id uuid NOT NULL,
    line_number integer NOT NULL CHECK (line_number>0), sku_id uuid NOT NULL,
    quantity_base bigint NOT NULL CHECK (quantity_base>0), base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')),
    continuous_cut boolean NOT NULL DEFAULT true,
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,plan_id,line_number),
    FOREIGN KEY (tenant_id,plan_id) REFERENCES inventory_material_plan(tenant_id,id),
    FOREIGN KEY (tenant_id,sku_id,base_unit) REFERENCES inventory_sku(tenant_id,id,base_unit)
);

CREATE TABLE inventory_usage_snapshot (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, work_order_id uuid NOT NULL,
    use_revision bigint NOT NULL CHECK (use_revision>0), plan_id uuid NOT NULL,
    work_order_revision bigint NOT NULL CHECK (work_order_revision>=0), operation_id uuid NOT NULL,
    posting_ids uuid[] NOT NULL CHECK (cardinality(posting_ids)>0), frozen_snapshot text NOT NULL,
    compensates_snapshot_id uuid,
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,work_order_id,use_revision),
    FOREIGN KEY (tenant_id,plan_id) REFERENCES inventory_material_plan(tenant_id,id),
    FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY (tenant_id,compensates_snapshot_id) REFERENCES inventory_usage_snapshot(tenant_id,id),
    CHECK (compensates_snapshot_id IS NULL OR compensates_snapshot_id<>id)
);

CREATE TABLE inventory_inspection (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, document_line_id uuid NOT NULL, inspector_id uuid NOT NULL,
    accepted_base bigint NOT NULL CHECK (accepted_base>=0), rejected_base bigint NOT NULL CHECK (rejected_base>=0),
    base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')),
    disposition varchar(16) NOT NULL CHECK (disposition IN ('ACCEPTED','QUARANTINE','REPAIR','SUPPLIER_RETURN','SCRAP')),
    evidence_reference varchar(500) NOT NULL CHECK (btrim(evidence_reference)<>''), reason varchar(1000),
    operation_id uuid NOT NULL,
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,document_line_id,operation_id),
    FOREIGN KEY (tenant_id,document_line_id) REFERENCES inventory_document_line(tenant_id,id),
    FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    CHECK (accepted_base::numeric+rejected_base::numeric BETWEEN 1 AND 9223372036854775807)
);

CREATE TABLE inventory_warehouse_scope (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, user_id uuid NOT NULL, location_id uuid NOT NULL,
    state varchar(8) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','REVOKED')),
    granted_by uuid NOT NULL, authority_epoch bigint NOT NULL CHECK (authority_epoch>=0),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,user_id,location_id),
    FOREIGN KEY (tenant_id,location_id) REFERENCES inventory_location(tenant_id,id)
);

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_document','inventory_document_line','inventory_operation','inventory_outbox','inventory_inbox',
        'inventory_reservation','inventory_material_plan','inventory_material_plan_line','inventory_usage_snapshot','inventory_inspection','inventory_warehouse_scope'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0), ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now()',table_name);
        EXECUTE format('ALTER TABLE %I ADD FOREIGN KEY (tenant_id) REFERENCES tenant(id)',table_name);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_revision BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard()',table_name);
        IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_operation','inventory_outbox','inventory_inbox','inventory_usage_snapshot','inventory_inspection'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
END $$;

ALTER TABLE inventory_movement ADD CONSTRAINT inventory_movement_tenant_id_uq UNIQUE(tenant_id,id),
    ADD COLUMN warehouse_admission varchar(20) NOT NULL DEFAULT 'LEGACY_UNRESOLVED' CHECK (warehouse_admission IN ('LEGACY_UNRESOLVED','VERIFIED')),
    ADD COLUMN document_id uuid, ADD COLUMN document_revision bigint CHECK (document_revision>=0), ADD COLUMN operation_id uuid,
    ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0),
    ADD FOREIGN KEY (tenant_id,document_id) REFERENCES inventory_document(tenant_id,id),
    ADD FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    ADD FOREIGN KEY (tenant_id,compensates_movement_id) REFERENCES inventory_movement(tenant_id,id) NOT VALID;
ALTER TABLE inventory_movement ALTER COLUMN warehouse_admission SET DEFAULT 'VERIFIED',
    ADD CHECK (warehouse_admission<>'VERIFIED' OR (document_id IS NOT NULL AND document_revision IS NOT NULL AND operation_id IS NOT NULL));
CREATE TRIGGER warehouse_movement_admission BEFORE INSERT OR UPDATE ON inventory_movement FOR EACH ROW EXECUTE FUNCTION warehouse_admission_guard();

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_movement_leg','inventory_balance_projection','inventory_fulfillment_effect','inventory_customer_material_fact'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN quantity_base bigint CHECK (quantity_base>=0), ADD COLUMN base_unit varchar(2) CHECK (base_unit IN (''EA'',''MM'')), ADD COLUMN stock_identity_id uuid, ADD COLUMN lot_id uuid, ADD COLUMN warehouse_admission varchar(20) NOT NULL DEFAULT ''LEGACY_UNRESOLVED'' CHECK (warehouse_admission IN (''LEGACY_UNRESOLVED'',''VERIFIED'')), ADD COLUMN revision bigint NOT NULL DEFAULT 0 CHECK (revision>=0)',table_name);
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I UNIQUE (tenant_id,id)',table_name,table_name||'_tenant_id_uq');
        EXECUTE format('ALTER TABLE %I ADD FOREIGN KEY (tenant_id,stock_identity_id) REFERENCES inventory_segment(tenant_id,id), ADD FOREIGN KEY (tenant_id,lot_id) REFERENCES inventory_lot(tenant_id,id)',table_name);
        EXECUTE format('ALTER TABLE %I ADD CHECK (warehouse_admission<>''VERIFIED'' OR (quantity_base IS NOT NULL AND base_unit IS NOT NULL AND stock_identity_id IS NOT NULL))',table_name);
        EXECUTE format('ALTER TABLE %I ALTER COLUMN warehouse_admission SET DEFAULT ''VERIFIED''',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_admission BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_admission_guard()',table_name);
    END LOOP;
END $$;

UPDATE inventory_movement_leg SET quantity_base=quantity::bigint,base_unit='EA' WHERE serialized;
ALTER TABLE inventory_movement_leg ALTER COLUMN quantity DROP NOT NULL,
    ADD COLUMN condition varchar(12) CHECK (condition IN ('SERVICEABLE','QUARANTINE','DAMAGED','SCRAP')),
    ADD COLUMN legal_owner varchar(8) CHECK (legal_owner IN ('ISP','CUSTOMER','UNKNOWN')),
    ADD COLUMN document_line_id uuid,
    ADD FOREIGN KEY (tenant_id,document_line_id) REFERENCES inventory_document_line(tenant_id,id),
    ADD FOREIGN KEY (tenant_id,movement_id) REFERENCES inventory_movement(tenant_id,id) NOT VALID,
    ADD FOREIGN KEY (tenant_id,stock_identity_id,sku_id,base_unit) REFERENCES inventory_segment(tenant_id,id,sku_id,base_unit),
    ADD FOREIGN KEY (tenant_id,location_id) REFERENCES inventory_location(tenant_id,id) NOT VALID,
    ADD CHECK (quantity_base IS NULL OR quantity_base>0),
    ADD CHECK (NOT serialized OR quantity_base IS NULL OR (quantity_base=1 AND base_unit='EA')),
    ADD CHECK (warehouse_admission<>'VERIFIED' OR (document_line_id IS NOT NULL AND condition IS NOT NULL AND legal_owner IS NOT NULL));

ALTER TABLE inventory_balance_projection ALTER COLUMN quantity DROP NOT NULL,
    ADD COLUMN serialized boolean NOT NULL DEFAULT false,
    ADD COLUMN condition varchar(12) CHECK (condition IN ('SERVICEABLE','QUARANTINE','DAMAGED','SCRAP')),
    ADD COLUMN legal_owner varchar(8) CHECK (legal_owner IN ('ISP','CUSTOMER','UNKNOWN')),
    ADD FOREIGN KEY (tenant_id,stock_identity_id,sku_id,base_unit) REFERENCES inventory_segment(tenant_id,id,sku_id,base_unit),
    ADD FOREIGN KEY (tenant_id,location_id) REFERENCES inventory_location(tenant_id,id) NOT VALID,
    ADD CHECK (warehouse_admission<>'VERIFIED' OR (condition IS NOT NULL AND legal_owner IS NOT NULL)),
    DROP CONSTRAINT inventory_balance_dimension_uq;
CREATE UNIQUE INDEX inventory_balance_legacy_dimension_uq ON inventory_balance_projection(tenant_id,item_id,location_id,custody_owner_id,custody_owner_kind,status) WHERE warehouse_admission='LEGACY_UNRESOLVED';
CREATE UNIQUE INDEX inventory_balance_stock_dimension_uq ON inventory_balance_projection
    (tenant_id,sku_id,stock_identity_id,lot_id,location_id,custody_owner_id,custody_owner_kind,condition,legal_owner)
    NULLS NOT DISTINCT WHERE warehouse_admission='VERIFIED';
CREATE UNIQUE INDEX inventory_balance_serial_position_uq ON inventory_balance_projection(tenant_id,stock_identity_id)
    WHERE warehouse_admission='VERIFIED' AND serialized AND quantity_base>0;

ALTER TABLE inventory_fulfillment_effect ALTER COLUMN quantity DROP NOT NULL,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN operation_id uuid, ADD COLUMN posting_id uuid, ADD COLUMN use_revision bigint CHECK (use_revision>=0),
    ADD FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    ADD FOREIGN KEY (tenant_id,posting_id) REFERENCES inventory_movement(tenant_id,id),
    ADD CHECK (warehouse_admission<>'VERIFIED' OR (operation_id IS NOT NULL AND posting_id IS NOT NULL AND use_revision IS NOT NULL));
ALTER TABLE inventory_customer_material_fact ALTER COLUMN quantity DROP NOT NULL,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN posting_id uuid, ADD COLUMN use_revision bigint CHECK (use_revision>=0), ADD COLUMN compensation_id uuid,
    ADD FOREIGN KEY (tenant_id,posting_id) REFERENCES inventory_movement(tenant_id,id),
    ADD FOREIGN KEY (tenant_id,compensation_id) REFERENCES inventory_customer_material_fact(tenant_id,id),
    ADD CHECK (warehouse_admission<>'VERIFIED' OR (posting_id IS NOT NULL AND use_revision IS NOT NULL));

CREATE INDEX inventory_document_workorder_idx ON inventory_document(tenant_id,work_order_id,created_at,id);
CREATE INDEX inventory_document_state_idx ON inventory_document(tenant_id,kind,state,created_at,id);
CREATE INDEX inventory_line_stock_idx ON inventory_document_line(tenant_id,stock_identity_id,lot_id);
CREATE INDEX inventory_outbox_recorded_idx ON inventory_outbox(tenant_id,recorded_at,id);
CREATE INDEX inventory_reservation_expiry_idx ON inventory_reservation(tenant_id,expires_at,id) WHERE state='OPEN';
CREATE INDEX inventory_reservation_stock_idx ON inventory_reservation(tenant_id,stock_identity_id,location_id);

CREATE FUNCTION warehouse_document_guard() RETURNS trigger LANGUAGE plpgsql AS $$
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
        WHEN 'DEMAND' THEN (OLD.state,NEW.state) IN (('DRAFT','SUBMITTED'),('DRAFT','CANCELLED'),('SUBMITTED','PART_RESERVED'),('SUBMITTED','RESERVED'),('PART_RESERVED','RESERVED'),('PART_RESERVED','PART_ISSUED'),('RESERVED','PART_ISSUED'),('RESERVED','ISSUED'),('PART_ISSUED','ISSUED'),('PART_ISSUED','SETTLING'),('ISSUED','SETTLING'),('SETTLING','CLOSED'),('SUBMITTED','CANCELLED'),('PART_RESERVED','CANCELLED'),('RESERVED','CANCELLED'))
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

CREATE FUNCTION warehouse_line_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_state text; parent_kind text; parent_revision bigint; parent_id uuid; owner_tenant uuid;
BEGIN
    IF TG_OP='DELETE' THEN parent_id:=OLD.document_id; owner_tenant:=OLD.tenant_id;
    ELSE parent_id:=NEW.document_id; owner_tenant:=NEW.tenant_id; END IF;
    IF TG_OP='UPDATE' AND NEW.document_id<>OLD.document_id THEN RAISE EXCEPTION 'line parent is immutable' USING ERRCODE='23514'; END IF;
    SELECT state,kind,revision INTO parent_state,parent_kind,parent_revision FROM inventory_document
        WHERE tenant_id=owner_tenant AND id=parent_id FOR UPDATE;
    IF parent_state IS DISTINCT FROM 'DRAFT' THEN RAISE EXCEPTION 'posted lines are immutable' USING ERRCODE='23514'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NEW.document_revision<>parent_revision THEN RAISE EXCEPTION 'stale document line revision' USING ERRCODE='40001'; END IF;
    IF NEW.tracking IS DISTINCT FROM (SELECT tracking FROM inventory_sku WHERE tenant_id=NEW.tenant_id AND id=NEW.sku_id) THEN
        RAISE EXCEPTION 'SKU tracking mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_plan_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.state<>'DRAFT' THEN RAISE EXCEPTION 'plans must start as drafts' USING ERRCODE='23514'; END IF;
        RETURN NEW;
    END IF;
    IF OLD.state='SUBMITTED' THEN RAISE EXCEPTION 'submitted plan is immutable' USING ERRCODE='23514'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NEW.state='SUBMITTED' AND ((NEW.material_mode='NONE' AND EXISTS (SELECT FROM inventory_material_plan_line WHERE tenant_id=NEW.tenant_id AND plan_id=NEW.id)) OR
        (NEW.material_mode='MATERIAL_REQUIRED' AND NOT EXISTS (SELECT FROM inventory_material_plan_line WHERE tenant_id=NEW.tenant_id AND plan_id=NEW.id))) THEN
        RAISE EXCEPTION 'material mode and lines disagree' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_plan_line_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_id uuid; owner_tenant uuid; parent_state text; parent_mode text;
BEGIN
    IF TG_OP='DELETE' THEN parent_id:=OLD.plan_id; owner_tenant:=OLD.tenant_id;
    ELSE parent_id:=NEW.plan_id; owner_tenant:=NEW.tenant_id; END IF;
    IF TG_OP='UPDATE' AND NEW.plan_id<>OLD.plan_id THEN RAISE EXCEPTION 'plan line parent immutable' USING ERRCODE='23514'; END IF;
    SELECT state,material_mode INTO parent_state,parent_mode FROM inventory_material_plan WHERE tenant_id=owner_tenant AND id=parent_id FOR UPDATE;
    IF parent_state IS DISTINCT FROM 'DRAFT' OR parent_mode='NONE' THEN RAISE EXCEPTION 'plan lines are frozen or materialless' USING ERRCODE='23514'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_stock_dimension_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE identity_lot uuid; identity_kind text; identity_quantity bigint;
BEGIN
    IF NEW.stock_identity_id IS NOT NULL THEN
        SELECT lot_id,kind,quantity_base INTO identity_lot,identity_kind,identity_quantity FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=NEW.stock_identity_id;
        IF NOT FOUND OR identity_lot IS DISTINCT FROM NEW.lot_id THEN RAISE EXCEPTION 'stock identity lot mismatch' USING ERRCODE='23514'; END IF;
        IF TG_TABLE_NAME='inventory_balance_projection' THEN
            NEW.serialized := identity_kind='SERIAL';
            IF NEW.quantity_base>identity_quantity THEN RAISE EXCEPTION 'position exceeds physical piece quantity' USING ERRCODE='23514'; END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_authorization_epoch_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.epoch<>OLD.epoch+1 THEN RAISE EXCEPTION 'authorization epoch must advance once' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_authorization_epoch BEFORE UPDATE ON iam_authorization_epoch FOR EACH ROW EXECUTE FUNCTION warehouse_authorization_epoch_guard();
CREATE TRIGGER warehouse_authorization_append_only BEFORE DELETE ON iam_authorization_epoch FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

ALTER TABLE app_user ADD CONSTRAINT warehouse_user_tenant_id_uq UNIQUE (tenant_id,id);
ALTER TABLE area ADD CONSTRAINT warehouse_area_tenant_id_uq UNIQUE (tenant_id,id);
ALTER TABLE inventory_warehouse_scope ADD FOREIGN KEY (tenant_id,user_id) REFERENCES app_user(tenant_id,id);
ALTER TABLE inventory_location ADD FOREIGN KEY (tenant_id,area_id) REFERENCES area(tenant_id,id),
    ADD CONSTRAINT warehouse_location_kind_ck CHECK (kind IN ('WAREHOUSE','BIN','VEHICLE','TECHNICIAN','CUSTOMER_SITE','QUARANTINE','LOST','DISPOSED','TRANSIT')) NOT VALID;

CREATE FUNCTION warehouse_new_empty_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state='ENFORCED' AND NEW.initialization_kind='NEW_EMPTY' AND
       (EXISTS (SELECT FROM inventory_document WHERE tenant_id=NEW.tenant_id) OR
        EXISTS (SELECT FROM inventory_movement WHERE tenant_id=NEW.tenant_id) OR
        EXISTS (SELECT FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id) OR
        EXISTS (SELECT FROM inventory_customer_material_fact WHERE tenant_id=NEW.tenant_id) OR
        EXISTS (SELECT FROM inventory_fulfillment_effect WHERE tenant_id=NEW.tenant_id) OR
        EXISTS (SELECT FROM onu WHERE tenant_id=NEW.tenant_id)) THEN
        RAISE EXCEPTION 'new tenant is not empty' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_new_empty BEFORE INSERT ON inventory_tenant_cutover FOR EACH ROW EXECUTE FUNCTION warehouse_new_empty_guard();

CREATE FUNCTION warehouse_reservation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' AND NEW.state<>'OPEN' THEN RAISE EXCEPTION 'reservation must start open' USING ERRCODE='23514'; END IF;
    IF TG_OP='UPDATE' AND (OLD.state<>'OPEN' OR (NEW.state='EXPIRED' AND OLD.reserved_picked_base>0)) THEN
        RAISE EXCEPTION 'closed or picked reservation cannot expire or change' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_reservation_lifecycle BEFORE INSERT OR UPDATE ON inventory_reservation FOR EACH ROW EXECUTE FUNCTION warehouse_reservation_guard();
CREATE TRIGGER warehouse_reservation_append_only BEFORE DELETE ON inventory_reservation FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE FUNCTION warehouse_inspection_quantity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE line_quantity bigint; inspected numeric;
BEGIN
    SELECT quantity_base INTO line_quantity FROM inventory_document_line WHERE tenant_id=NEW.tenant_id AND id=NEW.document_line_id FOR UPDATE;
    SELECT coalesce(sum(accepted_base::numeric+rejected_base::numeric),0) INTO inspected FROM inventory_inspection WHERE tenant_id=NEW.tenant_id AND document_line_id=NEW.document_line_id;
    IF inspected+NEW.accepted_base::numeric+NEW.rejected_base::numeric>line_quantity THEN
        RAISE EXCEPTION 'inspection exceeds receipt quantity' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_inspection_quantity BEFORE INSERT ON inventory_inspection FOR EACH ROW EXECUTE FUNCTION warehouse_inspection_quantity_guard();

CREATE FUNCTION warehouse_sku_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.tracking,NEW.base_unit) IS DISTINCT FROM (OLD.tracking,OLD.base_unit) AND
        (EXISTS (SELECT FROM inventory_document_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
                 WHERE line.tenant_id=OLD.tenant_id AND line.sku_id=OLD.id AND document.state<>'DRAFT') OR
         EXISTS (SELECT FROM inventory_segment WHERE tenant_id=OLD.tenant_id AND sku_id=OLD.id)) THEN
        RAISE EXCEPTION 'posted SKU units are immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_applied_movement_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state='APPLIED' THEN RAISE EXCEPTION 'posted movement is append-only' USING ERRCODE='23514'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER warehouse_document_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON inventory_document FOR EACH ROW EXECUTE FUNCTION warehouse_document_guard();
CREATE TRIGGER warehouse_line_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON inventory_document_line FOR EACH ROW EXECUTE FUNCTION warehouse_line_guard();
CREATE TRIGGER warehouse_plan_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON inventory_material_plan FOR EACH ROW EXECUTE FUNCTION warehouse_plan_guard();
CREATE TRIGGER warehouse_plan_line_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON inventory_material_plan_line FOR EACH ROW EXECUTE FUNCTION warehouse_plan_line_guard();
CREATE TRIGGER warehouse_sku_units BEFORE UPDATE ON inventory_sku FOR EACH ROW EXECUTE FUNCTION warehouse_sku_guard();
CREATE TRIGGER warehouse_movement_append_only BEFORE UPDATE OR DELETE ON inventory_movement FOR EACH ROW EXECUTE FUNCTION warehouse_applied_movement_guard();
CREATE TRIGGER warehouse_movement_revision BEFORE UPDATE ON inventory_movement FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard();
CREATE TRIGGER warehouse_balance_revision BEFORE UPDATE ON inventory_balance_projection FOR EACH ROW EXECUTE FUNCTION warehouse_revision_guard();

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_document_line','inventory_reservation','inventory_balance_projection','inventory_movement_leg'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_stock_dimension BEFORE INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_stock_dimension_guard()',table_name);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_movement_leg','inventory_fulfillment_effect','inventory_customer_material_fact','inventory_lot'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
END $$;

ALTER TABLE inventory_operation ADD UNIQUE (tenant_id,id,document_id,document_revision);
ALTER TABLE inventory_outbox ADD FOREIGN KEY (tenant_id,operation_id,document_id,document_revision)
    REFERENCES inventory_operation(tenant_id,id,document_id,document_revision);
ALTER TABLE inventory_document_line ADD UNIQUE (tenant_id,id,sku_id,base_unit), ADD UNIQUE (tenant_id,id,base_unit);
ALTER TABLE inventory_reservation ADD FOREIGN KEY (tenant_id,document_line_id,sku_id,base_unit)
    REFERENCES inventory_document_line(tenant_id,id,sku_id,base_unit);
ALTER TABLE inventory_inspection ADD FOREIGN KEY (tenant_id,document_line_id,base_unit)
    REFERENCES inventory_document_line(tenant_id,id,base_unit);

CREATE FUNCTION warehouse_origin_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_line inventory_document_line; source_document inventory_document; target_sku uuid;
BEGIN
    IF NEW.warehouse_admission<>'VERIFIED' THEN RETURN NEW; END IF;
    IF TG_OP='UPDATE' AND NEW.origin_document_line_id IS DISTINCT FROM OLD.origin_document_line_id THEN
        RAISE EXCEPTION 'warehouse origin is immutable' USING ERRCODE='23514';
    END IF;
    target_sku := CASE WHEN TG_TABLE_NAME='inventory_lot' THEN to_jsonb(NEW)->>'sku_id' ELSE to_jsonb(NEW)->>'warehouse_sku_id' END;
    SELECT * INTO source_line FROM inventory_document_line WHERE tenant_id=NEW.tenant_id AND id=NEW.origin_document_line_id;
    SELECT * INTO source_document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=source_line.document_id;
    IF source_line.sku_id IS DISTINCT FROM target_sku OR source_line.base_unit IS DISTINCT FROM NEW.base_unit OR
       NOT ((source_document.kind='RECEIPT' AND source_document.state IN ('RECEIVED_IN_INSPECTION','PUTAWAY','CLOSED')) OR
            (source_document.kind='OPENING_BALANCE' AND source_document.state IN ('POSTED','CLOSED'))) OR source_document.id IS NULL THEN
        RAISE EXCEPTION 'verified stock requires posted receipt or approved opening origin' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_lot_origin AFTER INSERT OR UPDATE ON inventory_lot DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_origin_guard();
CREATE CONSTRAINT TRIGGER warehouse_asset_origin AFTER INSERT OR UPDATE ON inventory_serialized_asset DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_origin_guard();

CREATE FUNCTION warehouse_usage_postings_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE posting uuid;
BEGIN
    FOREACH posting IN ARRAY NEW.posting_ids LOOP
        IF posting IS NULL OR NOT EXISTS (SELECT FROM inventory_movement WHERE tenant_id=NEW.tenant_id AND id=posting AND state='APPLIED') THEN
            RAISE EXCEPTION 'usage posting is missing or belongs to another tenant' USING ERRCODE='23503';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_usage_postings AFTER INSERT ON inventory_usage_snapshot DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_usage_postings_guard();

CREATE FUNCTION warehouse_master_archive_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state='ARCHIVED' AND NEW.state<>'ARCHIVED' THEN RAISE EXCEPTION 'archived master cannot be reopened' USING ERRCODE='23514'; END IF;
    IF NEW.state='ARCHIVED' AND OLD.state<>'ARCHIVED' THEN
        IF TG_TABLE_NAME='inventory_sku' AND
           (EXISTS (SELECT FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND sku_id=NEW.id AND coalesce(quantity_base,quantity)>0) OR
            EXISTS (SELECT FROM inventory_document_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
                    WHERE line.tenant_id=NEW.tenant_id AND line.sku_id=NEW.id AND document.state NOT IN ('CLOSED','CANCELLED'))) THEN
            RAISE EXCEPTION 'SKU has stock or open references' USING ERRCODE='23514';
        ELSIF TG_TABLE_NAME='inventory_location' AND
           (EXISTS (SELECT FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND location_id=NEW.id AND coalesce(quantity_base,quantity)>0) OR
            EXISTS (SELECT FROM inventory_reservation WHERE tenant_id=NEW.tenant_id AND location_id=NEW.id AND state='OPEN') OR
            EXISTS (SELECT FROM inventory_document_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
                    WHERE line.tenant_id=NEW.tenant_id AND (line.location_id=NEW.id OR line.destination_location_id=NEW.id) AND document.state NOT IN ('CLOSED','CANCELLED'))) THEN
            RAISE EXCEPTION 'location has stock or open references' USING ERRCODE='23514';
        ELSIF TG_TABLE_NAME='inventory_supplier' AND EXISTS
            (SELECT FROM inventory_document WHERE tenant_id=NEW.tenant_id AND supplier_id=NEW.id AND state NOT IN ('CLOSED','CANCELLED')) THEN
            RAISE EXCEPTION 'supplier has open references' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_sku_archive BEFORE UPDATE ON inventory_sku FOR EACH ROW EXECUTE FUNCTION warehouse_master_archive_guard();
CREATE TRIGGER warehouse_location_archive BEFORE UPDATE ON inventory_location FOR EACH ROW EXECUTE FUNCTION warehouse_master_archive_guard();
CREATE TRIGGER warehouse_supplier_archive BEFORE UPDATE ON inventory_supplier FOR EACH ROW EXECUTE FUNCTION warehouse_master_archive_guard();

DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE INSERT,UPDATE,DELETE ON inventory_identity_candidate FROM warehouse_app;
    END IF;
END $$;
