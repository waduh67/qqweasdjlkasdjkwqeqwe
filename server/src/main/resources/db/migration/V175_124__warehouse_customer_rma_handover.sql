-- Customer-owned repairs have a dedicated source and custody document. They do
-- not pass through an ISP stock ISSUE or change the historic sale assignment.
CREATE TABLE inventory_rma_handover (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, return_id uuid NOT NULL, repair_case_id uuid NOT NULL,
    original_assignment_id uuid NOT NULL, customer_id uuid NOT NULL, work_order_id uuid NOT NULL,
    technician_id uuid NOT NULL, asset_id uuid NOT NULL, body text NOT NULL, origin_snapshot jsonb NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,return_id), UNIQUE(tenant_id,repair_case_id),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,return_id) REFERENCES inventory_return_case(tenant_id,id),
    FOREIGN KEY(tenant_id,repair_case_id) REFERENCES inventory_repair_case(tenant_id,id),
    FOREIGN KEY(tenant_id,original_assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,technician_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id)
);
CREATE TABLE inventory_rma_receipt (
    tenant_id uuid NOT NULL, handover_id uuid NOT NULL, actor_id uuid NOT NULL, request jsonb NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,handover_id),
    FOREIGN KEY(tenant_id,handover_id) REFERENCES inventory_rma_handover(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
DO $$ DECLARE table_name text; rule record;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_rma_handover','inventory_rma_receipt'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    FOR rule IN SELECT conname,pg_get_expr(conbin,conrelid) expression FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND contype='c' AND pg_get_expr(conbin,conrelid) LIKE '%kind%' LOOP
        EXECUTE format('ALTER TABLE inventory_document DROP CONSTRAINT %I',rule.conname);
        EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT %I CHECK ((%s) OR (kind=''RMA_HANDOVER'' AND state IN (''DRAFT'',''DISPATCHED'',''RECEIVED'')))',rule.conname,rule.expression);
    END LOOP;
END $$;

CREATE FUNCTION warehouse_capture_rma_handover() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE intake inventory_return_case; repair inventory_repair_case; removal inventory_asset_removal;
    physical inventory_serialized_asset; balance inventory_balance_projection; document inventory_document;
    work work_order; origin jsonb; request jsonb; view jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.created_xid<>pg_current_xact_id() THEN RAISE EXCEPTION 'RMA_ORIGIN_TRANSACTION_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO STRICT intake FROM inventory_return_case WHERE tenant_id=NEW.tenant_id AND id=NEW.return_id;
    SELECT * INTO STRICT repair FROM inventory_repair_case WHERE tenant_id=NEW.tenant_id AND id=NEW.repair_case_id FOR UPDATE;
    SELECT * INTO STRICT removal FROM inventory_asset_removal WHERE tenant_id=NEW.tenant_id AND id=intake.source_document_id;
    SELECT * INTO STRICT physical FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id FOR UPDATE;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.id;
    SELECT * INTO STRICT work FROM work_order WHERE tenant_id=NEW.tenant_id AND id=NEW.work_order_id FOR UPDATE;
    request:=NEW.body::jsonb->'request'; origin:=NEW.body::jsonb->'origin'; view:=NEW.body::jsonb->'view';
    PERFORM warehouse_assert_returned_asset(NEW.tenant_id,intake.id);
    SELECT * INTO balance FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND stock_identity_id=NEW.asset_id
        AND location_id=physical.location_id AND custody_owner_id=physical.location_id AND custody_owner_kind='WAREHOUSE'
        AND status='QUARANTINE' AND condition='SERVICEABLE' AND legal_owner='CUSTOMER' AND quantity_base=1 FOR UPDATE;
    IF intake.origin<>'ASSET_REMOVAL' OR repair.return_document_id<>intake.id OR repair.asset_id<>NEW.asset_id
        OR repair.state<>'CLOSED' OR repair.legal_owner<>'CUSTOMER' OR repair.revision<>2 OR balance.id IS NULL
        OR repair.inspected_revision IS DISTINCT FROM (request->>'expectedRevision')::bigint
        OR removal.assignment_id<>NEW.original_assignment_id OR removal.customer_id<>NEW.customer_id OR removal.asset_id<>NEW.asset_id
        OR removal.legal_owner<>'CUSTOMER' OR physical.legal_owner<>'CUSTOMER' OR physical.condition<>'SERVICEABLE'
        OR physical.status<>'QUARANTINE' OR physical.custody_owner_kind<>'WAREHOUSE' OR physical.custody_owner_id<>physical.location_id
        OR physical.warehouse_admission<>'VERIFIED' OR balance.warehouse_admission<>'VERIFIED'
        OR request->>'observedSerial' IS DISTINCT FROM physical.serial_number
        OR nullif(btrim(request->>'evidenceReference'),'') IS NULL
        OR (request->>'workOrderId')::uuid IS DISTINCT FROM work.id OR (request->>'workOrderRevision')::bigint IS DISTINCT FROM work.warehouse_revision
        OR (request->>'technicianId')::uuid IS DISTINCT FROM NEW.technician_id
        OR work.customer_id IS DISTINCT FROM NEW.customer_id OR work.type<>'REPAIR' OR work.status NOT IN ('ASSIGNED','IN_PROGRESS')
        OR NOT EXISTS(SELECT FROM work_order_assignee WHERE tenant_id=NEW.tenant_id AND work_order_id=work.id AND technician_id=NEW.technician_id)
        OR NOT EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND id=NEW.original_assignment_id
            AND ended_at IS NOT NULL AND legal_owner='CUSTOMER' AND ownership_mode='SALE')
        OR NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=NEW.tenant_id AND id=(request->>'transitLocationId')::uuid
            AND kind='TRANSIT' AND state='ACTIVE')
        OR NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=NEW.tenant_id AND id=(request->>'technicianLocationId')::uuid
            AND kind='TECHNICIAN' AND custodian_id=NEW.technician_id AND state='ACTIVE')
        OR NOT EXISTS(SELECT FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=intake.id AND revision=repair.inspected_revision)
        OR document.actor_id=NEW.technician_id OR document.state<>'DRAFT' OR document.revision<>0 OR document.kind<>'RMA_HANDOVER'
        OR (document.customer_id,document.work_order_id,document.source_document_id,document.source_revision) IS DISTINCT FROM
            (NEW.customer_id,NEW.work_order_id,NEW.return_id,repair.inspected_revision)
        OR (origin->>'repairCaseId')::uuid IS DISTINCT FROM repair.id OR (origin->>'repairRevision')::bigint IS DISTINCT FROM repair.revision
        OR (origin->>'originalAssignmentId')::uuid IS DISTINCT FROM removal.assignment_id
        OR (origin->>'customerId')::uuid IS DISTINCT FROM removal.customer_id OR (origin->>'assetRevision')::bigint IS DISTINCT FROM physical.revision
        OR origin->'source'->'dimension' IS DISTINCT FROM jsonb_build_object('skuId',physical.sku_id,'stockIdentityId',physical.id,
            'lotId',NULL,'locationId',physical.location_id,'custodianId',physical.location_id,'custodianKind','WAREHOUSE','condition','SERVICEABLE','legalOwner','CUSTOMER')
        OR origin->'source'->>'quantity' IS DISTINCT FROM '1' OR origin->'source'->>'unit' IS DISTINCT FROM 'EA'
        OR origin->'source'->>'tracking' IS DISTINCT FROM 'SERIAL' OR origin->'source'->>'serial' IS DISTINCT FROM physical.serial_number
        OR (origin->'source'->>'balanceRevision')::bigint IS DISTINCT FROM balance.revision THEN
        RAISE EXCEPTION 'RMA_INSPECTED_ORIGINAL_CUSTOMER_SOURCE_REQUIRED' USING ERRCODE='23514';
    END IF;
    IF view-ARRAY['recordedAt'] IS DISTINCT FROM jsonb_build_object('id',NEW.id,'returnId',NEW.return_id,'repairCaseId',NEW.repair_case_id,
        'originalAssignmentId',NEW.original_assignment_id,'customerId',NEW.customer_id,'workOrderId',NEW.work_order_id,
        'workOrderRevision',work.warehouse_revision,'technicianId',NEW.technician_id,'stockIdentityId',physical.id,'skuId',physical.sku_id,
        'serial',physical.serial_number,'sourceLocationId',physical.location_id,'transitLocationId',(request->>'transitLocationId')::uuid,
        'technicianLocationId',(request->>'technicianLocationId')::uuid,'legalOwner','CUSTOMER','revision',0,'state','DRAFT',
        'locationId',physical.location_id,'createdBy',document.actor_id) OR (view->>'recordedAt')::timestamptz IS NULL THEN
        RAISE EXCEPTION 'RMA_SOURCE_RESPONSE_MISMATCH' USING ERRCODE='23514'; END IF;
    NEW.origin_snapshot:=jsonb_build_object('asset',to_jsonb(physical),'balance',to_jsonb(balance),'workOrder',to_jsonb(work));
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_rma_capture BEFORE INSERT ON inventory_rma_handover FOR EACH ROW EXECUTE FUNCTION warehouse_capture_rma_handover();

