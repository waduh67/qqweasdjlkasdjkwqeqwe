CREATE FUNCTION warehouse_material_obligation_totals(target_tenant uuid,target_work_order uuid)
RETURNS TABLE(issue_line_id uuid,stock_identity_id uuid,base_unit varchar,issued_base bigint,used_base bigint,
    returned_base bigint,transferred_base bigint,accountable_base bigint,transit_base bigint,acknowledged_base bigint)
LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    RETURN QUERY SELECT line.id,line.stock_identity_id,line.base_unit,line.quantity_base,
        used.amount::bigint,returned.amount::bigint,0::bigint,
        (line.quantity_base-used.amount-returned.amount)::bigint,
        (line.quantity_base-received.amount+pending.amount)::bigint,received.amount::bigint
    FROM inventory_document_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
    JOIN inventory_issue_line issue ON issue.tenant_id=line.tenant_id AND issue.id=line.id
    CROSS JOIN LATERAL (SELECT coalesce(sum(usage.used_base::numeric),0) amount FROM inventory_material_usage_line usage
        WHERE usage.tenant_id=line.tenant_id AND usage.issue_line_id=line.id) used
    CROSS JOIN LATERAL (SELECT coalesce(sum(receipt.accepted_base::numeric),0) amount FROM inventory_material_receipt_line receipt
        WHERE receipt.tenant_id=line.tenant_id AND receipt.issue_line_id=line.id) received
    CROSS JOIN LATERAL (SELECT coalesce(sum(residual.quantity_base::numeric),0) amount FROM inventory_material_residual residual
        JOIN inventory_material_residual_ack ack ON ack.tenant_id=residual.tenant_id AND ack.residual_id=residual.id
        WHERE residual.tenant_id=line.tenant_id AND residual.issue_line_id=line.id AND residual.purpose='RETURN') returned
    CROSS JOIN LATERAL (SELECT coalesce(sum(residual.quantity_base::numeric),0) amount FROM inventory_material_residual residual
        WHERE residual.tenant_id=line.tenant_id AND residual.issue_line_id=line.id AND NOT EXISTS (
            SELECT FROM inventory_material_residual_ack ack WHERE ack.tenant_id=residual.tenant_id AND ack.residual_id=residual.id)) pending
    WHERE document.tenant_id=target_tenant AND document.work_order_id=target_work_order AND document.kind='ISSUE'
        AND document.state IN ('DISPATCHED','PART_RECEIVED','RECEIVED') ORDER BY line.id;
END $$;

