CREATE FUNCTION warehouse_assert_asset_removal(scope uuid, removal_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE removal inventory_asset_removal; origin inventory_asset_removal_origin; assignment inventory_asset_assignment;
    asset inventory_serialized_asset; source_asset inventory_serialized_asset; source_assignment inventory_asset_assignment;
    movement inventory_movement; operation inventory_operation; outgoing inventory_movement_leg; incoming inventory_movement_leg;
    retirement customer_asset_retirement; episode customer_asset_installation; replacement customer_asset_installation;
    outbox fulfillment_asset_outbox; permit inventory_deployment_authorization; body jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO removal FROM inventory_asset_removal WHERE tenant_id=scope AND id=$2;
    IF NOT FOUND THEN RAISE EXCEPTION 'ASSET_REMOVAL_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO origin FROM inventory_asset_removal_origin WHERE tenant_id=scope AND inventory_asset_removal_origin.removal_id=$2;
    SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=scope AND id=removal.assignment_id;
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=scope AND id=removal.asset_id;
    source_asset:=jsonb_populate_record(NULL::inventory_serialized_asset,origin.asset_snapshot);
    source_assignment:=jsonb_populate_record(NULL::inventory_asset_assignment,origin.assignment_snapshot);
    IF origin.removal_id IS NULL OR assignment.id IS NULL OR asset.id IS NULL
        OR assignment.asset_id<>asset.id OR assignment.customer_id<>removal.customer_id
        OR assignment.ended_at IS DISTINCT FROM removal.removed_at OR assignment.revision<>removal.source_assignment_revision+1
        OR source_assignment.revision<>removal.source_assignment_revision OR source_asset.revision<>removal.source_asset_revision
        OR source_assignment.ended_at IS NOT NULL OR source_asset.status<>'CUSTOMER_INSTALLED'
        OR to_jsonb(assignment)-ARRAY['ended_at','revision'] IS DISTINCT FROM to_jsonb(source_assignment)-ARRAY['ended_at','revision']
        OR to_jsonb(asset)-ARRAY['location_id','custody_owner_id','custody_owner_kind','status','condition','revision'] IS DISTINCT FROM
            to_jsonb(source_asset)-ARRAY['location_id','custody_owner_id','custody_owner_kind','status','condition','revision']
        OR source_asset.legal_owner<>removal.legal_owner OR removal.legal_owner<>warehouse_asset_expected_owner(scope,assignment.id)
        OR source_asset.location_id<>removal.source_location_id OR source_asset.custody_owner_id<>removal.customer_id
        OR asset.revision<>removal.source_asset_revision+1 OR asset.status<>'QUARANTINE' OR asset.condition<>'QUARANTINE'
        OR asset.location_id<>removal.recovery_location_id OR asset.custody_owner_id<>removal.actor_id OR asset.custody_owner_kind<>'TRANSIT'
        OR origin.origin_snapshot IS DISTINCT FROM warehouse_asset_origin_snapshot(scope,asset.id)
        OR NOT EXISTS(SELECT FROM inventory_asset_assignment_history history WHERE history.tenant_id=scope
            AND history.assignment_id=assignment.id AND history.revision=removal.source_assignment_revision
            AND to_jsonb(jsonb_populate_record(NULL::inventory_asset_assignment,history.snapshot))=to_jsonb(source_assignment)) THEN
        RAISE EXCEPTION 'ASSET_REMOVAL_HISTORY_POSITION_BINDING' USING ERRCODE='23514'; END IF;
    IF (SELECT count(*) FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=asset.id AND quantity_base>0)<>1
        OR NOT EXISTS(SELECT FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=asset.id AND quantity_base=1
            AND base_unit='EA' AND location_id=removal.recovery_location_id AND custody_owner_id=removal.actor_id AND custody_owner_kind='TRANSIT'
            AND legal_owner=removal.legal_owner AND status='QUARANTINE' AND condition='QUARANTINE') THEN
        RAISE EXCEPTION 'ASSET_RECOVERY_UNAVAILABLE' USING ERRCODE='23514'; END IF;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=scope AND id=removal.id;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=removal.id;
    SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
    SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
    IF operation.id IS NULL OR movement.id IS NULL OR operation.namespace<>'warehouse.asset.remove'
        OR operation.resource_id<>assignment.id OR operation.actor_id<>removal.actor_id OR operation.operation_key<>removal.operation_key
        OR operation.payload_hash<>removal.payload_hash OR operation.original_body<>removal.result
        OR operation.authority_epoch<>removal.authority_epoch OR operation.cutover_epoch<>removal.cutover_epoch
        OR movement.kind<>'RETURN' OR movement.state<>'APPLIED' OR movement.document_id<>removal.id
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=removal.id)<>1
        OR (SELECT count(*) FROM inventory_operation WHERE tenant_id=scope AND document_id=removal.id)<>1
        OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
        OR (SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=removal.id)<>1
        OR NOT EXISTS(SELECT FROM inventory_document WHERE tenant_id=scope AND id=removal.id AND kind='ASSET_REMOVAL' AND state='POSTED'
            AND customer_id=removal.customer_id AND work_order_id=removal.work_order_id AND actor_id=removal.actor_id
            AND work_order_revision=removal.work_order_revision AND authority_epoch=removal.authority_epoch AND cutover_epoch=removal.cutover_epoch)
        OR NOT EXISTS(SELECT FROM inventory_document_line WHERE tenant_id=scope AND id=removal.id AND document_id=removal.id
            AND source_line_id=assignment.id AND stock_identity_id=asset.id AND quantity_base=1 AND base_unit='EA' AND tracking='SERIAL')
        OR (outgoing.stock_identity_id,outgoing.sku_id,outgoing.quantity_base,outgoing.base_unit,outgoing.location_id,
            outgoing.custody_owner_id,outgoing.custody_owner_kind,outgoing.status,outgoing.condition,outgoing.legal_owner,outgoing.document_line_id)
            IS DISTINCT FROM (asset.id,asset.sku_id,1::bigint,'EA'::text,removal.source_location_id,removal.customer_id,
                'CUSTOMER'::varchar,'CUSTOMER_INSTALLED'::varchar,'SERVICEABLE'::text,removal.legal_owner,removal.id)
        OR (incoming.stock_identity_id,incoming.sku_id,incoming.quantity_base,incoming.base_unit,incoming.location_id,
            incoming.custody_owner_id,incoming.custody_owner_kind,incoming.status,incoming.condition,incoming.legal_owner,incoming.document_line_id)
            IS DISTINCT FROM (asset.id,asset.sku_id,1::bigint,'EA'::text,removal.recovery_location_id,removal.actor_id,
                'TRANSIT'::varchar,'QUARANTINE'::varchar,'QUARANTINE'::text,removal.legal_owner,removal.id)
        OR incoming.lot_id IS NOT NULL OR outgoing.lot_id IS NOT NULL THEN
        RAISE EXCEPTION 'ASSET_REMOVAL_EXACT_POSTING' USING ERRCODE='23514'; END IF;
    SELECT * INTO episode FROM customer_asset_installation WHERE tenant_id=scope AND assignment_id=assignment.id;
    SELECT * INTO retirement FROM customer_asset_retirement WHERE tenant_id=scope AND customer_asset_retirement.removal_id=removal.id;
    SELECT * INTO outbox FROM fulfillment_asset_outbox WHERE tenant_id=scope AND operation_id=removal.id;
    body:=removal.result::jsonb;
    IF episode.id IS NULL OR retirement.removal_id IS NULL OR outbox.operation_id IS NULL
        OR retirement.episode_id<>episode.id OR retirement.onu_id IS DISTINCT FROM episode.onu_id OR retirement.retired_at<>removal.removed_at
        OR (body->>'operationId')::uuid IS DISTINCT FROM removal.id OR (body->>'assignmentId')::uuid IS DISTINCT FROM assignment.id
        OR (body->>'assetId')::uuid IS DISTINCT FROM asset.id OR (body->>'customerId')::uuid IS DISTINCT FROM removal.customer_id
        OR (body->>'workOrderId')::uuid IS DISTINCT FROM removal.work_order_id OR body->>'legalOwner' IS DISTINCT FROM removal.legal_owner
        OR (body->>'removedAt')::timestamptz IS DISTINCT FROM removal.removed_at
        OR (retirement.response::jsonb->>'retiredAt')::timestamptz IS DISTINCT FROM removal.removed_at
        OR retirement.response::jsonb-ARRAY['retiredAt','episodeRevision','legalOwner'] IS DISTINCT FROM episode.response::jsonb-ARRAY['retiredAt','episodeRevision','legalOwner']
        OR outbox.customer_id<>removal.customer_id OR outbox.work_order_id<>removal.work_order_id OR outbox.old_onu_id IS DISTINCT FROM episode.onu_id
        OR NOT EXISTS(SELECT FROM fulfillment_asset_delivery WHERE tenant_id=scope AND operation_id=removal.id)
        OR (episode.onu_id IS NOT NULL AND NOT EXISTS(SELECT FROM onu WHERE tenant_id=scope AND id=episode.onu_id AND asset_id=asset.id
            AND assignment_id=assignment.id AND customer_id=removal.customer_id AND retired_at=removal.removed_at AND status='DISMANTLED'
            AND odp_id IS NULL AND odp_port_number IS NULL)) THEN
        RAISE EXCEPTION 'ASSET_REMOVAL_CUSTOMER_OUTBOX_BINDING' USING ERRCODE='23514'; END IF;
    IF removal.authorization_id IS NULL THEN
        IF outbox.new_onu_id IS NOT NULL OR body->'replacement'<>'null'::jsonb THEN
            RAISE EXCEPTION 'ASSET_DISMANTLE_HAS_REPLACEMENT' USING ERRCODE='23514'; END IF;
    ELSE
        SELECT * INTO permit FROM inventory_deployment_authorization WHERE tenant_id=scope AND id=removal.authorization_id;
        SELECT * INTO replacement FROM customer_asset_installation WHERE tenant_id=scope AND assignment_id=removal.replacement_assignment_id;
        IF permit.id IS NULL OR NOT permit.consumed OR permit.purpose<>'REPLACE' OR permit.previous_assignment_id<>assignment.id
            OR permit.expected_assignment_revision<>removal.source_assignment_revision OR permit.asset_id<>removal.replacement_asset_id
            OR permit.operation_id<>removal.replacement_assignment_id OR permit.customer_id<>removal.customer_id OR permit.work_order_id<>removal.work_order_id
            OR permit.actor_id<>removal.actor_id OR replacement.id IS NULL OR (replacement.onu_id IS NULL)<>(episode.onu_id IS NULL)
            OR outbox.new_onu_id IS DISTINCT FROM replacement.onu_id
            OR NOT EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=scope AND id=removal.replacement_assignment_id
                AND asset_id=removal.replacement_asset_id AND customer_id=removal.customer_id AND previous_assignment_id=assignment.id
                AND started_at=removal.removed_at)
            OR body->'replacement' IS DISTINCT FROM (SELECT result::jsonb FROM inventory_deployment_result
                WHERE tenant_id=scope AND authorization_id=permit.id) THEN
            RAISE EXCEPTION 'ASSET_SWAP_EXACT_REPLACEMENT' USING ERRCODE='23514'; END IF;
    END IF;
    IF NOT EXISTS(SELECT FROM wo_signature signed JOIN evidence_object_registry registry ON registry.tenant_id=signed.tenant_id AND registry.revision_id=signed.id
        WHERE signed.tenant_id=scope AND signed.id=removal.evidence_id AND signed.work_order_id=removal.work_order_id
        AND signed.sha256=removal.evidence_digest AND registry.expected_sha256=signed.sha256 AND registry.object_key=signed.storage_key
        AND signed.storage_key=origin.evidence_snapshot->>'storage_key') THEN
        RAISE EXCEPTION 'ASSET_REMOVAL_EVIDENCE_BINDING' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_asset_removal_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data jsonb; scope uuid; target uuid; removal uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(row_data->>'tenant_id')::uuid;
    FOR removal IN SELECT record.id FROM inventory_asset_removal record WHERE record.tenant_id=scope AND (
        record.id IN ((row_data->>'id')::uuid,(row_data->>'removal_id')::uuid,(row_data->>'operation_id')::uuid,(row_data->>'document_id')::uuid)
        OR record.asset_id IN ((row_data->>'id')::uuid,(row_data->>'asset_id')::uuid,(row_data->>'stock_identity_id')::uuid)
        OR record.assignment_id IN ((row_data->>'id')::uuid,(row_data->>'assignment_id')::uuid)
        OR record.replacement_asset_id IN ((row_data->>'id')::uuid,(row_data->>'asset_id')::uuid,(row_data->>'stock_identity_id')::uuid)
        OR record.replacement_assignment_id IN ((row_data->>'id')::uuid,(row_data->>'assignment_id')::uuid)
        OR record.authorization_id IN ((row_data->>'id')::uuid,(row_data->>'authorization_id')::uuid)
        OR record.evidence_id IN ((row_data->>'id')::uuid,(row_data->>'revision_id')::uuid)) LOOP
        PERFORM warehouse_assert_asset_removal(scope,removal);
    END LOOP;
    IF TG_TABLE_NAME='inventory_deployment_result' AND EXISTS(SELECT FROM inventory_deployment_authorization
        WHERE tenant_id=scope AND id=(row_data->>'authorization_id')::uuid AND purpose='REPLACE')
        AND NOT EXISTS(SELECT FROM inventory_asset_removal WHERE tenant_id=scope AND authorization_id=(row_data->>'authorization_id')::uuid) THEN
        RAISE EXCEPTION 'REPLACEMENT_REQUIRES_PHYSICAL_REMOVAL' USING ERRCODE='23514'; END IF;
    IF row_data->>'namespace'='warehouse.asset.remove' OR row_data->>'kind'='ASSET_REMOVAL' THEN
        target:=(row_data->>'id')::uuid;
        IF row_data->>'state' IS DISTINCT FROM 'DRAFT' AND NOT EXISTS(SELECT FROM inventory_asset_removal WHERE tenant_id=scope AND id=target) THEN
            RAISE EXCEPTION 'REMOVAL_OPERATION_REQUIRES_HISTORY' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NULL;
