ALTER TABLE inventory_document
    ADD COLUMN transfer_source_location_id uuid,
    ADD COLUMN transfer_destination_location_id uuid,
    ADD COLUMN transfer_transit_location_id uuid,
    ADD COLUMN transfer_receiver_id uuid,
    ADD FOREIGN KEY (tenant_id,transfer_source_location_id) REFERENCES inventory_location(tenant_id,id),
    ADD FOREIGN KEY (tenant_id,transfer_destination_location_id) REFERENCES inventory_location(tenant_id,id),
    ADD FOREIGN KEY (tenant_id,transfer_transit_location_id) REFERENCES inventory_location(tenant_id,id),
    ADD FOREIGN KEY (tenant_id,transfer_receiver_id) REFERENCES app_user(tenant_id,id),
    ADD CONSTRAINT warehouse_transfer_complete_binding CHECK (
        num_nonnulls(transfer_source_location_id,transfer_destination_location_id,transfer_transit_location_id,transfer_receiver_id)=0 OR
        (kind='TRANSFER' AND work_order_id IS NULL AND
        num_nonnulls(transfer_source_location_id,transfer_destination_location_id,transfer_transit_location_id,transfer_receiver_id)=4 AND
        transfer_source_location_id<>transfer_destination_location_id AND transfer_source_location_id<>transfer_transit_location_id AND
        transfer_destination_location_id<>transfer_transit_location_id));

CREATE INDEX warehouse_transfer_receiver_idx ON inventory_document(tenant_id,transfer_receiver_id,created_at,id)
    WHERE transfer_receiver_id IS NOT NULL;

CREATE FUNCTION warehouse_transfer_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(OLD.tenant_id);
    IF OLD.transfer_receiver_id IS NOT NULL AND (TG_OP='DELETE' OR
        to_jsonb(NEW)-ARRAY['state','revision','updated_at','closed_at'] IS DISTINCT FROM
        to_jsonb(OLD)-ARRAY['state','revision','updated_at','closed_at']) THEN
        RAISE EXCEPTION 'transfer binding is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_transfer_binding BEFORE UPDATE OR DELETE ON inventory_document
    FOR EACH ROW EXECUTE FUNCTION warehouse_transfer_binding_guard();

