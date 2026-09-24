ALTER TABLE inventory_return_case DROP CONSTRAINT inventory_return_case_origin_check,
    ADD CONSTRAINT warehouse_return_origin CHECK(origin IN ('MATERIAL_RESIDUAL','ASSET_REMOVAL')),
    ADD COLUMN recovery_snapshot jsonb,
    ADD CONSTRAINT warehouse_return_recovery_snapshot CHECK((origin='ASSET_REMOVAL')=(recovery_snapshot IS NOT NULL));

CREATE FUNCTION warehouse_capture_returned_asset() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE removal inventory_asset_removal; physical inventory_serialized_asset; balance inventory_balance_projection;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.origin<>'ASSET_REMOVAL' THEN RETURN NEW; END IF;
    SELECT * INTO removal FROM inventory_asset_removal WHERE tenant_id=NEW.tenant_id AND id=NEW.source_document_id;
    IF removal.id IS NULL OR removal.asset_id<>NEW.stock_identity_id THEN
        RAISE EXCEPTION 'RETURN_REMOVAL_REQUIRED' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_asset_removal(NEW.tenant_id,removal.id);
    SELECT * INTO physical FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=removal.asset_id FOR UPDATE;
    SELECT * INTO balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND stock_identity_id=physical.id
        AND quantity_base=1 AND location_id=removal.recovery_location_id AND custody_owner_id=removal.actor_id
        AND custody_owner_kind='TRANSIT' AND condition='QUARANTINE' AND status='QUARANTINE' AND legal_owner=removal.legal_owner FOR UPDATE;
    IF physical.id IS NULL OR balance.id IS NULL OR physical.revision<>removal.source_asset_revision+1
        OR physical.warehouse_admission<>'VERIFIED' OR balance.warehouse_admission<>'VERIFIED'
        OR NOT EXISTS(SELECT FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.id AND actor_id<>removal.actor_id)
        OR (NEW.body::jsonb->'source'->>'balanceRevision')::bigint IS DISTINCT FROM balance.revision THEN
        RAISE EXCEPTION 'RETURN_RECOVERY_CUSTODY_REQUIRED' USING ERRCODE='23514'; END IF;
    NEW.recovery_snapshot:=jsonb_build_object('asset',to_jsonb(physical),'balance',to_jsonb(balance));
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_return_capture BEFORE INSERT ON inventory_return_case
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_returned_asset();

