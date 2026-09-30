CREATE OR REPLACE FUNCTION warehouse_approval_terminal_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE approval inventory_approval%ROWTYPE; expected_count integer; approved_count integer;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
    IF approval.evaluation_snapshot IS NULL THEN RETURN NEW; END IF;
    SELECT count(*) INTO expected_count FROM inventory_approval_requirement WHERE tenant_id=approval.tenant_id AND approval_id=approval.id;
    IF expected_count=0 THEN RAISE EXCEPTION 'approval requires sealed tiers' USING ERRCODE='23514'; END IF;
    IF approval.status='APPROVED' THEN
        SELECT count(*) INTO approved_count FROM inventory_approval_decision WHERE tenant_id=approval.tenant_id AND approval_id=approval.id AND decision='APPROVE';
        IF approved_count<>expected_count OR NOT EXISTS (
            SELECT FROM inventory_approval_effect effect
            JOIN inventory_movement movement ON movement.tenant_id=effect.tenant_id AND movement.operation_id=effect.posting_operation_id
            JOIN inventory_outbox event ON event.tenant_id=effect.tenant_id AND event.id=effect.event_id AND event.operation_id=effect.posting_operation_id
            JOIN inventory_inbox inbox ON inbox.tenant_id=event.tenant_id AND inbox.event_id=event.id AND inbox.consumer='warehouse.approval.receipt'
            WHERE effect.tenant_id=approval.tenant_id AND effect.approval_id=approval.id
                AND effect.source_document_id=approval.source_document_id AND effect.source_document_revision=approval.source_document_revision
                AND effect.original_body=approval.terminal_body AND movement.state='APPLIED'
                AND movement.document_id=approval.source_document_id AND movement.document_revision=approval.source_document_revision+1
        ) THEN RAISE EXCEPTION 'approved outcome requires atomic owner posting and delivery receipts' USING ERRCODE='23514'; END IF;
    ELSIF EXISTS(SELECT FROM inventory_approval_effect WHERE tenant_id=approval.tenant_id AND approval_id=approval.id) THEN
        RAISE EXCEPTION 'non-approved request cannot have an effect' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
