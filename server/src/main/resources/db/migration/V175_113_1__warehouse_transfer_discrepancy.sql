ALTER TABLE inventory_document
    ADD COLUMN transfer_remainder_action varchar(16),
    ADD COLUMN transfer_remainder_request text,
    ADD CONSTRAINT warehouse_transfer_remainder_binding CHECK (
        (transfer_remainder_action IS NULL AND transfer_remainder_request IS NULL) OR
        (kind='ADJUSTMENT' AND transfer_remainder_action IN ('LOST','REJECTED') AND
        transfer_remainder_request IS NOT NULL AND source_document_id IS NOT NULL AND source_revision IS NOT NULL));

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF strpos(definition,'permitted := CASE OLD.kind')=0 THEN RAISE EXCEPTION 'document lifecycle anchor missing'; END IF;
    EXECUTE replace(definition,'permitted := CASE OLD.kind',$body$
    IF OLD.transfer_remainder_action IS NOT NULL AND OLD.state='DRAFT' AND NEW.state='POSTED' AND EXISTS (
        SELECT FROM inventory_approval approval WHERE approval.tenant_id=NEW.tenant_id AND approval.source_document_id=NEW.id
        AND approval.source_document_revision=OLD.revision AND approval.status='APPROVED') THEN RETURN NEW; END IF;
    permitted := CASE OLD.kind$body$);
    definition:=pg_get_functiondef('warehouse_approval_posting_guard()'::regprocedure);
    IF strpos(definition,'AND ((approval.business_action=''RECEIPT''')=0 THEN RAISE EXCEPTION 'approval posting anchor missing'; END IF;
    EXECUTE replace(definition,'AND ((approval.business_action=''RECEIPT''',$body$AND ((approval.business_action='ADJUSTMENT' AND NEW.kind='TRANSFER' AND EXISTS (
        SELECT FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id AND transfer_remainder_action IS NOT NULL)) OR
        (approval.business_action='RECEIPT'$body$);
END $$;

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_transfer(uuid,uuid)'::regprocedure);
    IF strpos(definition,'''warehouse.transfer.receive'') OR')=0 THEN RAISE EXCEPTION 'transfer namespace anchor missing'; END IF;
    definition:=replace(definition,'''warehouse.transfer.receive'') OR',
        '''warehouse.transfer.receive'',''warehouse.transfer.discrepancy'',''warehouse.transfer.resolve'') OR');
    definition:=replace(definition,'operation.actor_id<>(CASE WHEN operation.namespace=''warehouse.transfer.receive'' THEN document.transfer_receiver_id ELSE document.actor_id END) OR',
        '(operation.namespace<>''warehouse.transfer.resolve'' AND operation.actor_id<>(CASE WHEN operation.namespace IN (''warehouse.transfer.receive'',''warehouse.transfer.discrepancy'') THEN document.transfer_receiver_id ELSE document.actor_id END)) OR');
    definition:=replace(definition,'IF operation.document_revision=0 THEN',$body$
        IF operation.namespace IN ('warehouse.transfer.discrepancy','warehouse.transfer.resolve') THEN
            IF snapshot->>'state'<>'DISCREPANCY' OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id) OR
                NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=operation.id) THEN
                RAISE EXCEPTION 'discrepancy observation must not post stock' USING ERRCODE='23514'; END IF;
            IF operation.namespace='warehouse.transfer.resolve' AND NOT EXISTS (
                SELECT FROM inventory_approval approval JOIN inventory_operation effect ON effect.tenant_id=approval.tenant_id
                AND effect.operation_key=approval.id::text AND effect.namespace='warehouse.approval.effect'
                WHERE approval.tenant_id=scope AND approval.id::text=operation.operation_key AND approval.status='APPROVED'
                AND effect.actor_id=operation.actor_id AND approval.source_document_id=(snapshot->>'resolutionDocumentId')::uuid) THEN
                RAISE EXCEPTION 'remainder resolution requires independent approval effect' USING ERRCODE='23514'; END IF;
        ELSIF operation.document_revision=0 THEN$body$);
    definition:=replace(definition,'remaining:=dispatched-received;',
        'remaining:=dispatched-received-coalesce((item->>''resolvedBase'')::numeric,0);');
    EXECUTE definition;
END $$;

CREATE FUNCTION warehouse_assert_transfer_resolution(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE document inventory_document; parent inventory_document; approval inventory_approval; line inventory_document_line;
    outgoing inventory_movement_leg; incoming inventory_movement_leg; movement inventory_movement;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=target;
    IF document.transfer_remainder_action IS NULL THEN RETURN; END IF;
    SELECT * INTO parent FROM inventory_document WHERE tenant_id=scope AND id=document.source_document_id;
    IF parent.id IS NULL OR parent.transfer_receiver_id IS NULL OR document.actor_id<>parent.transfer_receiver_id OR parent.state<>'DISCREPANCY' THEN
        RAISE EXCEPTION 'remainder requires receiver-bound transfer' USING ERRCODE='23514'; END IF;
    IF document.state='DRAFT' THEN RETURN; END IF;
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=scope AND source_document_id=target AND status='APPROVED';
    IF approval.id IS NULL OR approval.source_document_revision<>0 OR document.state<>'POSTED' OR document.revision<>1 OR
        approval.business_action<>'ADJUSTMENT' OR EXISTS(SELECT FROM inventory_approval_decision WHERE tenant_id=scope AND approval_id=approval.id
        AND (approver_id IN (parent.actor_id,parent.transfer_receiver_id) OR delegated_from IN (parent.actor_id,parent.transfer_receiver_id))) THEN
        RAISE EXCEPTION 'independent remainder approval required' USING ERRCODE='23514'; END IF;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND document_id=target;
    IF movement.id IS NULL OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND document_id=target)<>1 OR
        movement.operation_namespace<>'warehouse.approval.effect' OR movement.operation_key<>approval.id::text OR movement.kind<>'TRANSFER' THEN
        RAISE EXCEPTION 'remainder posting binding mismatch' USING ERRCODE='23514'; END IF;
    FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP
        SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id AND direction='OUT';
        SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id AND direction='IN';
        IF outgoing.id IS NULL OR incoming.id IS NULL OR
            (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id)<>2 OR
            outgoing.stock_identity_id<>line.stock_identity_id OR incoming.stock_identity_id<>line.stock_identity_id OR
            outgoing.quantity_base<>line.quantity_base OR incoming.quantity_base<>line.quantity_base OR
            outgoing.location_id<>parent.transfer_transit_location_id OR outgoing.custody_owner_id<>parent.id OR outgoing.status<>'IN_TRANSIT' OR
            incoming.location_id<>line.destination_location_id OR incoming.status<>(CASE WHEN document.transfer_remainder_action='LOST' THEN 'LOST' ELSE 'QUARANTINE' END) OR
            incoming.legal_owner<>outgoing.legal_owner OR incoming.condition<>outgoing.condition THEN
            RAISE EXCEPTION 'remainder exact paired custody required' USING ERRCODE='23514'; END IF;
    END LOOP;
END $$;

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_transfer_final_guard()'::regprocedure);
    EXECUTE replace(definition,'IF target IS NOT NULL THEN PERFORM warehouse_assert_transfer(scope,target); END IF;',
        'IF target IS NOT NULL THEN PERFORM warehouse_assert_transfer(scope,target); PERFORM warehouse_assert_transfer_resolution(scope,target); END IF;');
END $$;