CREATE FUNCTION warehouse_rma_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE handover inventory_rma_handover; document inventory_document; request jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT handover FROM inventory_rma_handover WHERE tenant_id=NEW.tenant_id AND id=NEW.handover_id;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=handover.id FOR UPDATE;
    request:=handover.body::jsonb->'request';
    IF NEW.created_xid<>pg_current_xact_id() OR NEW.actor_id<>handover.technician_id OR document.actor_id=NEW.actor_id
        OR document.state<>'DISPATCHED' OR document.revision<>1 OR NEW.request->>'expectedRevision' IS DISTINCT FROM '1'
        OR NEW.request->>'observedSerial' IS DISTINCT FROM request->>'observedSerial'
        OR nullif(btrim(NEW.request->>'evidenceReference'),'') IS NULL
        OR NOT EXISTS(SELECT FROM work_order work JOIN work_order_assignee assignee
            ON assignee.tenant_id=work.tenant_id AND assignee.work_order_id=work.id
            WHERE work.tenant_id=NEW.tenant_id AND work.id=handover.work_order_id AND work.customer_id=handover.customer_id
                AND work.type='REPAIR' AND work.status IN ('ASSIGNED','IN_PROGRESS') AND work.warehouse_revision=(request->>'workOrderRevision')::bigint
                AND assignee.technician_id=NEW.actor_id) THEN
        RAISE EXCEPTION 'RMA_ASSIGNED_RECEIVER_REQUIRED' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_rma_receiver BEFORE INSERT ON inventory_rma_receipt FOR EACH ROW EXECUTE FUNCTION warehouse_rma_receipt_guard();

