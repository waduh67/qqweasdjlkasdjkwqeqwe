CREATE TABLE inventory_material_obligation (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), work_order_id uuid NOT NULL,
    stock_identity_id uuid NOT NULL, issued_base bigint NOT NULL CHECK (issued_base>0),
    base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')), due_at timestamptz NOT NULL,
    UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_issue_line(tenant_id,id),
    FOREIGN KEY (tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY (tenant_id,stock_identity_id) REFERENCES inventory_segment(tenant_id,id)
);
ALTER TABLE inventory_material_obligation ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_material_obligation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_material_obligation
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_material_obligation
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE INDEX inventory_material_obligation_due_idx ON inventory_material_obligation(tenant_id,work_order_id,due_at);

INSERT INTO inventory_material_obligation(id,tenant_id,work_order_id,stock_identity_id,issued_base,base_unit,due_at)
SELECT line.id,line.tenant_id,document.work_order_id,line.stock_identity_id,line.quantity_base,line.base_unit,
    operation.created_at+interval '24 hours'
FROM inventory_document_line line JOIN inventory_issue_line issue ON issue.tenant_id=line.tenant_id AND issue.id=line.id
JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
JOIN inventory_operation operation ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id
    AND operation.document_revision=2
WHERE document.state IN ('DISPATCHED','PART_RECEIVED','RECEIVED');

CREATE FUNCTION warehouse_material_obligation_origin_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_document' THEN
        IF NEW.kind='ISSUE' AND NEW.state='DISPATCHED' THEN
            INSERT INTO inventory_material_obligation(id,tenant_id,work_order_id,stock_identity_id,issued_base,base_unit,due_at)
            SELECT line.id,line.tenant_id,NEW.work_order_id,line.stock_identity_id,line.quantity_base,line.base_unit,operation.created_at+interval '24 hours'
            FROM inventory_document_line line JOIN inventory_operation operation ON operation.tenant_id=line.tenant_id
                AND operation.document_id=NEW.id AND operation.document_revision=2
            WHERE line.tenant_id=NEW.tenant_id AND line.document_id=NEW.id;
        END IF;
    ELSIF NOT EXISTS (SELECT FROM inventory_document_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
        JOIN inventory_operation operation ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id AND operation.document_revision=2
        WHERE line.tenant_id=NEW.tenant_id AND line.id=NEW.id AND line.stock_identity_id=NEW.stock_identity_id
        AND line.quantity_base=NEW.issued_base AND line.base_unit=NEW.base_unit AND document.work_order_id=NEW.work_order_id
        AND document.state IN ('DISPATCHED','PART_RECEIVED','RECEIVED') AND NEW.due_at=operation.created_at+interval '24 hours') THEN
        RAISE EXCEPTION 'obligation requires immutable dispatched source' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_material_obligation_origin AFTER INSERT ON inventory_material_obligation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_obligation_origin_guard();
CREATE CONSTRAINT TRIGGER warehouse_material_obligation_dispatch AFTER UPDATE ON inventory_document
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_obligation_origin_guard();

CREATE FUNCTION warehouse_assert_residual_positions(target_tenant uuid,target_residual uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE bad boolean;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    WITH RECURSIVE identities(id) AS (
        SELECT source_identity_id FROM inventory_material_residual WHERE tenant_id=target_tenant AND id=target_residual
        UNION SELECT segment.id FROM inventory_segment segment JOIN identities ON identities.id=segment.parent_segment_id WHERE segment.tenant_id=target_tenant),
    expected AS (
        SELECT leg.stock_identity_id,leg.sku_id,leg.lot_id,leg.location_id,leg.custody_owner_kind,leg.custody_owner_id,
            leg.condition,leg.legal_owner,leg.status,leg.base_unit,
            sum(CASE WHEN leg.direction='IN' THEN leg.quantity_base::numeric ELSE -leg.quantity_base::numeric END) quantity
        FROM inventory_movement_leg leg JOIN identities ON identities.id=leg.stock_identity_id
        JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
        WHERE leg.tenant_id=target_tenant AND leg.warehouse_admission='VERIFIED' AND movement.state='APPLIED'
        GROUP BY leg.stock_identity_id,leg.sku_id,leg.lot_id,leg.location_id,leg.custody_owner_kind,leg.custody_owner_id,
            leg.condition,leg.legal_owner,leg.status,leg.base_unit),
    actual AS (
        SELECT balance.stock_identity_id,balance.sku_id,balance.lot_id,balance.location_id,balance.custody_owner_kind,balance.custody_owner_id,
            balance.condition,balance.legal_owner,balance.status,balance.base_unit,balance.quantity_base::numeric quantity
        FROM inventory_balance_projection balance JOIN identities ON identities.id=balance.stock_identity_id
        WHERE balance.tenant_id=target_tenant AND balance.quantity_base>0 AND balance.warehouse_admission='VERIFIED')
    SELECT EXISTS ((SELECT * FROM expected WHERE quantity>0 EXCEPT ALL SELECT * FROM actual)
        UNION ALL (SELECT * FROM actual EXCEPT ALL SELECT * FROM expected WHERE quantity>0)) INTO bad;
    IF bad THEN RAISE EXCEPTION 'residual positions must match immutable paired postings' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_residual_position_final_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target_tenant uuid; identity_id uuid; residual_id uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    target_tenant:=CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END;
    FOR identity_id IN SELECT DISTINCT unnest(CASE WHEN TG_OP='DELETE' THEN ARRAY[OLD.stock_identity_id]
        WHEN TG_OP='INSERT' THEN ARRAY[NEW.stock_identity_id] ELSE ARRAY[OLD.stock_identity_id,NEW.stock_identity_id] END) LOOP
        FOR residual_id IN WITH RECURSIVE ancestry(id,parent_segment_id) AS (
            SELECT id,parent_segment_id FROM inventory_segment WHERE tenant_id=target_tenant AND id=identity_id
            UNION SELECT segment.id,segment.parent_segment_id FROM inventory_segment segment JOIN ancestry ON segment.id=ancestry.parent_segment_id
                WHERE segment.tenant_id=target_tenant)
            SELECT residual.id FROM inventory_material_residual residual JOIN ancestry ON ancestry.id=residual.source_identity_id
                WHERE residual.tenant_id=target_tenant LOOP
            PERFORM warehouse_assert_residual_positions(target_tenant,residual_id);
        END LOOP;
    END LOOP;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_residual_position_truth AFTER INSERT OR UPDATE OR DELETE ON inventory_balance_projection
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_residual_position_final_guard();

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_material_residual(uuid,uuid)'::regprocedure);
    EXECUTE replace(definition,'    SELECT * INTO STRICT document FROM inventory_document',
        '    PERFORM warehouse_assert_residual_positions(target_tenant,target_residual);
    SELECT * INTO STRICT document FROM inventory_document');
END $$;