CREATE FUNCTION warehouse_assert_returned_asset(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE intake inventory_return_case; document inventory_document; line inventory_document_line;
    removal inventory_asset_removal; origin inventory_asset_removal_origin; physical inventory_serialized_asset;
    balance inventory_balance_projection; operation inventory_operation; movement inventory_movement;
    outgoing inventory_movement_leg; incoming inventory_movement_leg;
    record jsonb; initial jsonb; previous jsonb; snapshot jsonb; command jsonb; canonical text; inspection jsonb;
    released boolean; expected_revision bigint:=0; outgoing_owner uuid; outgoing_kind text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO intake FROM inventory_return_case WHERE tenant_id=scope AND id=target AND origin='ASSET_REMOVAL';
    IF NOT FOUND THEN RAISE EXCEPTION 'RETURN_ASSET_CASE_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    SELECT * INTO removal FROM inventory_asset_removal WHERE tenant_id=scope AND id=intake.source_document_id;
    SELECT * INTO origin FROM inventory_asset_removal_origin WHERE tenant_id=scope AND removal_id=removal.id;
    physical:=jsonb_populate_record(NULL::inventory_serialized_asset,intake.recovery_snapshot->'asset');
    balance:=jsonb_populate_record(NULL::inventory_balance_projection,intake.recovery_snapshot->'balance');
    record:=intake.body::jsonb;
    initial:=record->'view';
    IF removal.id IS NULL OR origin.removal_id IS NULL OR physical.id IS NULL OR balance.id IS NULL
        OR document.kind<>'RETURN' OR document.source_document_id IS DISTINCT FROM removal.id OR document.source_revision<>1
        OR document.actor_id=removal.actor_id OR document.actor_id::text IS DISTINCT FROM initial->>'receivedBy'
        OR physical.id<>removal.asset_id OR physical.id<>intake.stock_identity_id OR physical.legal_owner<>removal.legal_owner
        OR physical.revision<>removal.source_asset_revision+1 OR physical.status<>'QUARANTINE' OR physical.condition<>'QUARANTINE'
        OR physical.location_id<>removal.recovery_location_id OR physical.custody_owner_id<>removal.actor_id OR physical.custody_owner_kind<>'TRANSIT'
        OR to_jsonb(physical)-ARRAY['location_id','custody_owner_id','custody_owner_kind','status','condition','revision'] IS DISTINCT FROM
            to_jsonb(jsonb_populate_record(NULL::inventory_serialized_asset,origin.asset_snapshot))-
                ARRAY['location_id','custody_owner_id','custody_owner_kind','status','condition','revision']
        OR (record->'intake'->>'sourceDocumentId')::uuid IS DISTINCT FROM removal.id
        OR record->'intake'->>'origin' IS DISTINCT FROM intake.origin
        OR (record->'intake'->>'quarantineLocationId')::uuid IS DISTINCT FROM intake.quarantine_location_id
        OR nullif(btrim(record->'intake'->>'evidenceReference'),'') IS NULL
        OR record->'source'->'dimension' IS DISTINCT FROM jsonb_build_object('skuId',physical.sku_id,'stockIdentityId',physical.id,
            'lotId',NULL,'locationId',physical.location_id,'custodianId',physical.custody_owner_id,
            'custodianKind','TRANSIT','condition','QUARANTINE','legalOwner',physical.legal_owner)
        OR (record->'source'->>'quantity')::bigint IS DISTINCT FROM 1::bigint OR record->'source'->>'unit' IS DISTINCT FROM 'EA'
        OR record->'source'->>'tracking' IS DISTINCT FROM 'SERIAL' OR record->'source'->>'serial' IS DISTINCT FROM physical.serial_number
        OR (record->'source'->>'balanceRevision')::bigint IS DISTINCT FROM balance.revision
        OR (balance.stock_identity_id,balance.sku_id,balance.quantity_base,balance.base_unit,balance.location_id,
            balance.custody_owner_id,balance.custody_owner_kind,balance.condition,balance.status,balance.legal_owner) IS DISTINCT FROM
            (physical.id,physical.sku_id,1::bigint,'EA'::varchar,physical.location_id,physical.custody_owner_id,
            'TRANSIT'::varchar,'QUARANTINE'::varchar,'QUARANTINE'::varchar,physical.legal_owner)
        OR (line.stock_identity_id,line.sku_id,line.quantity_base,line.base_unit,line.tracking,line.location_id,
            line.custodian_id,line.custodian_kind,line.condition,line.legal_owner,line.source_line_id) IS DISTINCT FROM
            (physical.id,physical.sku_id,1::bigint,'EA'::varchar,'SERIAL'::varchar,physical.location_id,physical.custody_owner_id,
            'TRANSIT'::varchar,'QUARANTINE'::varchar,physical.legal_owner,removal.id)
        OR line.lot_id IS NOT NULL OR (record->>'sourceLineId')::uuid IS DISTINCT FROM removal.id
        OR (initial->>'id')::uuid IS DISTINCT FROM target OR initial->>'state' IS DISTINCT FROM 'DRAFT'
        OR (initial->>'revision')::bigint IS DISTINCT FROM 0::bigint OR initial->>'origin' IS DISTINCT FROM intake.origin
        OR (initial->>'sourceDocumentId')::uuid IS DISTINCT FROM removal.id
        OR (initial->>'stockIdentityId')::uuid IS DISTINCT FROM physical.id OR (initial->>'skuId')::uuid IS DISTINCT FROM physical.sku_id
        OR initial->>'lotId' IS NOT NULL OR initial->>'baseUnit' IS DISTINCT FROM 'EA' OR initial->>'quantityBase' IS DISTINCT FROM '1'
        OR (initial->>'locationId')::uuid IS DISTINCT FROM physical.location_id
        OR initial->>'condition' IS DISTINCT FROM 'QUARANTINE' OR initial->>'legalOwner' IS DISTINCT FROM physical.legal_owner THEN
        RAISE EXCEPTION 'RETURN_ASSET_ORIGIN_BINDING' USING ERRCODE='23514';
    END IF;
    FOR operation IN SELECT * FROM inventory_operation WHERE tenant_id=scope AND document_id=target ORDER BY document_revision LOOP
        snapshot:=operation.original_body::jsonb;
        SELECT canonical_payload INTO canonical FROM inventory_command_identity WHERE tenant_id=scope AND id=operation.id;
        command:=canonical::jsonb;
        IF operation.document_revision<>expected_revision OR operation.resource_id<>target OR operation.resource_scope<>'return:'||target
            OR canonical IS NULL OR operation.payload_hash<>encode(sha256(convert_to(canonical,'UTF8')),'hex')
            OR (snapshot->>'revision')::bigint IS DISTINCT FROM expected_revision
            OR snapshot-ARRAY['revision','state','locationId','condition','recordedAt','inspection'] IS DISTINCT FROM
                initial-ARRAY['revision','state','locationId','condition','recordedAt','inspection'] THEN
            RAISE EXCEPTION 'RETURN_ASSET_OPERATION_BINDING' USING ERRCODE='23514'; END IF;
        IF expected_revision=0 THEN
            IF operation.namespace<>'warehouse.return.open' OR operation.actor_id<>document.actor_id
                OR command IS DISTINCT FROM record->'intake' OR snapshot IS DISTINCT FROM initial
                OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id) THEN
                RAISE EXCEPTION 'RETURN_ASSET_OPEN_BINDING' USING ERRCODE='23514'; END IF;
        ELSE
            released:=snapshot->>'state'='ACCEPTED';
            IF expected_revision=1 THEN
                IF operation.namespace<>'warehouse.return.receive' OR operation.actor_id<>document.actor_id
                    OR command IS DISTINCT FROM record->'intake' OR snapshot->>'condition' IS DISTINCT FROM 'QUARANTINE'
                    OR snapshot->>'state' IS DISTINCT FROM 'RECEIVED_IN_INSPECTION'
                    OR (snapshot->>'locationId')::uuid IS DISTINCT FROM intake.quarantine_location_id THEN
                    RAISE EXCEPTION 'RETURN_ASSET_RECEIPT_BINDING' USING ERRCODE='23514'; END IF;
                outgoing_owner:=removal.actor_id;
                outgoing_kind:='TRANSIT';
            ELSE
                outgoing_owner:=(previous->>'locationId')::uuid;
                outgoing_kind:='WAREHOUSE';
                inspection:=snapshot->'inspection';
                IF operation.namespace<>'warehouse.return.inspect' OR previous->>'state' IS DISTINCT FROM 'RECEIVED_IN_INSPECTION'
                    OR jsonb_typeof(inspection) IS DISTINCT FROM 'object'
                    OR command->>'id' IS DISTINCT FROM target::text OR command->'request' IS DISTINCT FROM inspection
                    OR (inspection->>'expectedRevision')::bigint IS DISTINCT FROM expected_revision-1
                    OR inspection->>'measuredQuantityBase' IS DISTINCT FROM '1'
                    OR nullif(btrim(inspection->>'evidenceReference'),'') IS NULL
                    OR snapshot->>'condition' IS DISTINCT FROM inspection->>'condition'
                    OR snapshot->>'locationId' IS DISTINCT FROM inspection->>'destinationLocationId'
                    OR snapshot->>'state' NOT IN ('ACCEPTED','RECEIVED_IN_INSPECTION')
                    OR snapshot->>'condition' NOT IN ('SERVICEABLE','QUARANTINE','DAMAGED')
                    OR released IS DISTINCT FROM (snapshot->>'condition'='SERVICEABLE' AND physical.legal_owner='ISP')
                    OR inspection->>'observedSerial' IS DISTINCT FROM physical.serial_number
                    OR snapshot->>'condition'='SERVICEABLE' AND (inspection->>'resetConfirmed' IS DISTINCT FROM 'true'
                        OR nullif(btrim(inspection->>'resetEvidenceReference'),'') IS NULL) THEN
                    RAISE EXCEPTION 'RETURN_ASSET_INSPECTION_BINDING' USING ERRCODE='23514'; END IF;
            END IF;
            IF NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=(snapshot->>'locationId')::uuid
                AND (CASE WHEN released THEN kind='BIN' ELSE kind='QUARANTINE' END)) THEN
                RAISE EXCEPTION 'RETURN_ASSET_DESTINATION' USING ERRCODE='23514'; END IF;
            SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id;
            SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
            SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
            IF movement.id IS NULL OR movement.kind<>'RETURN' OR movement.state<>'APPLIED'
                OR movement.document_id<>target OR movement.document_revision<>expected_revision
                OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id)<>1
                OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
                OR outgoing.id IS NULL OR incoming.id IS NULL
                OR (outgoing.stock_identity_id,outgoing.sku_id,outgoing.lot_id,outgoing.quantity_base,outgoing.base_unit,
                    outgoing.document_line_id,outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,
                    outgoing.condition,outgoing.status,outgoing.legal_owner) IS DISTINCT FROM
                    (line.stock_identity_id,line.sku_id,line.lot_id,1::bigint,line.base_unit,line.id,
                    (previous->>'locationId')::uuid,outgoing_owner,outgoing_kind,previous->>'condition','QUARANTINE'::varchar,line.legal_owner)
                OR (incoming.stock_identity_id,incoming.sku_id,incoming.lot_id,incoming.quantity_base,incoming.base_unit,
                    incoming.document_line_id,incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,
                    incoming.condition,incoming.status,incoming.legal_owner) IS DISTINCT FROM
                    (line.stock_identity_id,line.sku_id,line.lot_id,1::bigint,line.base_unit,line.id,
                    (snapshot->>'locationId')::uuid,(snapshot->>'locationId')::uuid,'WAREHOUSE'::varchar,
                    snapshot->>'condition',CASE WHEN released THEN 'AVAILABLE' ELSE 'QUARANTINE' END,line.legal_owner)
                OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=operation.id
                    AND document_id=target AND document_revision=expected_revision AND event_kind='RETURN_RECEIVED') THEN
                RAISE EXCEPTION 'RETURN_ASSET_EXACT_POSTING' USING ERRCODE='23514'; END IF;
        END IF;
        previous:=snapshot;
        expected_revision:=expected_revision+1;
    END LOOP;
    IF expected_revision<2 OR expected_revision<>document.revision+1 OR document.state IS DISTINCT FROM
        (CASE WHEN snapshot->>'state'='ACCEPTED' THEN 'ACCEPTED' ELSE 'RECEIVED_IN_INSPECTION' END) THEN
        RAISE EXCEPTION 'RETURN_ASSET_REVISION_GAP' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_received_recovery(scope uuid, removal_id uuid) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT id INTO target FROM inventory_return_case WHERE tenant_id=scope AND origin='ASSET_REMOVAL' AND source_document_id=removal_id;
    IF target IS NULL THEN RETURN false; END IF;
    PERFORM warehouse_assert_returned_asset(scope,target);
    RETURN true;
