CREATE TABLE inventory_material_usage_delta (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), previous_usage_id uuid NOT NULL,
    receipt_id uuid NOT NULL, issue_line_id uuid NOT NULL, source_identity_id uuid NOT NULL,
    source_quantity_base bigint NOT NULL CHECK (source_quantity_base>0), source_dimension text NOT NULL, actor_id uuid NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), UNIQUE (tenant_id,id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_material_usage(tenant_id,id),
    FOREIGN KEY (tenant_id,previous_usage_id) REFERENCES inventory_material_usage(tenant_id,id),
    FOREIGN KEY (tenant_id,receipt_id,issue_line_id) REFERENCES inventory_material_receipt_line(tenant_id,receipt_id,issue_line_id),
    FOREIGN KEY (tenant_id,source_identity_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY (tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
ALTER TABLE inventory_material_usage_delta ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_material_usage_delta FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_material_usage_delta
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_material_usage_delta
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE INDEX inventory_material_usage_delta_source_idx ON inventory_material_usage_delta(tenant_id,previous_usage_id,source_identity_id);

CREATE FUNCTION warehouse_material_usage_delta_insert_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.created_xid<>pg_current_xact_id() OR NOT EXISTS (
        SELECT FROM inventory_material_usage WHERE tenant_id=NEW.tenant_id AND id=NEW.id AND actor_id=NEW.actor_id AND created_xid=pg_current_xact_id()) OR
        NOT EXISTS (SELECT FROM inventory_balance_projection balance WHERE balance.tenant_id=NEW.tenant_id
            AND balance.stock_identity_id=NEW.source_identity_id AND balance.quantity_base=NEW.source_quantity_base
            AND balance.status='ISSUED' AND balance.condition='SERVICEABLE' AND balance.legal_owner='ISP'
            AND balance.custody_owner_kind='TECHNICIAN' AND balance.custody_owner_id=NEW.actor_id
            AND balance.location_id::text=NEW.source_dimension::jsonb->>'locationId') THEN
        RAISE EXCEPTION 'usage delta must capture real remaining acknowledged custody' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_material_usage_delta_insert BEFORE INSERT ON inventory_material_usage_delta
    FOR EACH ROW EXECUTE FUNCTION warehouse_material_usage_delta_insert_guard();

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_material_usage(uuid,uuid)'::regprocedure);
    IF position('usage.use_revision<>1 OR usage.compensates_snapshot_id IS NOT NULL' IN definition)=0 OR
        position('IF receipt.receiver_id<>header.actor_id OR accepted.accepted_base<>item.acknowledged_base OR accepted.accepted_identity_id IS DISTINCT FROM item.source_identity_id OR' IN definition)=0 THEN
        RAISE EXCEPTION 'expected immutable usage validator missing';
    END IF;
    definition:=replace(definition,'DECLARE header inventory_material_usage',
        'DECLARE delta inventory_material_usage_delta%ROWTYPE; predecessor inventory_usage_snapshot%ROWTYPE; header inventory_material_usage');
    definition:=replace(definition,'    frozen:=usage.frozen_snapshot::jsonb;',
        '    SELECT * INTO delta FROM inventory_material_usage_delta WHERE tenant_id=target_tenant AND id=target_usage;
    IF delta.id IS NOT NULL THEN
        SELECT * INTO STRICT predecessor FROM inventory_usage_snapshot WHERE tenant_id=target_tenant AND id=delta.previous_usage_id;
        IF predecessor.work_order_id<>usage.work_order_id OR predecessor.plan_id<>usage.plan_id OR
            predecessor.use_revision+1<>usage.use_revision OR usage.compensates_snapshot_id IS DISTINCT FROM predecessor.id OR
            header.actor_id<>delta.actor_id THEN
            RAISE EXCEPTION ''usage delta requires exact predecessor revision'' USING ERRCODE=''23514'';
        END IF;
        PERFORM warehouse_assert_residual_source(target_tenant,usage.work_order_id,delta.receipt_id,delta.issue_line_id,
            delta.previous_usage_id,delta.source_identity_id,delta.actor_id);
    END IF;
    frozen:=usage.frozen_snapshot::jsonb;');
    definition:=replace(definition,'usage.use_revision<>1 OR usage.compensates_snapshot_id IS NOT NULL',
        '(delta.id IS NULL AND (usage.use_revision<>1 OR usage.compensates_snapshot_id IS NOT NULL))');
    definition:=replace(definition,
        'IF receipt.receiver_id<>header.actor_id OR accepted.accepted_base<>item.acknowledged_base OR accepted.accepted_identity_id IS DISTINCT FROM item.source_identity_id OR',
        'IF (delta.id IS NULL AND (receipt.receiver_id<>header.actor_id OR accepted.accepted_base<>item.acknowledged_base OR accepted.accepted_identity_id IS DISTINCT FROM item.source_identity_id)) OR
            (delta.id IS NOT NULL AND (delta.receipt_id<>item.receipt_id OR delta.issue_line_id<>item.issue_line_id OR
                delta.source_identity_id<>item.source_identity_id OR delta.source_quantity_base<>item.acknowledged_base)) OR');
    definition:=replace(definition,'        IF entry->''selection'' IS DISTINCT FROM',
        '        IF delta.id IS NOT NULL THEN accepted_json:=delta.source_dimension::jsonb; END IF;
        IF entry->''selection'' IS DISTINCT FROM');
    EXECUTE definition;
END $$;
