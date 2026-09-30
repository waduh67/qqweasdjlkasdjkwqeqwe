CREATE FUNCTION warehouse_approval_posting_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.operation_namespace='warehouse.approval.effect' AND NOT EXISTS (
        SELECT FROM inventory_approval approval JOIN inventory_operation operation
            ON operation.tenant_id=approval.tenant_id AND operation.id=NEW.operation_id
        WHERE approval.tenant_id=NEW.tenant_id AND approval.id::text=NEW.operation_key
            AND approval.source_document_id=NEW.document_id AND approval.source_document_revision+1=NEW.document_revision
            AND approval.status='APPROVED' AND approval.expires_at>clock_timestamp()
            AND approval.business_action='RECEIPT' AND NEW.kind='RECEIVE'
            AND operation.namespace='warehouse.approval.effect' AND operation.resource_id=approval.source_document_id
            AND operation.payload_hash=approval.source_snapshot_hash
    ) THEN
        RAISE EXCEPTION 'posting requires live document-bound approval' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_approval_posting BEFORE INSERT ON inventory_movement FOR EACH ROW EXECUTE FUNCTION warehouse_approval_posting_guard();

CREATE FUNCTION warehouse_approval_terminal_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE approval inventory_approval%ROWTYPE; expected_count integer; approved_count integer;
BEGIN
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
CREATE CONSTRAINT TRIGGER warehouse_approval_terminal AFTER INSERT OR UPDATE ON inventory_approval
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_approval_terminal_guard();

CREATE FUNCTION warehouse_approval_effect_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.source_document_id IS NULL OR NEW.posting_operation_id IS NULL OR NEW.event_id IS NULL OR NEW.movement_id IS NOT NULL OR NOT EXISTS (
        SELECT FROM inventory_approval approval WHERE approval.tenant_id=NEW.tenant_id AND approval.id=NEW.approval_id
            AND approval.status='APPROVED' AND NEW.status='APPROVED' AND approval.source_document_id=NEW.source_document_id
            AND approval.source_document_revision=NEW.source_document_revision AND approval.terminal_body=NEW.original_body
    ) THEN RAISE EXCEPTION 'effect must derive from approved source revision' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_approval_effect_binding BEFORE INSERT ON inventory_approval_effect FOR EACH ROW EXECUTE FUNCTION warehouse_approval_effect_guard();