CREATE FUNCTION warehouse_assert_transfer(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE document inventory_document; operation inventory_operation; line inventory_document_line;
    snapshot jsonb; item jsonb; dispatched numeric; received numeric; remaining numeric; revision_count bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=target;
    IF document.transfer_receiver_id IS NULL THEN RETURN; END IF;
    SELECT count(*) INTO revision_count FROM inventory_operation WHERE tenant_id=scope AND document_id=target;
    IF revision_count<>document.revision+1 THEN RAISE EXCEPTION 'transfer operation revision gap' USING ERRCODE='23514'; END IF;
    FOR operation IN SELECT * FROM inventory_operation WHERE tenant_id=scope AND document_id=target ORDER BY document_revision LOOP
        snapshot:=operation.original_body::jsonb;
        IF operation.namespace NOT IN ('warehouse.transfer.create','warehouse.transfer.dispatch','warehouse.transfer.receive') OR
            operation.resource_id<>target OR operation.resource_scope<>'transfer:'||target OR
            snapshot->>'id' IS DISTINCT FROM target::text OR (snapshot->>'revision')::bigint<>operation.document_revision OR
            snapshot->>'sourceLocationId' IS DISTINCT FROM document.transfer_source_location_id::text OR
            snapshot->>'destinationLocationId' IS DISTINCT FROM document.transfer_destination_location_id::text OR
            snapshot->>'transitLocationId' IS DISTINCT FROM document.transfer_transit_location_id::text OR
            snapshot->>'senderId' IS DISTINCT FROM document.actor_id::text OR
            snapshot->>'receiverId' IS DISTINCT FROM document.transfer_receiver_id::text OR
            operation.actor_id<>(CASE WHEN operation.namespace='warehouse.transfer.receive' THEN document.transfer_receiver_id ELSE document.actor_id END) OR
            NOT EXISTS(SELECT FROM inventory_command_identity WHERE tenant_id=scope AND id=operation.id) THEN
            RAISE EXCEPTION 'transfer operation binding mismatch' USING ERRCODE='23514';
        END IF;
        IF operation.document_revision=0 THEN
            IF operation.namespace<>'warehouse.transfer.create' OR snapshot->>'state'<>'DRAFT' OR
                EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id) THEN
                RAISE EXCEPTION 'transfer draft cannot move stock' USING ERRCODE='23514'; END IF;
        ELSE
            IF (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id
                AND document_id=target AND document_revision=operation.document_revision AND kind='TRANSFER' AND state='APPLIED')<>1 OR
                NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=operation.id AND document_id=target) THEN
                RAISE EXCEPTION 'transfer requires exact posting and outbox' USING ERRCODE='23514'; END IF;
        END IF;
    END LOOP;
    IF snapshot->>'state' IS DISTINCT FROM document.state OR
        jsonb_array_length(snapshot->'lines')<>(SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=target) THEN
        RAISE EXCEPTION 'transfer snapshot does not match document' USING ERRCODE='23514'; END IF;
    FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP
        SELECT value INTO item FROM jsonb_array_elements(snapshot->'lines') WHERE value->>'id'=line.id::text;
        IF item IS NULL OR item->>'stockIdentityId' IS DISTINCT FROM line.stock_identity_id::text OR
            item->>'skuId' IS DISTINCT FROM line.sku_id::text OR item->>'baseUnit' IS DISTINCT FROM line.base_unit OR
            (item->>'quantityBase')::bigint IS DISTINCT FROM line.quantity_base OR
            item->>'condition' IS DISTINCT FROM line.condition OR item->>'legalOwner' IS DISTINCT FROM line.legal_owner OR
            line.location_id<>document.transfer_source_location_id OR line.destination_location_id<>document.transfer_destination_location_id THEN
            RAISE EXCEPTION 'transfer line binding mismatch' USING ERRCODE='23514'; END IF;
        SELECT coalesce(sum(leg.quantity_base),0) INTO dispatched FROM inventory_movement_leg leg JOIN inventory_movement movement
            ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            WHERE leg.tenant_id=scope AND movement.document_id=target AND movement.operation_namespace='warehouse.transfer.dispatch'
            AND leg.document_line_id=line.id AND leg.direction='IN' AND leg.location_id=document.transfer_transit_location_id;
        SELECT coalesce(sum(leg.quantity_base),0) INTO received FROM inventory_movement_leg leg JOIN inventory_movement movement
            ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            WHERE leg.tenant_id=scope AND movement.document_id=target AND movement.operation_namespace='warehouse.transfer.receive'
            AND leg.document_line_id=line.id AND leg.direction='IN' AND leg.location_id=document.transfer_destination_location_id;
        remaining:=dispatched-received;
        IF dispatched<>(CASE WHEN document.state='DRAFT' THEN 0 ELSE line.quantity_base END) OR
            received<0 OR remaining<0 OR (item->>'receivedBase')::numeric<>received OR
            (item->>'inTransitBase')::numeric<>remaining OR
            (document.state='RECEIVED' AND remaining<>0) THEN
            RAISE EXCEPTION 'transfer quantities are not conserved' USING ERRCODE='23514'; END IF;
        IF EXISTS(SELECT FROM inventory_movement_leg leg JOIN inventory_movement movement
            ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            WHERE leg.tenant_id=scope AND movement.document_id=target AND leg.document_line_id=line.id AND
            (leg.condition<>line.condition OR leg.legal_owner<>line.legal_owner OR
            (leg.location_id=document.transfer_transit_location_id AND (leg.custody_owner_id<>target OR leg.custody_owner_kind<>'TRANSIT' OR leg.status<>'IN_TRANSIT')))) THEN
            RAISE EXCEPTION 'transfer cannot change owner condition or transit custody' USING ERRCODE='23514'; END IF;
    END LOOP;
    IF (SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection
        WHERE tenant_id=scope AND location_id=document.transfer_transit_location_id AND custody_owner_id=target)
        <> (SELECT coalesce(sum((value->>'inTransitBase')::numeric),0) FROM jsonb_array_elements(snapshot->'lines')) THEN
        RAISE EXCEPTION 'transfer transit projection mismatch' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_transfer_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE data jsonb; scope uuid; target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(coalesce(NEW.tenant_id,OLD.tenant_id));
    data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(data->>'tenant_id')::uuid;
    target:=CASE TG_TABLE_NAME
        WHEN 'inventory_document' THEN (data->>'id')::uuid
        WHEN 'inventory_command_identity' THEN (SELECT document_id FROM inventory_operation WHERE tenant_id=scope AND id=(data->>'id')::uuid)
        WHEN 'inventory_movement_leg' THEN (SELECT document_id FROM inventory_movement WHERE tenant_id=scope AND id=(data->>'movement_id')::uuid)
        WHEN 'inventory_balance_projection' THEN (data->>'custody_owner_id')::uuid
        ELSE (data->>'document_id')::uuid END;
    IF target IS NOT NULL THEN PERFORM warehouse_assert_transfer(scope,target); END IF;
    RETURN NULL;
END $$;

DO $$ DECLARE relation text;
BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_document','inventory_document_line','inventory_operation','inventory_command_identity',
        'inventory_movement','inventory_movement_leg','inventory_balance_projection'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_transfer_final AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_transfer_final_guard()',relation);
    END LOOP;
END $$;
