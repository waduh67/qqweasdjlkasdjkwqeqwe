CREATE FUNCTION warehouse_assert_material_usage(target_tenant uuid,target_usage uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE header inventory_material_usage%ROWTYPE; usage inventory_usage_snapshot%ROWTYPE; operation inventory_operation%ROWTYPE;
    plan inventory_material_plan%ROWTYPE; document inventory_document%ROWTYPE; item record; receipt inventory_material_receipt%ROWTYPE;
    accepted inventory_material_receipt_line%ROWTYPE; debit inventory_movement_leg%ROWTYPE; credit inventory_movement_leg%ROWTYPE;
    frozen jsonb; entry jsonb; accepted_json jsonb; line_count bigint; expected_legs bigint:=0; split_required boolean;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO STRICT header FROM inventory_material_usage WHERE tenant_id=target_tenant AND id=target_usage;
    SELECT * INTO STRICT usage FROM inventory_usage_snapshot WHERE tenant_id=target_tenant AND id=target_usage;
    SELECT * INTO STRICT operation FROM inventory_operation WHERE tenant_id=target_tenant AND id=target_usage;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=target_tenant AND id=operation.document_id;
    SELECT * INTO STRICT plan FROM inventory_material_plan WHERE tenant_id=target_tenant AND id=usage.plan_id;
    frozen:=usage.frozen_snapshot::jsonb;
    IF usage.operation_id<>header.id OR operation.namespace<>'warehouse.material.use' OR operation.business_action<>'REPORT_USE' OR
        operation.actor_id<>header.actor_id OR operation.resource_id<>usage.work_order_id OR operation.document_id<>header.id OR
        operation.original_status<>200 OR operation.original_body<>usage.frozen_snapshot OR operation.created_at<>header.recorded_at OR
        document.kind<>'USAGE' OR document.state<>'POSTED' OR document.revision<>1 OR operation.document_revision<>1 OR
        document.actor_id<>header.actor_id OR document.work_order_id IS DISTINCT FROM usage.work_order_id OR
        document.customer_id IS DISTINCT FROM header.customer_id OR document.work_order_revision IS DISTINCT FROM usage.work_order_revision OR
        document.plan_revision IS DISTINCT FROM plan.plan_revision OR document.use_revision IS DISTINCT FROM usage.use_revision OR
        plan.work_order_id<>usage.work_order_id OR plan.work_order_revision<>usage.work_order_revision OR plan.state<>'SUBMITTED' OR
        plan.material_mode<>usage.material_mode OR usage.use_revision<>1 OR usage.compensates_snapshot_id IS NOT NULL OR
        frozen->>'usageId' IS DISTINCT FROM header.id::text OR frozen->>'workOrderId' IS DISTINCT FROM usage.work_order_id::text OR
        frozen->>'workOrderRevision' IS DISTINCT FROM usage.work_order_revision::text OR frozen->>'planId' IS DISTINCT FROM plan.id::text OR
        frozen->>'planRevision' IS DISTINCT FROM plan.plan_revision::text OR frozen->>'useRevision' IS DISTINCT FROM usage.use_revision::text OR
        frozen->>'actorId' IS DISTINCT FROM header.actor_id::text OR frozen->>'customerId' IS DISTINCT FROM header.customer_id::text OR
        frozen->>'evidenceReference' IS DISTINCT FROM header.evidence_reference OR frozen->>'reason' IS DISTINCT FROM header.reason OR
        frozen->>'networkReferenceLabel' IS DISTINCT FROM header.network_reference_label OR frozen->>'materialMode' IS DISTINCT FROM usage.material_mode OR
        (frozen->>'recordedAt')::timestamptz IS DISTINCT FROM header.recorded_at OR
        NOT EXISTS (SELECT FROM inventory_material_plan_snapshot snapshot WHERE snapshot.tenant_id=target_tenant AND snapshot.id=plan.id
            AND snapshot.snapshot::jsonb->>'customerId' IS NOT DISTINCT FROM header.customer_id::text) OR
        NOT EXISTS (SELECT FROM inventory_command_identity identity WHERE identity.tenant_id=target_tenant AND identity.id=operation.id) THEN
        RAISE EXCEPTION 'usage requires exact immutable command, plan and owner context' USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO line_count FROM inventory_material_usage_line WHERE tenant_id=target_tenant AND usage_id=header.id;
    IF line_count IS DISTINCT FROM jsonb_array_length(frozen->'lines') OR
        line_count<>(SELECT count(*) FROM inventory_document_line WHERE tenant_id=target_tenant AND document_id=header.id) THEN
        RAISE EXCEPTION 'usage line snapshot incomplete' USING ERRCODE='23514';
    END IF;
    IF usage.material_mode='NONE' THEN
        IF line_count<>0 OR header.reason IS NULL OR cardinality(usage.posting_ids)<>0 OR frozen->>'postingId' IS NOT NULL OR
            EXISTS (SELECT FROM inventory_movement WHERE tenant_id=target_tenant AND operation_id=header.id) OR
            EXISTS (SELECT FROM inventory_customer_material_fact WHERE tenant_id=target_tenant AND usage_id=header.id) THEN
            RAISE EXCEPTION 'NONE usage must be explicit and have no physical effects' USING ERRCODE='23514';
        END IF;
        RETURN;
    END IF;
    IF line_count=0 OR cardinality(usage.posting_ids)<>1 OR frozen->>'postingId' IS DISTINCT FROM usage.posting_ids[1]::text OR
        NOT EXISTS (SELECT FROM inventory_movement movement WHERE movement.tenant_id=target_tenant AND movement.id=usage.posting_ids[1]
            AND movement.operation_id=header.id AND movement.document_id=header.id AND movement.document_revision=1
            AND movement.kind='CONSUME' AND movement.state='APPLIED') THEN
        RAISE EXCEPTION 'material usage requires one exact consumption posting' USING ERRCODE='23514';
    END IF;
    FOR item IN SELECT line.*,source.source_line_id,source.stock_identity_id,source.quantity_base,source.tracking,
            plan_line.quantity_base plan_quantity,plan_line.plan_id FROM inventory_material_usage_line line
        JOIN inventory_document_line source ON source.tenant_id=line.tenant_id AND source.id=line.id
        JOIN inventory_material_plan_line plan_line ON plan_line.tenant_id=line.tenant_id AND plan_line.id=line.plan_line_id
        WHERE line.tenant_id=target_tenant AND line.usage_id=header.id LOOP
        SELECT * INTO STRICT receipt FROM inventory_material_receipt WHERE tenant_id=target_tenant AND id=item.receipt_id;
        SELECT * INTO STRICT accepted FROM inventory_material_receipt_line WHERE tenant_id=target_tenant AND receipt_id=item.receipt_id AND issue_line_id=item.issue_line_id;
        IF receipt.receiver_id<>header.actor_id OR accepted.accepted_base<>item.acknowledged_base OR accepted.accepted_identity_id IS DISTINCT FROM item.source_identity_id OR
            item.source_line_id IS DISTINCT FROM item.issue_line_id OR item.stock_identity_id IS DISTINCT FROM item.source_identity_id OR
            item.quantity_base<>item.used_base OR item.tracking='SERIAL' OR item.base_unit<>accepted.base_unit OR item.plan_id<>plan.id OR
            item.requested_base<>item.plan_quantity OR receipt.snapshot::jsonb->'issue'->>'workOrderId' IS DISTINCT FROM usage.work_order_id::text OR
            receipt.snapshot::jsonb->'issue'->>'planId' IS DISTINCT FROM plan.id::text OR
            (SELECT coalesce(sum(used_base::numeric),0) FROM inventory_material_usage_line WHERE tenant_id=target_tenant
                AND receipt_id=item.receipt_id AND issue_line_id=item.issue_line_id)>accepted.accepted_base OR
            NOT EXISTS (SELECT FROM jsonb_array_elements(receipt.snapshot::jsonb->'issue'->'lines') issued
                WHERE issued->>'id'=item.issue_line_id::text AND issued->>'planLineId'=item.plan_line_id::text) THEN
            RAISE EXCEPTION 'usage must consume its acknowledged receipt identity and quantity' USING ERRCODE='23514';
        END IF;
        SELECT element INTO STRICT entry FROM jsonb_array_elements(frozen->'lines') element WHERE element->>'id'=item.id::text;
        SELECT element->'accepted' INTO STRICT accepted_json FROM jsonb_array_elements(receipt.snapshot::jsonb->'lines') element
            WHERE element->'selection'->>'issueLineId'=item.issue_line_id::text;
        IF entry->'selection' IS DISTINCT FROM jsonb_build_object('receiptId',item.receipt_id,'issueLineId',item.issue_line_id,
                'stockIdentityId',item.source_identity_id,'quantityBase',item.used_base::text,'baseUnit',item.base_unit) OR
            entry->'source' IS DISTINCT FROM accepted_json OR entry->>'sourceRevision' IS DISTINCT FROM item.source_revision::text OR
            entry->>'receiptRevision' IS DISTINCT FROM receipt.issue_revision::text OR entry->>'issueId' IS DISTINCT FROM receipt.issue_id::text OR
            entry->>'planLineId' IS DISTINCT FROM item.plan_line_id::text OR entry->>'requestedBase' IS DISTINCT FROM item.requested_base::text OR
            entry->>'acknowledgedBase' IS DISTINCT FROM item.acknowledged_base::text OR entry->>'residualBase' IS DISTINCT FROM item.residual_base::text OR
            entry->>'factId' IS DISTINCT FROM item.fact_id::text THEN
            RAISE EXCEPTION 'usage source snapshot mismatch' USING ERRCODE='23514';
        END IF;
        split_required:=item.base_unit='MM' AND item.residual_base>0;
        SELECT * INTO STRICT debit FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=usage.posting_ids[1]
            AND document_line_id=item.id AND direction='OUT';
        SELECT * INTO STRICT credit FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=usage.posting_ids[1]
            AND document_line_id=item.id AND direction='IN' AND stock_identity_id=item.consumed_identity_id;
        IF debit.stock_identity_id<>item.source_identity_id OR debit.status<>'ISSUED' OR debit.custody_owner_kind<>'TECHNICIAN' OR
            debit.custody_owner_id<>header.actor_id OR debit.location_id::text IS DISTINCT FROM accepted_json->>'locationId' OR
            debit.quantity_base<>(CASE WHEN split_required THEN item.acknowledged_base ELSE item.used_base END) OR
            debit.condition<>'SERVICEABLE' OR debit.legal_owner<>'ISP' OR debit.base_unit<>item.base_unit OR
            credit.quantity_base<>item.used_base OR credit.status<>'CONSUMED' OR credit.serialized OR
            (credit.sku_id,credit.base_unit,credit.condition,credit.legal_owner) IS DISTINCT FROM (debit.sku_id,debit.base_unit,debit.condition,debit.legal_owner) OR
            credit.lot_id IS DISTINCT FROM debit.lot_id OR
            (credit.custody_owner_kind,credit.custody_owner_id) IS DISTINCT FROM
                (CASE WHEN header.customer_id IS NULL THEN 'TECHNICIAN' ELSE 'CUSTOMER' END,coalesce(header.customer_id,header.actor_id)) OR
            NOT EXISTS (SELECT FROM inventory_location WHERE tenant_id=target_tenant AND id=credit.location_id AND code='CONSUMED') OR
            entry->'consumed' IS DISTINCT FROM (accepted_json || jsonb_build_object('stockIdentityId',credit.stock_identity_id,
                'locationId',credit.location_id,'custodianId',credit.custody_owner_id,'custodianKind',credit.custody_owner_kind)) OR
            NOT EXISTS (SELECT FROM inventory_customer_material_fact fact WHERE fact.tenant_id=target_tenant AND fact.id=item.fact_id
                AND fact.usage_id=header.id AND fact.posting_id=usage.posting_ids[1] AND fact.work_order_id=usage.work_order_id
                AND fact.customer_id IS NOT DISTINCT FROM header.customer_id AND fact.quantity_base=item.used_base AND fact.base_unit=item.base_unit
                AND fact.stock_identity_id=item.consumed_identity_id AND fact.use_revision=usage.use_revision AND fact.installed AND NOT fact.returned
                AND fact.compensation_id IS NULL AND fact.recorded_at=header.recorded_at AND fact.payload_hash=operation.payload_hash) THEN
            RAISE EXCEPTION 'usage requires exact custody debit, consumed credit and material fact' USING ERRCODE='23514';
        END IF;
        IF split_required THEN
            IF NOT EXISTS (SELECT FROM inventory_segment parent JOIN inventory_segment consumed ON consumed.tenant_id=parent.tenant_id AND consumed.parent_segment_id=parent.id
                JOIN inventory_segment remnant ON remnant.tenant_id=parent.tenant_id AND remnant.parent_segment_id=parent.id
                WHERE parent.tenant_id=target_tenant AND parent.id=item.source_identity_id AND parent.state='SPLIT' AND parent.revision=item.source_revision+1
                    AND consumed.id=item.consumed_identity_id AND consumed.quantity_base=item.used_base AND remnant.id=item.remainder_identity_id
                    AND remnant.quantity_base=item.residual_base AND remnant.kind='REMNANT') OR
                NOT EXISTS (SELECT FROM inventory_movement_leg leg WHERE leg.tenant_id=target_tenant AND leg.movement_id=usage.posting_ids[1]
                    AND leg.document_line_id=item.id AND leg.direction='IN' AND leg.stock_identity_id=item.remainder_identity_id
                    AND leg.quantity_base=item.residual_base AND leg.status='ISSUED' AND leg.lot_id IS NOT DISTINCT FROM debit.lot_id
                    AND (leg.sku_id,leg.base_unit,leg.location_id,leg.custody_owner_kind,leg.custody_owner_id,leg.condition,leg.legal_owner)
                        =(debit.sku_id,debit.base_unit,debit.location_id,debit.custody_owner_kind,debit.custody_owner_id,debit.condition,debit.legal_owner)) THEN
                RAISE EXCEPTION 'partial MM use requires conserved consumed and accountable children' USING ERRCODE='23514';
            END IF;
        ELSIF item.consumed_identity_id<>item.source_identity_id OR
            (item.residual_base>0 AND item.remainder_identity_id<>item.source_identity_id) THEN
            RAISE EXCEPTION 'whole piece or fungible use must retain source identity' USING ERRCODE='23514';
        END IF;
        IF entry->'remainder' IS DISTINCT FROM (CASE WHEN item.residual_base=0 THEN 'null'::jsonb
            ELSE accepted_json || jsonb_build_object('stockIdentityId',item.remainder_identity_id) END) THEN
            RAISE EXCEPTION 'usage residual snapshot mismatch' USING ERRCODE='23514';
        END IF;
        expected_legs:=expected_legs+2+CASE WHEN split_required THEN 1 ELSE 0 END;
    END LOOP;
    IF (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=usage.posting_ids[1])<>expected_legs OR
        (SELECT count(*) FROM inventory_customer_material_fact WHERE tenant_id=target_tenant AND posting_id=usage.posting_ids[1])<>line_count THEN
        RAISE EXCEPTION 'usage posting contains unaccounted legs or facts' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_material_usage_bound_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_material_usage' THEN target:=NEW.id;
    ELSIF TG_TABLE_NAME IN ('inventory_material_usage_line','inventory_customer_material_fact') THEN target:=NEW.usage_id;
    ELSIF TG_TABLE_NAME='inventory_usage_snapshot' THEN
        IF NEW.material_mode='NONE' OR EXISTS (SELECT FROM inventory_material_usage WHERE tenant_id=NEW.tenant_id AND id=NEW.id) THEN target:=NEW.id; END IF;
    ELSIF TG_TABLE_NAME='inventory_operation' THEN
        IF NEW.namespace='warehouse.material.use' THEN target:=NEW.id; END IF;
    ELSIF TG_TABLE_NAME='inventory_document' THEN
        IF NEW.kind='USAGE' AND NEW.state<>'DRAFT' THEN target:=NEW.id; END IF;
    ELSIF TG_TABLE_NAME='inventory_movement' THEN
        IF NEW.kind='CONSUME' AND EXISTS (SELECT FROM inventory_movement_leg leg JOIN inventory_document_line line
            ON line.tenant_id=leg.tenant_id AND line.id=leg.document_line_id JOIN inventory_issue_line issue ON issue.tenant_id=line.tenant_id AND issue.id=line.source_line_id
            WHERE leg.tenant_id=NEW.tenant_id AND leg.movement_id=NEW.id) THEN target:=NEW.operation_id; END IF;
    END IF;
    IF target IS NOT NULL THEN PERFORM warehouse_assert_material_usage(NEW.tenant_id,target); END IF;
    RETURN NEW;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_material_usage','inventory_material_usage_line','inventory_usage_snapshot',
        'inventory_customer_material_fact','inventory_operation','inventory_movement','inventory_document'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_usage_bound AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_usage_bound_guard()',table_name);
    END LOOP;
END $$;

CREATE FUNCTION warehouse_consumed_balance_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE balance inventory_balance_projection%ROWTYPE; posted numeric;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
    IF balance.warehouse_admission='VERIFIED' AND balance.status='CONSUMED' AND balance.quantity_base>0 THEN
        SELECT coalesce(sum(CASE WHEN leg.direction='IN' THEN leg.quantity_base::numeric ELSE -leg.quantity_base::numeric END),0) INTO posted
            FROM inventory_movement_leg leg JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            WHERE leg.tenant_id=NEW.tenant_id AND leg.stock_identity_id=balance.stock_identity_id AND leg.location_id=balance.location_id
                AND leg.custody_owner_id=balance.custody_owner_id AND leg.custody_owner_kind=balance.custody_owner_kind
                AND leg.condition=balance.condition AND leg.legal_owner=balance.legal_owner AND leg.status='CONSUMED' AND movement.state='APPLIED';
        IF posted<>balance.quantity_base THEN RAISE EXCEPTION 'consumed state requires physical posting' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_consumed_balance_bound AFTER INSERT OR UPDATE ON inventory_balance_projection
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_consumed_balance_guard();