END $$;

DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_return(uuid,uuid)'::regprocedure);
    anchor:='IF NOT FOUND THEN RETURN; END IF;';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'return origin dispatch entry changed'; END IF;
    EXECUTE replace(definition,anchor,anchor||$patch$
    IF intake.origin='ASSET_REMOVAL' THEN PERFORM warehouse_assert_returned_asset(scope,target); RETURN; END IF;
    $patch$);

    definition:=pg_get_functiondef('warehouse_assert_asset_removal(uuid,uuid)'::regprocedure);
    anchor:='body jsonb;';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'removal declaration changed'; END IF;
    definition:=replace(definition,anchor,'body jsonb; continued boolean;');
    anchor:='source_assignment:=jsonb_populate_record(NULL::inventory_asset_assignment,origin.assignment_snapshot);';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'removal snapshot entry changed'; END IF;
    definition:=replace(definition,anchor,anchor||' continued:=warehouse_received_recovery(scope,removal.id);');
    anchor:=$anchor$OR asset.revision<>removal.source_asset_revision+1 OR asset.status<>'QUARANTINE' OR asset.condition<>'QUARANTINE'
        OR asset.location_id<>removal.recovery_location_id OR asset.custody_owner_id<>removal.actor_id OR asset.custody_owner_kind<>'TRANSIT'$anchor$;
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'removal live position entry changed'; END IF;
    definition:=replace(definition,anchor,$patch$OR (NOT continued AND (asset.revision<>removal.source_asset_revision+1 OR asset.status<>'QUARANTINE' OR asset.condition<>'QUARANTINE'
        OR asset.location_id<>removal.recovery_location_id OR asset.custody_owner_id<>removal.actor_id OR asset.custody_owner_kind<>'TRANSIT'))$patch$);
    anchor:='IF (SELECT count(*) FROM inventory_balance_projection';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'removal balance entry changed'; END IF;
    definition:=replace(definition,anchor,'IF NOT continued AND ((SELECT count(*) FROM inventory_balance_projection');
    anchor:=$anchor$AND legal_owner=removal.legal_owner AND status='QUARANTINE' AND condition='QUARANTINE') THEN$anchor$;
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'removal balance boundary changed'; END IF;
    definition:=replace(definition,anchor,$patch$AND legal_owner=removal.legal_owner AND status='QUARANTINE' AND condition='QUARANTINE')) THEN$patch$);
    EXECUTE definition;
END $$;
