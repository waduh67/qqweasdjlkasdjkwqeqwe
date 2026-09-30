ALTER TABLE inventory_repair_case
    ADD COLUMN source_return_revision bigint,
    ADD COLUMN source_snapshot jsonb,
    ADD COLUMN dispatch_request jsonb,
    ADD COLUMN receive_revision bigint,
    ADD COLUMN receive_request jsonb,
    ADD COLUMN inspected_revision bigint;

CREATE FUNCTION warehouse_repair_mutation_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(CASE WHEN TG_OP='DELETE' THEN OLD.tenant_id ELSE NEW.tenant_id END);
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'REPAIR_HISTORY_IMMUTABLE' USING ERRCODE='23514'; END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.state<>'OUTBOUND' OR NEW.revision<>0 OR NEW.source_return_revision IS NULL OR NEW.source_return_revision<1
            OR NEW.source_snapshot IS NULL OR NEW.dispatch_request IS NULL OR NEW.receive_revision IS NOT NULL
            OR NEW.receive_request IS NOT NULL OR NEW.inspected_revision IS NOT NULL OR NEW.result IS NOT NULL
            OR NEW.inspected_return_document_id IS NOT NULL OR NEW.replacement_asset_id IS NOT NULL THEN
            RAISE EXCEPTION 'REPAIR_BOUND_DISPATCH_REQUIRED' USING ERRCODE='23514'; END IF;
    ELSE
        IF to_jsonb(NEW)-ARRAY['state','revision','updated_at','result','receive_revision','receive_request','inspected_return_document_id','inspected_revision']
            IS DISTINCT FROM to_jsonb(OLD)-ARRAY['state','revision','updated_at','result','receive_revision','receive_request','inspected_return_document_id','inspected_revision']
            OR NEW.revision<>OLD.revision+1
            OR NOT ((OLD.state,NEW.state) IN (('OUTBOUND','RETURNED'),('RETURNED','CLOSED')))
            OR OLD.state='RETURNED' AND (NEW.result,NEW.receive_revision,NEW.receive_request) IS DISTINCT FROM
                (OLD.result,OLD.receive_revision,OLD.receive_request) THEN
            RAISE EXCEPTION 'REPAIR_HISTORY_IMMUTABLE' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_repair_mutation BEFORE INSERT OR UPDATE OR DELETE ON inventory_repair_case
    FOR EACH ROW EXECUTE FUNCTION warehouse_repair_mutation_guard();