END $$;
DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_removal','inventory_asset_removal_origin','customer_asset_retirement','fulfillment_asset_outbox',
        'fulfillment_asset_delivery','inventory_asset_assignment','inventory_asset_assignment_history','inventory_serialized_asset','inventory_balance_projection',
        'inventory_document','inventory_document_line','inventory_operation','inventory_movement','inventory_movement_leg',
        'inventory_deployment_authorization','inventory_deployment_result','customer_asset_installation','onu','wo_signature','evidence_object_registry'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_asset_removal_final AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_asset_removal_final_guard()',table_name);
    END LOOP;
END $$;

CREATE FUNCTION warehouse_asset_delivery_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'ASSET_DELIVERY_HISTORY_REQUIRED' USING ERRCODE='23514'; END IF;
    IF TG_OP='UPDATE' AND ((NEW.tenant_id,NEW.operation_id) IS DISTINCT FROM (OLD.tenant_id,OLD.operation_id)
        OR NEW.revision<>OLD.revision+1 OR OLD.state='SUCCEEDED' OR NEW.attempts<OLD.attempts) THEN
        RAISE EXCEPTION 'ASSET_DELIVERY_TRANSITION_INVALID' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_asset_delivery BEFORE INSERT OR UPDATE OR DELETE ON fulfillment_asset_delivery
    FOR EACH ROW EXECUTE FUNCTION warehouse_asset_delivery_guard();
