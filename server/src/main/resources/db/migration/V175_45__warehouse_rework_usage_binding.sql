ALTER TABLE inventory_material_usage_delta ADD COLUMN rework_id uuid, ADD COLUMN source_usage_id uuid, ADD COLUMN evidence_revision varchar(64);
ALTER TABLE inventory_material_usage_delta ADD FOREIGN KEY (tenant_id,rework_id) REFERENCES inventory_material_rework(tenant_id,id);
ALTER TABLE inventory_material_usage_delta ADD FOREIGN KEY (tenant_id,source_usage_id) REFERENCES inventory_material_usage(tenant_id,id);
ALTER TABLE inventory_material_usage_delta ADD CHECK (
    (rework_id IS NULL AND source_usage_id IS NULL AND evidence_revision IS NULL) OR
    (rework_id IS NOT NULL AND evidence_revision IS NOT NULL AND evidence_revision ~ '^[0-9a-f]{64}$'));
CREATE INDEX inventory_material_usage_delta_rework_idx ON inventory_material_usage_delta(tenant_id,rework_id,id);

CREATE FUNCTION warehouse_rework_usage_insert_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.rework_id IS NOT NULL THEN
        PERFORM warehouse_assert_rework_live(NEW.tenant_id,NEW.rework_id);
        IF NOT EXISTS (SELECT FROM inventory_material_rework WHERE tenant_id=NEW.tenant_id AND id=NEW.rework_id AND evidence_revision=NEW.evidence_revision) THEN
            RAISE EXCEPTION 'usage requires the exact rework evidence revision' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_rework_usage_insert BEFORE INSERT ON inventory_material_usage_delta
    FOR EACH ROW EXECUTE FUNCTION warehouse_rework_usage_insert_guard();

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_material_usage(uuid,uuid)'::regprocedure);
    IF position('predecessor.plan_id<>usage.plan_id' IN definition)=0 OR position('delta.previous_usage_id,delta.source_identity_id,delta.actor_id' IN definition)=0 OR
        position('item.plan_id<>plan.id' IN definition)=0 THEN RAISE EXCEPTION 'expected positive usage validator missing'; END IF;
    definition:=replace(definition,'predecessor.plan_id<>usage.plan_id',
        '(delta.rework_id IS NULL AND predecessor.plan_id<>usage.plan_id) OR
            (delta.rework_id IS NOT NULL AND (delta.rework_id<>usage.plan_id OR (predecessor.plan_id<>usage.plan_id AND NOT EXISTS (
                SELECT FROM inventory_material_rework_inherited_line inherited JOIN inventory_material_plan_line source
                    ON source.tenant_id=inherited.tenant_id AND source.id=inherited.plan_line_id
                WHERE inherited.tenant_id=target_tenant AND inherited.rework_id=delta.rework_id AND source.plan_id=predecessor.plan_id))))');
    definition:=replace(definition,'        PERFORM warehouse_assert_residual_source(target_tenant,usage.work_order_id,delta.receipt_id,delta.issue_line_id,',
        '        IF delta.rework_id IS NOT NULL THEN
            PERFORM warehouse_assert_material_rework(target_tenant,delta.rework_id);
            IF delta.evidence_revision IS DISTINCT FROM (SELECT evidence_revision FROM inventory_material_rework WHERE tenant_id=target_tenant AND id=delta.rework_id) THEN
                RAISE EXCEPTION ''usage rework evidence binding mismatch'' USING ERRCODE=''23514'';
            END IF;
            IF delta.created_xid=pg_current_xact_id() THEN PERFORM warehouse_assert_rework_live(target_tenant,delta.rework_id); END IF;
        END IF;
        PERFORM warehouse_assert_residual_source(target_tenant,usage.work_order_id,delta.receipt_id,delta.issue_line_id,');
    definition:=replace(definition,'delta.previous_usage_id,delta.source_identity_id,delta.actor_id',
        'CASE WHEN delta.rework_id IS NULL THEN delta.previous_usage_id ELSE delta.source_usage_id END,delta.source_identity_id,delta.actor_id');
    definition:=replace(definition,'    frozen:=usage.frozen_snapshot::jsonb;',
        '    IF EXISTS (SELECT FROM inventory_material_rework WHERE tenant_id=target_tenant AND id=usage.plan_id)
        AND delta.rework_id IS DISTINCT FROM usage.plan_id THEN
        RAISE EXCEPTION ''usage of a rework plan requires explicit immutable rework linkage'' USING ERRCODE=''23514'';
    END IF;
    frozen:=usage.frozen_snapshot::jsonb;');
    definition:=replace(definition,'item.plan_id<>plan.id',
        '(item.plan_id<>plan.id AND NOT EXISTS (SELECT FROM inventory_material_rework_inherited_line
            WHERE tenant_id=target_tenant AND rework_id=delta.rework_id AND plan_line_id=item.plan_line_id))');
    definition:=replace(definition,'receipt.snapshot::jsonb->''issue''->>''planId'' IS DISTINCT FROM plan.id::text',
        'receipt.snapshot::jsonb->''issue''->>''planId'' IS DISTINCT FROM item.plan_id::text');
    EXECUTE definition;
END $$;

CREATE FUNCTION warehouse_has_rework_demand(target_tenant uuid,target_work_order uuid) RETURNS boolean LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    IF EXISTS (SELECT FROM work_order WHERE tenant_id=target_tenant AND id=target_work_order AND status='CANCELLED') THEN RETURN false; END IF;
    RETURN EXISTS (SELECT FROM inventory_material_rework rework JOIN inventory_material_submission submission
        ON submission.tenant_id=rework.tenant_id AND submission.id=rework.id
        JOIN inventory_document_line demand ON demand.tenant_id=submission.tenant_id AND demand.document_id=submission.document_id
        WHERE rework.tenant_id=target_tenant AND rework.work_order_id=target_work_order AND demand.quantity_base>(
            SELECT coalesce(sum(line.quantity_base::numeric),0) FROM inventory_document_line line JOIN inventory_document issue
                ON issue.tenant_id=line.tenant_id AND issue.id=line.document_id
            WHERE line.tenant_id=target_tenant AND line.source_line_id=demand.id AND issue.kind='ISSUE' AND issue.state IN ('DISPATCHED','PART_RECEIVED','RECEIVED')));
END $$;

CREATE OR REPLACE FUNCTION warehouse_assert_material_close(target_tenant uuid,target_work_order uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    IF (SELECT material_state FROM inventory_material_lifecycle WHERE tenant_id=target_tenant AND work_order_id=target_work_order
        ORDER BY revision DESC LIMIT 1)='CLOSED' AND (EXISTS (
        SELECT FROM warehouse_material_obligation_totals(target_tenant,target_work_order) WHERE accountable_base>0 OR returned_base>0) OR EXISTS (
        SELECT FROM inventory_reservation reservation JOIN inventory_document_line line ON line.tenant_id=reservation.tenant_id AND line.id=reservation.document_line_id
        JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
        WHERE reservation.tenant_id=target_tenant AND document.work_order_id=target_work_order
            AND reservation.reserved_unpicked_base+reservation.reserved_picked_base>0) OR warehouse_has_rework_demand(target_tenant,target_work_order)) THEN
        RAISE EXCEPTION 'closed material settlement cannot retain or acquire obligations' USING ERRCODE='23514';
    END IF;
END $$;
