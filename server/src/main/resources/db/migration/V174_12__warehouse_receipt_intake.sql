CREATE TABLE inventory_receipt_intake (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    source_location_id uuid NOT NULL, inspection_location_id uuid NOT NULL,
    snapshot text NOT NULL,
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY (tenant_id,source_location_id) REFERENCES inventory_location(tenant_id,id),
    FOREIGN KEY (tenant_id,inspection_location_id) REFERENCES inventory_location(tenant_id,id),
    CHECK (source_location_id<>inspection_location_id)
);

CREATE TABLE inventory_receipt_evidence (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), document_id uuid NOT NULL,
    object_key text NOT NULL, sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    content_type varchar(80) NOT NULL CHECK (content_type IN ('image/png','image/jpeg','application/pdf')),
    size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 15728640),
    actor_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,object_key),
    FOREIGN KEY (tenant_id,document_id) REFERENCES inventory_document(tenant_id,id),
    CHECK (object_key=tenant_id::text||'/warehouse/receipts/'||document_id::text||'/'||id::text)
);

CREATE TABLE inventory_receipt_disposition (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), inspection_id uuid NOT NULL,
    source_segment_id uuid NOT NULL, segment_id uuid NOT NULL,
    quantity_base bigint NOT NULL CHECK (quantity_base>0),
    base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')),
    disposition varchar(16) NOT NULL CHECK (disposition IN ('ACCEPTED','QUARANTINE','SUPPLIER_RETURN')),
    evidence_id uuid NOT NULL,
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,segment_id),
    FOREIGN KEY (tenant_id,inspection_id) REFERENCES inventory_inspection(tenant_id,id),
    FOREIGN KEY (tenant_id,source_segment_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY (tenant_id,segment_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY (tenant_id,evidence_id) REFERENCES inventory_receipt_evidence(tenant_id,id)
);
CREATE INDEX inventory_receipt_evidence_document ON inventory_receipt_evidence(tenant_id,document_id);
CREATE INDEX inventory_receipt_disposition_inspection ON inventory_receipt_disposition(tenant_id,inspection_id);

CREATE FUNCTION warehouse_receipt_intake_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE document_state text; document_kind text;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'receipt snapshot cannot be deleted' USING ERRCODE='23514'; END IF;
    IF TG_OP='UPDATE' AND (NEW.tenant_id,NEW.id) IS DISTINCT FROM (OLD.tenant_id,OLD.id) THEN
        RAISE EXCEPTION 'receipt identity is immutable' USING ERRCODE='23514';
    END IF;
    SELECT state,kind INTO document_state,document_kind FROM inventory_document
        WHERE tenant_id=NEW.tenant_id AND id=NEW.id FOR UPDATE;
    IF document_state IS DISTINCT FROM 'DRAFT' OR document_kind IS DISTINCT FROM 'RECEIPT' THEN
        RAISE EXCEPTION 'receipt intake requires draft supplier receipt' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_receipt_intake_guard BEFORE INSERT OR UPDATE OR DELETE ON inventory_receipt_intake
    FOR EACH ROW EXECUTE FUNCTION warehouse_receipt_intake_guard();

CREATE FUNCTION warehouse_receipt_disposition_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE checked_inspection inventory_inspection; checked_line inventory_document_line; checked_segment inventory_segment;
    allocated numeric; capacity bigint;
BEGIN
    SELECT * INTO checked_inspection FROM inventory_inspection WHERE tenant_id=NEW.tenant_id AND id=NEW.inspection_id;
    SELECT * INTO checked_line FROM inventory_document_line
        WHERE tenant_id=NEW.tenant_id AND id=checked_inspection.document_line_id FOR UPDATE;
    SELECT * INTO checked_segment FROM inventory_segment WHERE tenant_id=NEW.tenant_id AND id=NEW.segment_id;
    IF checked_line.id IS NULL OR checked_segment.id IS NULL OR checked_segment.state<>'ACTIVE' OR
        checked_segment.warehouse_admission<>'VERIFIED' OR checked_segment.sku_id<>checked_line.sku_id OR
        checked_segment.lot_id IS DISTINCT FROM checked_line.lot_id OR checked_segment.base_unit<>NEW.base_unit OR
        checked_line.base_unit<>NEW.base_unit OR checked_inspection.base_unit<>NEW.base_unit OR
        checked_segment.quantity_base<>NEW.quantity_base OR
        (checked_segment.id<>NEW.source_segment_id AND checked_segment.parent_segment_id IS DISTINCT FROM NEW.source_segment_id) OR
        NOT EXISTS (SELECT FROM inventory_receipt_evidence WHERE tenant_id=NEW.tenant_id AND id=NEW.evidence_id AND document_id=checked_line.document_id) THEN
        RAISE EXCEPTION 'inspection disposition binding mismatch' USING ERRCODE='23514';
    END IF;
    capacity := CASE WHEN NEW.disposition='ACCEPTED' THEN checked_inspection.accepted_base ELSE checked_inspection.rejected_base END;
    SELECT coalesce(sum(quantity_base::numeric),0) INTO allocated FROM inventory_receipt_disposition
        WHERE tenant_id=NEW.tenant_id AND inspection_id=NEW.inspection_id AND (disposition='ACCEPTED')=(NEW.disposition='ACCEPTED');
    IF allocated+NEW.quantity_base>capacity THEN RAISE EXCEPTION 'inspection disposition exceeds decision' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_receipt_disposition_guard BEFORE INSERT ON inventory_receipt_disposition
    FOR EACH ROW EXECUTE FUNCTION warehouse_receipt_disposition_guard();

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_receipt_intake','inventory_receipt_evidence','inventory_receipt_disposition'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_receipt_evidence','inventory_receipt_disposition'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
END $$;
