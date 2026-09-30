CREATE INDEX inventory_material_demand_binding_lookup ON inventory_document(tenant_id,work_order_id,plan_revision) WHERE kind='DEMAND';

CREATE FUNCTION warehouse_assert_material_submission(owner_tenant uuid, target_plan uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE plan inventory_material_plan%ROWTYPE; demand inventory_document%ROWTYPE;
    submission_count bigint; demand_count bigint; line_count bigint; binding uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(owner_tenant);
    SELECT * INTO plan FROM inventory_material_plan WHERE tenant_id=owner_tenant AND id=target_plan FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;
    SELECT count(*),min(document_id::text)::uuid INTO submission_count,binding
        FROM inventory_material_submission WHERE tenant_id=owner_tenant AND id=target_plan;
    IF plan.state='DRAFT' THEN
        IF submission_count=0 THEN RETURN; END IF;
        RAISE EXCEPTION 'material submission binding requires a submitted plan' USING ERRCODE='23514',CONSTRAINT='warehouse_material_submission_ck';
    END IF;
    SELECT count(*) INTO demand_count FROM inventory_document
        WHERE tenant_id=owner_tenant AND work_order_id=plan.work_order_id AND plan_revision=plan.plan_revision AND kind='DEMAND';
    SELECT count(*) INTO line_count FROM inventory_material_plan_line WHERE tenant_id=owner_tenant AND plan_id=target_plan;
    IF submission_count<>1 OR NOT EXISTS(SELECT FROM inventory_material_plan_snapshot WHERE tenant_id=owner_tenant AND id=target_plan) THEN
        RAISE EXCEPTION 'material submission binding is missing or ambiguous' USING ERRCODE='23514',CONSTRAINT='warehouse_material_submission_ck';
    END IF;
    IF plan.material_mode='NONE' THEN
        IF binding IS NOT NULL OR demand_count<>0 OR line_count<>0 OR plan.reason IS NULL OR btrim(plan.reason)='' THEN
            RAISE EXCEPTION 'material submission binding violates explicit NONE declaration' USING ERRCODE='23514',CONSTRAINT='warehouse_material_submission_ck';
        END IF;
        RETURN;
    END IF;
    SELECT * INTO demand FROM inventory_document WHERE tenant_id=owner_tenant AND id=binding;
    IF NOT FOUND OR binding IS NULL OR demand_count<>1 OR line_count NOT BETWEEN 1 AND 100 OR demand.kind<>'DEMAND'
        OR demand.work_order_id IS DISTINCT FROM plan.work_order_id OR demand.plan_revision IS DISTINCT FROM plan.plan_revision
        OR demand.work_order_revision IS DISTINCT FROM plan.work_order_revision OR demand.state='DRAFT'
        OR demand.revision<1 OR demand.submitted_at IS NULL THEN
        RAISE EXCEPTION 'material submission binding has invalid demand identity or revision' USING ERRCODE='23514',CONSTRAINT='warehouse_material_submission_ck';
    END IF;
    IF (SELECT count(*) FROM inventory_document_line WHERE tenant_id=owner_tenant AND document_id=binding)<>line_count
        OR EXISTS(SELECT FROM inventory_material_plan_line planned
            LEFT JOIN inventory_document_line requested ON requested.tenant_id=planned.tenant_id AND requested.document_id=binding
                AND requested.line_number=planned.line_number
            LEFT JOIN inventory_sku sku ON sku.tenant_id=planned.tenant_id AND sku.id=planned.sku_id
            WHERE planned.tenant_id=owner_tenant AND planned.plan_id=target_plan AND
                (requested.id IS NULL OR requested.sku_id<>planned.sku_id OR requested.base_unit<>planned.base_unit
                 OR requested.quantity_base<>planned.quantity_base OR requested.continuous_cut<>planned.continuous_cut
                 OR requested.tracking IS DISTINCT FROM sku.tracking OR requested.document_revision<>0)) THEN
        RAISE EXCEPTION 'material submission binding has missing or mismatched demand lines' USING ERRCODE='23514',CONSTRAINT='warehouse_material_submission_ck';
    END IF;
END $$;

CREATE FUNCTION warehouse_material_submission_binding_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target_document uuid; target_order uuid; target_revision bigint; target record;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME IN ('inventory_material_plan','inventory_material_plan_snapshot','inventory_material_submission') THEN
        PERFORM warehouse_assert_material_submission(NEW.tenant_id,NEW.id);
    ELSIF TG_TABLE_NAME='inventory_material_plan_line' THEN
        PERFORM warehouse_assert_material_submission(NEW.tenant_id,NEW.plan_id);
    ELSE
        IF TG_TABLE_NAME='inventory_document' THEN
            target_document:=NEW.id; target_order:=NEW.work_order_id; target_revision:=NEW.plan_revision;
        ELSE
            target_document:=NEW.document_id;
            SELECT work_order_id,plan_revision INTO target_order,target_revision FROM inventory_document
                WHERE tenant_id=NEW.tenant_id AND id=target_document;
        END IF;
        FOR target IN SELECT id FROM inventory_material_plan plan WHERE plan.tenant_id=NEW.tenant_id AND
            ((plan.work_order_id=target_order AND plan.plan_revision=target_revision) OR EXISTS(
                SELECT FROM inventory_material_submission submission WHERE submission.tenant_id=plan.tenant_id
                    AND submission.id=plan.id AND submission.document_id=target_document)) ORDER BY plan.id LOOP
            PERFORM warehouse_assert_material_submission(NEW.tenant_id,target.id);
        END LOOP;
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER warehouse_material_submission ON inventory_material_submission;
DO $$ DECLARE table_name text; foreign_key record;
BEGIN
    FOR foreign_key IN SELECT conname FROM pg_constraint WHERE conrelid='inventory_material_submission'::regclass AND contype='f' LOOP
        EXECUTE format('ALTER TABLE inventory_material_submission ALTER CONSTRAINT %I DEFERRABLE INITIALLY DEFERRED',foreign_key.conname);
    END LOOP;
    FOREACH table_name IN ARRAY ARRAY['inventory_material_plan','inventory_material_plan_line','inventory_material_plan_snapshot',
        'inventory_material_submission','inventory_document','inventory_document_line'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_material_submission_binding AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_submission_binding_guard()',table_name);
    END LOOP;
END $$;