CREATE FUNCTION warehouse_assert_return_repair_step(scope uuid, target uuid, target_operation uuid, previous jsonb, snapshot jsonb)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE repair inventory_repair_case; intake inventory_return_case; operation inventory_operation;
    line inventory_document_line; movement inventory_movement; outgoing inventory_movement_leg; incoming inventory_movement_leg;
    command jsonb; request jsonb; progress jsonb; outbound boolean; source_owner uuid; source_kind text;
    destination_owner uuid; destination_kind text; expected_event text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO repair FROM inventory_repair_case WHERE tenant_id=scope AND id=(snapshot->'repair'->>'id')::uuid;
    SELECT * INTO intake FROM inventory_return_case WHERE tenant_id=scope AND id=target;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=scope AND id=$3;
    SELECT * INTO STRICT line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    SELECT canonical_payload::jsonb INTO command FROM inventory_command_identity WHERE tenant_id=scope AND id=$3;
    request:=command->'request';
    outbound:=operation.namespace='warehouse.return.repair-dispatch';
    IF repair.id IS NULL OR intake.origin IS DISTINCT FROM 'ASSET_REMOVAL' OR operation.id IS NULL
        OR operation.namespace NOT IN ('warehouse.return.repair-dispatch','warehouse.return.repair-receive')
        OR repair.return_document_id<>target OR repair.asset_id<>intake.stock_identity_id OR repair.legal_owner<>line.legal_owner
        OR repair.outbound_document_id IS DISTINCT FROM target OR repair.replacement_asset_id IS NOT NULL
        OR command->>'id' IS DISTINCT FROM target::text OR command->'request' IS NULL
        OR (request->>'expectedRevision')::bigint IS DISTINCT FROM operation.document_revision-1
        OR request->>'observedSerial' IS DISTINCT FROM intake.body::jsonb->'source'->>'serial'
        OR nullif(btrim(request->>'evidenceReference'),'') IS NULL OR nullif(btrim(request->>'vendorReference'),'') IS NULL
        OR snapshot->'inspection' IS DISTINCT FROM previous->'inspection'
        OR snapshot->>'condition' IS DISTINCT FROM previous->>'condition'
        OR repair.vendor_id IS DISTINCT FROM (repair.dispatch_request->>'vendorId')::uuid
        OR repair.vendor_reference IS DISTINCT FROM repair.dispatch_request->>'vendorReference'
        OR repair.source_snapshot->>'id' IS DISTINCT FROM target::text
        OR (repair.source_snapshot->>'revision')::bigint IS DISTINCT FROM repair.source_return_revision
        OR NOT EXISTS(SELECT FROM inventory_operation source WHERE source.tenant_id=scope AND source.document_id=target
            AND source.document_revision=repair.source_return_revision AND source.original_body::jsonb=repair.source_snapshot) THEN
        RAISE EXCEPTION 'REPAIR_SOURCE_BINDING' USING ERRCODE='23514'; END IF;
    progress:=jsonb_build_object('id',repair.id,'vendorId',repair.vendor_id,'vendorReference',repair.vendor_reference,
        'repairLocationId',(repair.dispatch_request->>'repairLocationId')::uuid,'dispatchRevision',repair.source_return_revision+1,
        'returnedRevision',NULL,'result',NULL,'receiptReference',NULL);
    IF outbound THEN
        IF previous IS DISTINCT FROM repair.source_snapshot OR previous->>'state' IS DISTINCT FROM 'RECEIVED_IN_INSPECTION'
            OR previous->'inspection' IS NULL OR previous->'inspection'='null'::jsonb OR previous->'repair' IS NOT NULL
            OR operation.document_revision<>repair.source_return_revision+1 OR request IS DISTINCT FROM repair.dispatch_request
            OR snapshot->>'state' IS DISTINCT FROM 'REPAIR' OR snapshot->>'locationId' IS DISTINCT FROM request->>'repairLocationId'
            OR NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=(request->>'repairLocationId')::uuid AND kind='TRANSIT') THEN
            RAISE EXCEPTION 'REPAIR_DISPATCH_BINDING' USING ERRCODE='23514'; END IF;
        source_owner:=(previous->>'locationId')::uuid; source_kind:='WAREHOUSE';
        destination_owner:=repair.vendor_id; destination_kind:='REPAIR'; expected_event:='DISPATCHED';
    ELSE
        IF previous->>'state' IS DISTINCT FROM 'REPAIR' OR previous->'repair' IS DISTINCT FROM progress
            OR repair.receive_revision IS DISTINCT FROM operation.document_revision
            OR repair.receive_revision<>repair.source_return_revision+2 OR request IS DISTINCT FROM repair.receive_request
            OR repair.result IS DISTINCT FROM request->>'result' OR repair.result NOT IN ('REPAIRED','UNREPAIRED')
            OR snapshot->>'state' IS DISTINCT FROM 'RECEIVED_IN_INSPECTION'
            OR snapshot->>'locationId' IS DISTINCT FROM request->>'quarantineLocationId'
            OR NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=(request->>'quarantineLocationId')::uuid AND kind='QUARANTINE') THEN
            RAISE EXCEPTION 'REPAIR_RECEIPT_BINDING' USING ERRCODE='23514'; END IF;
        progress:=progress||jsonb_build_object('returnedRevision',repair.receive_revision,'result',repair.result,
            'receiptReference',request->>'vendorReference');
        source_owner:=repair.vendor_id; source_kind:='REPAIR';
        destination_owner:=(snapshot->>'locationId')::uuid; destination_kind:='WAREHOUSE'; expected_event:='RETURN_RECEIVED';
    END IF;
    IF snapshot->'repair' IS DISTINCT FROM progress THEN
        RAISE EXCEPTION 'REPAIR_RESPONSE_BINDING' USING ERRCODE='23514'; END IF;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=$3;
    SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
    SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
    IF movement.id IS NULL OR movement.kind<>'REPAIR' OR movement.state<>'APPLIED'
        OR movement.document_id<>target OR movement.document_revision<>operation.document_revision
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=$3)<>1
        OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
        OR outgoing.id IS NULL OR incoming.id IS NULL
        OR (outgoing.stock_identity_id,outgoing.sku_id,outgoing.lot_id,outgoing.quantity_base,outgoing.base_unit,
            outgoing.document_line_id,outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,
            outgoing.condition,outgoing.status,outgoing.legal_owner) IS DISTINCT FROM
            (line.stock_identity_id,line.sku_id,line.lot_id,1::bigint,line.base_unit,line.id,
            (previous->>'locationId')::uuid,source_owner,source_kind,previous->>'condition','QUARANTINE'::varchar,line.legal_owner)
        OR (incoming.stock_identity_id,incoming.sku_id,incoming.lot_id,incoming.quantity_base,incoming.base_unit,
            incoming.document_line_id,incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,
            incoming.condition,incoming.status,incoming.legal_owner) IS DISTINCT FROM
            (line.stock_identity_id,line.sku_id,line.lot_id,1::bigint,line.base_unit,line.id,
            (snapshot->>'locationId')::uuid,destination_owner,destination_kind,snapshot->>'condition','QUARANTINE'::varchar,line.legal_owner)
        OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=$3 AND document_id=target
            AND document_revision=operation.document_revision AND event_kind=expected_event) THEN
        RAISE EXCEPTION 'REPAIR_EXACT_POSTING' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_repair_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE repair inventory_repair_case;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO repair FROM inventory_repair_case WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
    IF repair.id IS NULL OR repair.state NOT IN ('OUTBOUND','RETURNED','CLOSED')
        OR repair.vendor_id IS NULL OR nullif(btrim(repair.vendor_reference),'') IS NULL
        OR repair.revision IS DISTINCT FROM (CASE repair.state WHEN 'OUTBOUND' THEN 0::bigint WHEN 'RETURNED' THEN 1::bigint ELSE 2::bigint END)
        OR NOT EXISTS(SELECT FROM inventory_operation WHERE tenant_id=NEW.tenant_id AND document_id=repair.return_document_id
            AND document_revision=repair.source_return_revision+1 AND namespace='warehouse.return.repair-dispatch'
            AND original_body::jsonb->'repair'->>'id'=repair.id::text)
        OR (repair.state<>'OUTBOUND') IS DISTINCT FROM (repair.receive_revision IS NOT NULL AND repair.receive_request IS NOT NULL AND repair.result IS NOT NULL)
        OR repair.state<>'OUTBOUND' AND NOT EXISTS(SELECT FROM inventory_operation WHERE tenant_id=NEW.tenant_id
            AND document_id=repair.return_document_id AND document_revision=repair.receive_revision AND namespace='warehouse.return.repair-receive')
        OR (repair.state='CLOSED') IS DISTINCT FROM (repair.inspected_revision IS NOT NULL AND repair.inspected_return_document_id IS NOT NULL)
        OR repair.state='CLOSED' AND (repair.inspected_return_document_id<>repair.return_document_id
            OR repair.inspected_revision<=repair.receive_revision OR NOT EXISTS(SELECT FROM inventory_operation WHERE tenant_id=NEW.tenant_id
                AND document_id=repair.return_document_id AND document_revision=repair.inspected_revision AND namespace='warehouse.return.inspect'
                AND original_body::jsonb->>'condition'='SERVICEABLE')) THEN
        RAISE EXCEPTION 'REPAIR_CASE_POSTING_REQUIRED' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_returned_asset(NEW.tenant_id,repair.return_document_id);
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_repair_final AFTER INSERT OR UPDATE ON inventory_repair_case
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_repair_final_guard();

DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    anchor:='IF NEW.state=OLD.state THEN RETURN NEW; END IF;';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'repair document transition anchor changed'; END IF;
    EXECUTE replace(definition,anchor,anchor||$patch$
    IF OLD.kind='RETURN' AND OLD.state='REPAIR' AND NEW.state='RECEIVED_IN_INSPECTION'
        AND EXISTS(SELECT FROM inventory_repair_case WHERE tenant_id=NEW.tenant_id AND return_document_id=NEW.id) THEN RETURN NEW; END IF;
    $patch$);

    definition:=pg_get_functiondef('warehouse_assert_returned_asset(uuid,uuid)'::regprocedure);
    anchor:='ARRAY[''revision'',''state'',''locationId'',''condition'',''recordedAt'',''inspection'']';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'repair response invariant anchor changed'; END IF;
    definition:=replace(definition,anchor,'ARRAY[''revision'',''state'',''locationId'',''condition'',''recordedAt'',''inspection'',''repair'']');
    anchor:='IF expected_revision=0 THEN';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'repair operation dispatch anchor changed'; END IF;
    definition:=replace(definition,anchor,$patch$
        IF operation.namespace IN ('warehouse.return.repair-dispatch','warehouse.return.repair-receive') THEN
            PERFORM warehouse_assert_return_repair_step(scope,target,operation.id,previous,snapshot);
            previous:=snapshot; expected_revision:=expected_revision+1; CONTINUE;
        END IF;
        IF snapshot->'repair' IS DISTINCT FROM previous->'repair' THEN
            RAISE EXCEPTION 'REPAIR_PROGRESS_IMMUTABLE' USING ERRCODE='23514'; END IF;
        IF expected_revision=0 THEN$patch$);
    anchor:='CASE WHEN snapshot->>''state''=''ACCEPTED'' THEN ''ACCEPTED'' ELSE ''RECEIVED_IN_INSPECTION'' END';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'repair terminal state anchor changed'; END IF;
    definition:=replace(definition,anchor,'CASE WHEN snapshot->>''state''=''ACCEPTED'' THEN ''ACCEPTED'' WHEN snapshot->>''state''=''REPAIR'' THEN ''REPAIR'' ELSE ''RECEIVED_IN_INSPECTION'' END');
    EXECUTE definition;
END $$;