CREATE FUNCTION warehouse_assert_residual_source(target_tenant uuid,target_work_order uuid,target_receipt uuid,
    target_line uuid,target_usage uuid,target_identity uuid,target_actor uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE root uuid; usage_root uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT line.accepted_identity_id INTO root FROM inventory_material_receipt_line line
        JOIN inventory_material_receipt receipt ON receipt.tenant_id=line.tenant_id AND receipt.id=line.receipt_id
        JOIN inventory_document issue ON issue.tenant_id=receipt.tenant_id AND issue.id=receipt.issue_id
        WHERE line.tenant_id=target_tenant AND line.receipt_id=target_receipt AND line.issue_line_id=target_line
            AND receipt.receiver_id=target_actor AND issue.work_order_id=target_work_order AND line.accepted_base>0;
    IF root IS NULL THEN RAISE EXCEPTION 'residual requires own acknowledged source' USING ERRCODE='23514'; END IF;
    IF EXISTS (SELECT FROM inventory_material_usage_line WHERE tenant_id=target_tenant AND receipt_id=target_receipt AND issue_line_id=target_line) THEN
        SELECT remainder_identity_id INTO usage_root FROM inventory_material_usage_line WHERE tenant_id=target_tenant
            AND usage_id=target_usage AND receipt_id=target_receipt AND issue_line_id=target_line;
        IF usage_root IS NULL THEN RAISE EXCEPTION 'residual requires exact unused usage remainder' USING ERRCODE='23514'; END IF;
        root:=usage_root;
    ELSIF target_usage IS NOT NULL THEN RAISE EXCEPTION 'residual usage source mismatch' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (WITH RECURSIVE ancestry(id,parent_segment_id) AS (
        SELECT id,parent_segment_id FROM inventory_segment WHERE tenant_id=target_tenant AND id=target_identity
        UNION SELECT segment.id,segment.parent_segment_id FROM inventory_segment segment JOIN ancestry ON ancestry.parent_segment_id=segment.id
            WHERE segment.tenant_id=target_tenant) SELECT FROM ancestry WHERE id=root) THEN
        RAISE EXCEPTION 'residual identity substitution' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_assert_material_residual(target_tenant uuid,target_residual uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE residual inventory_material_residual%ROWTYPE; ack inventory_material_residual_ack%ROWTYPE; document inventory_document%ROWTYPE;
    source jsonb; transit jsonb; expected_out bigint; line_id uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO STRICT residual FROM inventory_material_residual WHERE tenant_id=target_tenant AND id=target_residual;
    PERFORM warehouse_assert_residual_source(target_tenant,residual.work_order_id,residual.receipt_id,residual.issue_line_id,
        residual.usage_id,residual.source_identity_id,residual.sender_id);
    source:=residual.source_dimension::jsonb; transit:=residual.transit_dimension::jsonb;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=target_tenant AND id=target_residual;
    SELECT id INTO STRICT line_id FROM inventory_document_line WHERE tenant_id=target_tenant AND document_id=target_residual;
    expected_out:=CASE WHEN residual.remainder_identity_id IS NOT NULL AND residual.base_unit='MM'
        THEN residual.source_quantity_base ELSE residual.quantity_base END;
    IF document.work_order_id IS DISTINCT FROM residual.work_order_id OR document.actor_id<>residual.sender_id OR
        document.kind<>(CASE WHEN residual.purpose='RETURN' THEN 'RETURN' ELSE 'TRANSFER' END) OR
        NOT EXISTS (SELECT FROM inventory_operation operation JOIN inventory_movement movement
            ON movement.tenant_id=operation.tenant_id AND movement.operation_id=operation.id
            WHERE operation.tenant_id=target_tenant AND operation.id=residual.dispatch_operation_id
                AND operation.actor_id=residual.sender_id AND operation.resource_id=residual.work_order_id
                AND operation.document_id=residual.id AND operation.document_revision=1
                AND operation.namespace='warehouse.material.residual.dispatch' AND operation.original_body=residual.body
                AND movement.id=residual.dispatch_posting_id AND movement.state='APPLIED') OR
        source->>'stockIdentityId' IS DISTINCT FROM residual.source_identity_id::text OR
        source->>'custodianId' IS DISTINCT FROM residual.sender_id::text OR source->>'custodianKind'<>'TECHNICIAN' OR
        transit IS DISTINCT FROM (source || jsonb_build_object('stockIdentityId',residual.transit_identity_id,
            'locationId',residual.target_location_id,'condition','QUARANTINE')) THEN
        RAISE EXCEPTION 'residual requires exact source and dispatch operation' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (SELECT FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=residual.dispatch_posting_id
        AND document_line_id=line_id AND direction='OUT' AND stock_identity_id=residual.source_identity_id
        AND quantity_base=expected_out AND base_unit=residual.base_unit AND status='ISSUED'
        AND custody_owner_kind='TECHNICIAN' AND custody_owner_id=residual.sender_id AND location_id::text=source->>'locationId') OR
        NOT EXISTS (SELECT FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=residual.dispatch_posting_id
        AND document_line_id=line_id AND direction='IN' AND stock_identity_id=residual.transit_identity_id
        AND quantity_base=residual.quantity_base AND base_unit=residual.base_unit AND status='IN_TRANSIT'
        AND condition='QUARANTINE' AND custody_owner_kind='TECHNICIAN' AND custody_owner_id=residual.sender_id AND location_id=residual.target_location_id) OR
        (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=residual.dispatch_posting_id)
            <>(CASE WHEN expected_out>residual.quantity_base THEN 3 ELSE 2 END) THEN
        RAISE EXCEPTION 'residual dispatch requires paired exact physical posting' USING ERRCODE='23514';
    END IF;
    SELECT * INTO ack FROM inventory_material_residual_ack WHERE tenant_id=target_tenant AND residual_id=residual.id;
    IF FOUND THEN
        IF ack.actor_id=residual.sender_id OR document.state<>'RECEIVED_IN_INSPECTION' OR document.revision<>2 OR
            NOT EXISTS (SELECT FROM inventory_operation operation JOIN inventory_movement movement
                ON movement.tenant_id=operation.tenant_id AND movement.operation_id=operation.id
                WHERE operation.tenant_id=target_tenant AND operation.id=ack.id AND operation.actor_id=ack.actor_id
                AND operation.document_id=residual.id AND operation.document_revision=2 AND operation.original_body=ack.body
                AND operation.namespace='warehouse.material.residual.acknowledge' AND movement.id=ack.posting_id AND movement.state='APPLIED') OR
            NOT EXISTS (SELECT FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=ack.posting_id
                AND direction='OUT' AND stock_identity_id=residual.transit_identity_id AND quantity_base=residual.quantity_base
                AND status='IN_TRANSIT' AND condition='QUARANTINE' AND custody_owner_id=residual.sender_id) OR
            NOT EXISTS (SELECT FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=ack.posting_id
                AND direction='IN' AND stock_identity_id=residual.transit_identity_id AND quantity_base=residual.quantity_base
                AND status='QUARANTINE' AND condition='QUARANTINE' AND custody_owner_kind='WAREHOUSE'
                AND location_id=residual.target_location_id AND custody_owner_id=residual.target_location_id) OR
            (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=ack.posting_id)<>2 THEN
            RAISE EXCEPTION 'residual receipt requires independent acknowledgement and exact paired posting' USING ERRCODE='23514';
        END IF;
    ELSIF document.state<>'DISPATCHED' OR document.revision<>1 THEN
        RAISE EXCEPTION 'residual custody cannot advance without acknowledgement' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_material_lifecycle_insert_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE prior inventory_material_lifecycle%ROWTYPE;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_material_lifecycle' THEN
        PERFORM id FROM work_order WHERE tenant_id=NEW.tenant_id AND id=NEW.work_order_id FOR UPDATE;
        SELECT * INTO prior FROM inventory_material_lifecycle WHERE tenant_id=NEW.tenant_id AND work_order_id=NEW.work_order_id ORDER BY revision DESC LIMIT 1;
        IF NEW.created_xid<>pg_current_xact_id() OR NEW.revision<>coalesce(prior.revision,0)+1 OR NEW.previous_id IS DISTINCT FROM prior.id OR
            NOT EXISTS (SELECT FROM work_order WHERE tenant_id=NEW.tenant_id AND id=NEW.work_order_id AND warehouse_revision=NEW.work_order_revision) OR
            (prior.id IS NOT NULL AND NEW.due_at<>prior.due_at) THEN
            RAISE EXCEPTION 'lifecycle must append to locked current work order revision' USING ERRCODE='23514';
        END IF;
        IF NEW.material_state='CLOSED' AND (NEW.action<>'CLOSE' OR EXISTS (
            SELECT FROM warehouse_material_obligation_totals(NEW.tenant_id,NEW.work_order_id) WHERE accountable_base>0 OR returned_base>0) OR EXISTS (
            SELECT FROM inventory_reservation reservation JOIN inventory_document_line line ON line.tenant_id=reservation.tenant_id AND line.id=reservation.document_line_id
            JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
            WHERE reservation.tenant_id=NEW.tenant_id AND document.work_order_id=NEW.work_order_id
                AND reservation.reserved_unpicked_base+reservation.reserved_picked_base>0)) THEN
            RAISE EXCEPTION 'material obligations prevent closure' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME IN ('inventory_material_obligation_snapshot','inventory_material_cancel_release') THEN
        IF NOT EXISTS (SELECT FROM inventory_material_lifecycle WHERE tenant_id=NEW.tenant_id AND id=NEW.lifecycle_id AND created_xid=pg_current_xact_id()) THEN
            RAISE EXCEPTION 'lifecycle children must be sealed in origin transaction' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.created_xid<>pg_current_xact_id() THEN RAISE EXCEPTION 'residual transaction mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION warehouse_material_lifecycle_final_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE target uuid; header inventory_material_lifecycle%ROWTYPE;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME IN ('inventory_material_residual','inventory_material_residual_ack') THEN
        target:=CASE WHEN TG_TABLE_NAME='inventory_material_residual' THEN NEW.id ELSE NEW.residual_id END;
        PERFORM warehouse_assert_material_residual(NEW.tenant_id,target);
    ELSIF TG_TABLE_NAME='inventory_document' THEN
        IF EXISTS (SELECT FROM inventory_material_residual WHERE tenant_id=NEW.tenant_id AND id=NEW.id) THEN
            PERFORM warehouse_assert_material_residual(NEW.tenant_id,NEW.id);
        END IF;
    ELSIF TG_TABLE_NAME='inventory_material_cancel_release' THEN
        IF NOT EXISTS (SELECT FROM inventory_material_lifecycle lifecycle JOIN inventory_reservation reservation ON reservation.tenant_id=lifecycle.tenant_id
            JOIN inventory_document_line line ON line.tenant_id=reservation.tenant_id AND line.id=reservation.document_line_id
            JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
            JOIN work_order work ON work.tenant_id=document.tenant_id AND work.id=document.work_order_id
            WHERE lifecycle.tenant_id=NEW.tenant_id AND lifecycle.id=NEW.lifecycle_id AND lifecycle.action='CANCEL'
            AND lifecycle.work_order_id=document.work_order_id AND work.status='CANCELLED' AND reservation.id=NEW.reservation_id
            AND reservation.stock_identity_id=NEW.stock_identity_id AND reservation.revision=NEW.source_revision+1
            AND reservation.state='RELEASED' AND reservation.reserved_unpicked_base=0 AND reservation.reserved_picked_base=0) THEN
            RAISE EXCEPTION 'cancellation release requires exact cancelled work order and released reservation' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME='inventory_material_lifecycle' THEN
        IF (SELECT count(*) FROM inventory_material_obligation_snapshot WHERE tenant_id=NEW.tenant_id AND lifecycle_id=NEW.id)
            <>jsonb_array_length(NEW.body::jsonb->'lines') THEN
            RAISE EXCEPTION 'lifecycle obligation snapshots incomplete' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_material_lifecycle','inventory_material_obligation_snapshot',
        'inventory_material_residual','inventory_material_residual_ack','inventory_material_cancel_release'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_material_lifecycle_insert BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_material_lifecycle_insert_guard()',table_name);
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_material_lifecycle_final AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_lifecycle_final_guard()',table_name);
    END LOOP;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_material_residual_document AFTER UPDATE ON inventory_document
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_lifecycle_final_guard();