CREATE FUNCTION warehouse_assert_rma_handover(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE handover inventory_rma_handover; document inventory_document; line inventory_document_line; acknowledgement inventory_rma_receipt;
    operation inventory_operation; movement inventory_movement; incoming inventory_movement_leg; outgoing inventory_movement_leg;
    initial jsonb; snapshot jsonb; request jsonb; canonical text; expected_request jsonb; step bigint:=1;
    source_location uuid; source_owner uuid; source_kind text; source_status text; destination uuid; destination_kind text; destination_status text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO handover FROM inventory_rma_handover WHERE tenant_id=scope AND id=target;
    IF NOT FOUND THEN RETURN; END IF;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    SELECT * INTO acknowledgement FROM inventory_rma_receipt WHERE tenant_id=scope AND handover_id=target;
    initial:=handover.body::jsonb->'view'; request:=handover.body::jsonb->'request';
    IF document.kind<>'RMA_HANDOVER' OR document.revision NOT IN (1,2)
        OR document.state IS DISTINCT FROM (CASE document.revision WHEN 1 THEN 'DISPATCHED' ELSE 'RECEIVED' END)
        OR (acknowledgement.handover_id IS NOT NULL) IS DISTINCT FROM (document.revision=2)
        OR (line.id,line.stock_identity_id,line.sku_id,line.base_unit,line.tracking,line.quantity_base,line.source_line_id,
            line.location_id,line.custodian_id,line.custodian_kind,line.condition,line.legal_owner) IS DISTINCT FROM
           (target,handover.asset_id,(initial->>'skuId')::uuid,'EA'::varchar,'SERIAL'::varchar,1::bigint,handover.return_id,
            (initial->>'sourceLocationId')::uuid,(initial->>'sourceLocationId')::uuid,'WAREHOUSE'::varchar,'SERVICEABLE'::varchar,'CUSTOMER'::varchar)
        OR line.lot_id IS NOT NULL THEN RAISE EXCEPTION 'RMA_DOCUMENT_SOURCE_MISMATCH' USING ERRCODE='23514'; END IF;
    FOR operation IN SELECT * FROM inventory_operation WHERE tenant_id=scope AND document_id=target ORDER BY document_revision LOOP
        snapshot:=operation.original_body::jsonb;
        SELECT canonical_payload INTO canonical FROM inventory_command_identity WHERE tenant_id=scope AND id=operation.id;
        expected_request:=jsonb_build_object('id',CASE step WHEN 1 THEN handover.return_id ELSE target END,
            'request',CASE step WHEN 1 THEN request ELSE acknowledgement.request END);
        IF step=1 THEN
            source_location:=(initial->>'sourceLocationId')::uuid; source_owner:=source_location; source_kind:='WAREHOUSE'; source_status:='QUARANTINE';
            destination:=(initial->>'transitLocationId')::uuid; destination_kind:='TRANSIT'; destination_status:='IN_TRANSIT';
        ELSE
            source_location:=(initial->>'transitLocationId')::uuid; source_owner:=handover.technician_id; source_kind:='TRANSIT'; source_status:='IN_TRANSIT';
            destination:=(initial->>'technicianLocationId')::uuid; destination_kind:='TECHNICIAN'; destination_status:='ISSUED';
        END IF;
        IF step>2 OR operation.document_revision<>step OR operation.resource_id<>target OR operation.resource_scope<>'rma:'||target
            OR operation.namespace IS DISTINCT FROM (CASE step WHEN 1 THEN 'warehouse.rma.dispatch' ELSE 'warehouse.rma.acknowledge' END)
            OR operation.actor_id IS DISTINCT FROM (CASE step WHEN 1 THEN document.actor_id ELSE handover.technician_id END)
            OR canonical IS NULL OR canonical::jsonb IS DISTINCT FROM expected_request
            OR operation.payload_hash<>encode(sha256(convert_to(canonical,'UTF8')),'hex')
            OR snapshot-ARRAY['revision','state','locationId','recordedAt'] IS DISTINCT FROM initial-ARRAY['revision','state','locationId','recordedAt']
            OR (snapshot->>'revision')::bigint IS DISTINCT FROM step
            OR snapshot->>'state' IS DISTINCT FROM (CASE step WHEN 1 THEN 'DISPATCHED' ELSE 'RECEIVED' END)
            OR (snapshot->>'locationId')::uuid IS DISTINCT FROM destination
            OR (snapshot->>'recordedAt')::timestamptz IS DISTINCT FROM operation.created_at THEN
            RAISE EXCEPTION 'RMA_OPERATION_BINDING' USING ERRCODE='23514'; END IF;
        SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id;
        SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
        SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
        IF movement.id IS NULL OR movement.kind<>'REPAIR' OR movement.state<>'APPLIED' OR movement.document_id<>target OR movement.document_revision<>step
            OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id)<>1
            OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
            OR incoming.id IS NULL OR outgoing.id IS NULL
            OR (outgoing.stock_identity_id,outgoing.sku_id,outgoing.lot_id,outgoing.quantity_base,outgoing.base_unit,outgoing.document_line_id,
                outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,outgoing.condition,outgoing.status,outgoing.legal_owner) IS DISTINCT FROM
               (handover.asset_id,line.sku_id,NULL::uuid,1::bigint,'EA'::varchar,target,source_location,source_owner,source_kind,'SERVICEABLE'::varchar,source_status,'CUSTOMER'::varchar)
            OR (incoming.stock_identity_id,incoming.sku_id,incoming.lot_id,incoming.quantity_base,incoming.base_unit,incoming.document_line_id,
                incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,incoming.condition,incoming.status,incoming.legal_owner) IS DISTINCT FROM
               (handover.asset_id,line.sku_id,NULL::uuid,1::bigint,'EA'::varchar,target,destination,handover.technician_id,destination_kind,'SERVICEABLE'::varchar,destination_status,'CUSTOMER'::varchar)
            OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=operation.id AND document_id=target AND document_revision=step
                AND event_kind=(CASE step WHEN 1 THEN 'DISPATCHED' ELSE 'RETURN_RECEIVED' END)) THEN
            RAISE EXCEPTION 'RMA_EXACT_POSTING_REQUIRED' USING ERRCODE='23514'; END IF;
        step:=step+1;
    END LOOP;
    IF step<>document.revision+1 THEN RAISE EXCEPTION 'RMA_POSTING_REVISION_GAP' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_rma_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME IN ('inventory_rma_handover','inventory_document') THEN target:=NEW.id;
    ELSIF TG_TABLE_NAME='inventory_rma_receipt' THEN target:=NEW.handover_id;
    ELSIF TG_TABLE_NAME='inventory_movement_leg' THEN
        SELECT document_id INTO target FROM inventory_movement WHERE tenant_id=NEW.tenant_id AND id=NEW.movement_id;
    ELSE target:=NEW.document_id;
    END IF;
    PERFORM warehouse_assert_rma_handover(NEW.tenant_id,target);
    RETURN NEW;
END $$;
DO $$ DECLARE table_name text; definition text; anchor text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_rma_handover','inventory_rma_receipt','inventory_document','inventory_document_line','inventory_operation','inventory_movement','inventory_movement_leg'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_rma_final AFTER INSERT OR UPDATE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_rma_final_guard()',table_name);
    END LOOP;
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    anchor:='IF NOT permitted THEN RAISE EXCEPTION ''invalid document lifecycle''';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'RMA document extension point changed'; END IF;
    EXECUTE replace(definition,anchor,$patch$
    IF OLD.kind='RMA_HANDOVER' AND (OLD.state,NEW.state) IN (('DRAFT','DISPATCHED'),('DISPATCHED','RECEIVED'))
        AND EXISTS(SELECT FROM inventory_rma_handover WHERE tenant_id=NEW.tenant_id AND id=NEW.id) THEN permitted:=true; END IF;
    IF NOT permitted THEN RAISE EXCEPTION 'invalid document lifecycle'$patch$);
END $$;
