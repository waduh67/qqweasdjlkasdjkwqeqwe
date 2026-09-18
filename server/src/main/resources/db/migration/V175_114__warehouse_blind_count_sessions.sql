CREATE TABLE inventory_count_scope (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, location_id uuid NOT NULL,
    partial_location boolean NOT NULL CHECK(partial_location), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,location_id) REFERENCES inventory_location(tenant_id,id)
);
CREATE TABLE inventory_count_entry (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, document_id uuid NOT NULL, balance_id uuid NOT NULL, counter_id uuid NOT NULL,
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,document_id,balance_id),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_document_line(tenant_id,id),
    FOREIGN KEY(tenant_id,document_id) REFERENCES inventory_count_scope(tenant_id,id),
    FOREIGN KEY(tenant_id,balance_id) REFERENCES inventory_balance_projection(tenant_id,id),
    FOREIGN KEY(tenant_id,counter_id) REFERENCES app_user(tenant_id,id)
);
CREATE TABLE inventory_count_round (
    tenant_id uuid NOT NULL, document_id uuid NOT NULL, document_revision bigint NOT NULL CHECK(document_revision>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(tenant_id,document_id,document_revision),
    FOREIGN KEY(tenant_id,document_id) REFERENCES inventory_count_scope(tenant_id,id)
);
CREATE INDEX warehouse_count_counter_idx ON inventory_count_entry(tenant_id,counter_id,document_id);
CREATE INDEX warehouse_count_scope_location_idx ON inventory_count_scope(tenant_id,location_id,created_at,id);

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_count_scope','inventory_count_entry','inventory_count_round'] LOOP
        EXECUTE format('ALTER TABLE %I ADD FOREIGN KEY(tenant_id) REFERENCES tenant(id)',table_name);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_count_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
END $$;

CREATE FUNCTION warehouse_count_evidence_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE document inventory_document; balance inventory_balance_projection; entry inventory_count_entry;
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    IF TG_OP<>'INSERT' THEN
        IF OLD.document_id IS NOT NULL OR (TG_OP='UPDATE' AND NEW.document_id IS NOT NULL) THEN
            RAISE EXCEPTION 'count observations are immutable; append a recount' USING ERRCODE='23514';
        END IF;
        RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF NEW.document_id IS NULL THEN RETURN NEW; END IF;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id FOR UPDATE;
    SELECT * INTO entry FROM inventory_count_entry WHERE tenant_id=NEW.tenant_id AND document_id=NEW.document_id AND balance_id=NEW.balance_id;
    SELECT * INTO balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND id=NEW.balance_id FOR UPDATE;
    IF document.kind IS DISTINCT FROM 'COUNT' OR document.state IS DISTINCT FROM 'COUNTING' OR entry.id IS NULL OR balance.id IS NULL
        OR entry.counter_id IS DISTINCT FROM NEW.counter_id OR balance.warehouse_admission IS DISTINCT FROM 'VERIFIED'
        OR (NEW.location_id,NEW.item_id,NEW.sku_id,NEW.stock_identity_id,NEW.custodian_id,NEW.base_unit,NEW.prior_quantity_base,NEW.observed_dimension_revision)
            IS DISTINCT FROM (balance.location_id,balance.stock_identity_id,balance.sku_id,balance.stock_identity_id,balance.custody_owner_id,balance.base_unit,balance.quantity_base,balance.revision)
        OR NEW.document_revision IS DISTINCT FROM (SELECT max(document_revision) FROM inventory_count_round WHERE tenant_id=NEW.tenant_id AND document_id=NEW.document_id)
        OR NEW.approval_id IS NOT NULL OR NEW.posting_operation_id IS NOT NULL OR NEW.approver_id IS NOT NULL OR NEW.closed_at IS NOT NULL
        OR NEW.discrepancy_state<>'OBSERVED' THEN
        RAISE EXCEPTION 'count observation requires its assigned current dimension and round' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_count_evidence_immutable BEFORE INSERT OR UPDATE OR DELETE ON inventory_cycle_count
    FOR EACH ROW EXECUTE FUNCTION warehouse_count_evidence_guard();

CREATE FUNCTION warehouse_count_scope_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE document inventory_document; source_id uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    source_id:=(to_jsonb(NEW)->>CASE WHEN TG_TABLE_NAME='inventory_count_scope' THEN 'id' ELSE 'document_id' END)::uuid;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=source_id FOR UPDATE;
    IF document.kind IS DISTINCT FROM 'COUNT' OR document.state IS DISTINCT FROM
        (CASE WHEN TG_TABLE_NAME='inventory_count_round' THEN 'COUNTING' ELSE 'DRAFT' END) THEN
        RAISE EXCEPTION 'count scope requires its owner document lifecycle' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='inventory_count_round' THEN
        IF (to_jsonb(NEW)->>'document_revision')::bigint<>document.revision THEN
            RAISE EXCEPTION 'count round revision mismatch' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='inventory_count_entry' THEN
        IF NOT EXISTS(SELECT FROM inventory_document_line line JOIN inventory_balance_projection balance
            ON balance.tenant_id=line.tenant_id AND balance.id=(to_jsonb(NEW)->>'balance_id')::uuid
            JOIN inventory_count_scope scope ON scope.tenant_id=line.tenant_id AND scope.id=line.document_id
            WHERE line.tenant_id=NEW.tenant_id AND line.id=NEW.id AND line.document_id=source_id
                AND (line.stock_identity_id,line.sku_id,line.lot_id,line.location_id,line.custodian_id,line.custodian_kind,line.condition,line.legal_owner)
                    IS NOT DISTINCT FROM (balance.stock_identity_id,balance.sku_id,balance.lot_id,balance.location_id,balance.custody_owner_id,balance.custody_owner_kind,balance.condition,balance.legal_owner)
                AND balance.location_id=scope.location_id AND balance.warehouse_admission='VERIFIED') THEN
            RAISE EXCEPTION 'count entry dimension mismatch' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_count_scope_insert BEFORE INSERT ON inventory_count_scope FOR EACH ROW EXECUTE FUNCTION warehouse_count_scope_guard();
CREATE TRIGGER warehouse_count_entry_insert BEFORE INSERT ON inventory_count_entry FOR EACH ROW EXECUTE FUNCTION warehouse_count_scope_guard();
CREATE TRIGGER warehouse_count_round_insert BEFORE INSERT ON inventory_count_round FOR EACH ROW EXECUTE FUNCTION warehouse_count_scope_guard();

DO $$ DECLARE definition text; original text := '(''APPROVED'',''RECOUNT_REQUIRED''),(''RECOUNT_REQUIRED'',''COUNTING'')';
BEGIN
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF strpos(definition,original)=0 THEN RAISE EXCEPTION 'count lifecycle extension point missing'; END IF;
    EXECUTE replace(definition,original,original || ',(''COUNTING'',''RECOUNT_REQUIRED''),(''SUBMITTED'',''RECOUNT_REQUIRED'')');
END $$;
