DO $$ DECLARE row record; BEGIN
    FOR row IN SELECT conname FROM pg_constraint WHERE conrelid='inventory_reference_command'::regclass
        AND contype='c' AND pg_get_constraintdef(oid) LIKE '%SUBMIT%' LOOP
        EXECUTE format('ALTER TABLE inventory_reference_command DROP CONSTRAINT %I',row.conname);
    END LOOP;
    FOR row IN SELECT conname FROM pg_constraint WHERE conrelid='inventory_reference_post'::regclass
        AND contype='c' AND pg_get_constraintdef(oid) LIKE '%RECEIPT%' LOOP
        EXECUTE format('ALTER TABLE inventory_reference_post DROP CONSTRAINT %I',row.conname);
    END LOOP;
END $$;
ALTER TABLE inventory_reference_command ADD CONSTRAINT warehouse_reference_request_action
    CHECK(action IN ('SUBMIT','REVIEW','DECIDE','SETTINGS','RECEIVE','HANDOVER'));
ALTER TABLE inventory_reference_post ADD CONSTRAINT warehouse_reference_stock_action CHECK(action IN ('RECEIPT','TRANSFER','HANDOVER'));
CREATE UNIQUE INDEX warehouse_reference_request_movement_once ON inventory_reference_command(tenant_id,movement_id) WHERE movement_id IS NOT NULL;

DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_assert_reference_post_legs(uuid,uuid)'::regprocedure);
    anchor:='document.kind<>binding.action';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference document kind anchor changed'; END IF;
    definition:=replace(definition,anchor,'document.kind IS DISTINCT FROM (CASE WHEN binding.action=''HANDOVER'' THEN ''TRANSFER'' ELSE binding.action END)');
    definition:=replace(definition,'IF NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=binding.destination_location_id',
        'IF binding.action<>''HANDOVER'' AND NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=binding.destination_location_id');
    anchor:='IF binding.action=''RECEIPT'' AND NOT EXISTS';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference destination anchor changed'; END IF;
    definition:=replace(definition,anchor,$body$IF binding.action='HANDOVER' AND NOT EXISTS(SELECT FROM inventory_location
        WHERE tenant_id=scope AND id=binding.destination_location_id AND kind='TECHNICIAN' AND state='ACTIVE' AND custodian_id IS NOT NULL) THEN
        RAISE EXCEPTION 'handover destination must be technician custody' USING ERRCODE='23514'; END IF;
    IF binding.action='RECEIPT' AND NOT EXISTS$body$);
    anchor:='direction=''IN'' AND (status<>''AVAILABLE'' OR custody_owner_kind<>''WAREHOUSE'' OR custody_owner_id<>location_id)';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference incoming dimensions anchor changed'; END IF;
    definition:=replace(definition,anchor,$body$direction='IN' AND (CASE WHEN binding.action='HANDOVER' AND location_id=binding.destination_location_id
                    THEN status IS DISTINCT FROM 'ISSUED' OR custody_owner_kind IS DISTINCT FROM 'TECHNICIAN' OR custody_owner_id IS DISTINCT FROM
                        (SELECT custodian_id FROM inventory_location WHERE tenant_id=scope AND id=binding.destination_location_id)
                    ELSE status IS DISTINCT FROM 'AVAILABLE' OR custody_owner_kind IS DISTINCT FROM 'WAREHOUSE' OR custody_owner_id IS DISTINCT FROM location_id END)$body$);
    definition:=replace(definition,'direction=''OUT'' AND (location_id<>binding.source_location_id OR',
        'direction=''OUT'' AND (location_id<>binding.source_location_id OR (binding.action<>''RECEIPT'' AND (custody_owner_kind<>''WAREHOUSE'' OR custody_owner_id<>binding.source_location_id)) OR');
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    anchor:='binding.action=NEW.kind';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference transition action anchor changed'; END IF;
    definition:=replace(definition,anchor,'(binding.action=NEW.kind OR binding.action=''HANDOVER'' AND NEW.kind=''TRANSFER'')');
    definition:=replace(definition,'operation.namespace=''warehouse.reference.''||lower(NEW.kind)',
        'operation.namespace=''warehouse.reference.''||lower(binding.action)');
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_reference_post(uuid,uuid)'::regprocedure);
    anchor:='IF binding.action=''TRANSFER'' THEN';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference transfer canonical anchor changed'; END IF;
    definition:=replace(definition,anchor,'IF binding.action IN (''TRANSFER'',''HANDOVER'') THEN');
    anchor:='''warehouseId'',binding.destination_location_id,''lines'',request->''lines'',''notes'',request->''notes'')';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference transfer payload anchor changed'; END IF;
    definition:=replace(definition,anchor,$body$'warehouseId',binding.destination_location_id,'lines',request->'lines','notes',request->'notes')
                ||(CASE WHEN binding.action='HANDOVER' THEN jsonb_build_object('technicianId',request->'technicianId','skuId',request->'skuId') ELSE '{}'::jsonb END)$body$);
    anchor:='FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference transfer line anchor changed'; END IF;
    definition:=replace(definition,anchor,$body$IF binding.action='HANDOVER' AND (request->>'technicianId' IS DISTINCT FROM
                (SELECT custodian_id::text FROM inventory_location WHERE tenant_id=scope AND id=binding.destination_location_id)
            OR body->>'technicianId' IS DISTINCT FROM request->>'technicianId'
            OR NOT EXISTS(SELECT FROM inventory_reference_command WHERE tenant_id=scope AND action='HANDOVER' AND movement_id=target)) THEN
            RAISE EXCEPTION 'handover must bind technician and material request' USING ERRCODE='23514'; END IF;
        FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP$body$);
    anchor:='IF item IS DISTINCT FROM jsonb_build_object(''stockIdentityId''';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference handover SKU anchor changed'; END IF;
    definition:=replace(definition,anchor,'IF binding.action=''HANDOVER'' AND request->>''skuId'' IS DISTINCT FROM line.sku_id::text OR item IS DISTINCT FROM jsonb_build_object(''stockIdentityId''');
    EXECUTE definition;
END $$;

CREATE FUNCTION warehouse_assert_reference_request_movement(scope uuid,operation uuid,previous jsonb) RETURNS void LANGUAGE plpgsql AS $$
DECLARE command inventory_reference_command; binding inventory_reference_post; document inventory_document;
    stock_operation inventory_operation; stock_payload jsonb; input jsonb; current jsonb; before_line jsonb; after_line jsonb;
    amount numeric; expected_state text; expected_payload jsonb; item jsonb; position integer:=0;
BEGIN
    SELECT * INTO STRICT command FROM inventory_reference_command WHERE tenant_id=scope AND id=operation;
    input:=command.canonical_payload::jsonb->'input'; current:=command.snapshot;
    SELECT * INTO STRICT binding FROM inventory_reference_post WHERE tenant_id=scope AND document_id=command.movement_id;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=scope AND id=command.movement_id;
    SELECT * INTO STRICT stock_operation FROM inventory_operation WHERE tenant_id=scope AND id=binding.id;
    SELECT canonical_payload::jsonb INTO STRICT stock_payload FROM inventory_command_identity WHERE tenant_id=scope AND id=binding.id;
    IF previous IS NULL OR previous->>'state' NOT IN ('APPROVED','PARTIALLY_RECEIVED','RECEIVED','PARTIALLY_FULFILLED')
        OR command.permission_code IS DISTINCT FROM (CASE WHEN command.action='RECEIVE' THEN 'warehouse.request.receive' ELSE 'warehouse.request.handover' END)
        OR command.notes IS DISTINCT FROM input->>'notes' OR stock_payload->>'notes' IS DISTINCT FROM input->>'notes'
        OR stock_operation.actor_id IS DISTINCT FROM command.actor_id OR stock_operation.authority_epoch IS DISTINCT FROM command.authority_epoch
        OR stock_operation.cutover_epoch IS DISTINCT FROM command.cutover_epoch
        OR stock_operation.operation_key IS DISTINCT FROM 'request:'||command.resource_id||':'||command.revision||':'||lower(command.action)
        OR binding.action IS DISTINCT FROM (CASE WHEN command.action='RECEIVE' THEN 'RECEIPT' ELSE 'HANDOVER' END)
        OR command.created_xid IS DISTINCT FROM binding.created_xid THEN
        RAISE EXCEPTION 'request movement requires exact stock command and authority' USING ERRCODE='23514'; END IF;
    SELECT value INTO before_line FROM jsonb_array_elements(previous->'lines') WHERE value->>'id'=input->>'lineId';
    SELECT value INTO after_line FROM jsonb_array_elements(current->'lines') WHERE value->>'id'=input->>'lineId';
    IF before_line IS NULL OR after_line IS NULL OR before_line->>'skuId' IS NULL OR EXISTS(SELECT FROM inventory_document_line
        WHERE tenant_id=scope AND document_id=command.movement_id AND (sku_id::text IS DISTINCT FROM before_line->>'skuId'
            OR base_unit IS DISTINCT FROM before_line->>'baseUnit')) THEN
        RAISE EXCEPTION 'request movement must use approved SKU and unit' USING ERRCODE='23514'; END IF;
    SELECT sum(quantity_base::numeric) INTO amount FROM inventory_document_line WHERE tenant_id=scope AND document_id=command.movement_id;
    IF amount IS NULL OR amount<=0 OR amount>9223372036854775807 THEN
        RAISE EXCEPTION 'request movement quantity required' USING ERRCODE='23514'; END IF;
    IF command.action='RECEIVE' THEN
        expected_payload:=jsonb_build_object('warehouseId',input->'warehouseId','notes',input->'notes',
            'supplierId',input->'supplierId','reference',input->'reference','lines',jsonb_build_array(jsonb_build_object(
                'skuId',before_line->'skuId','quantityBase',input->'quantityBase','serials',input->'serials',
                'lotCode',input->'lotCode','conversion',input->'conversion','cost',input->'cost')));
        IF previous->>'kind' IS DISTINCT FROM 'PROCUREMENT' OR stock_payload IS DISTINCT FROM expected_payload
            OR amount::text IS DISTINCT FROM input->>'quantityBase'
            OR input->>'warehouseId' IS DISTINCT FROM binding.destination_location_id::text
            OR previous->>'warehouseId' IS NOT NULL AND previous->>'warehouseId' IS DISTINCT FROM input->>'warehouseId'
            OR after_line->>'receivedBase' IS DISTINCT FROM ((before_line->>'receivedBase')::numeric+amount)::text
            OR after_line->>'fulfilledBase' IS DISTINCT FROM ((before_line->>'fulfilledBase')::numeric+
                CASE WHEN previous->>'warehouseId' IS NOT NULL THEN amount ELSE 0 END)::text THEN
            RAISE EXCEPTION 'request receipt must increment exact approved counters' USING ERRCODE='23514'; END IF;
    ELSE
        expected_payload:=jsonb_build_object('sourceWarehouseId',input->'warehouseId','warehouseId',binding.destination_location_id,
            'technicianId',previous->'technicianId','skuId',before_line->'skuId','lines',input->'lines','notes',input->'notes');
        IF previous->>'technicianId' IS NULL OR stock_payload IS DISTINCT FROM expected_payload
            OR binding.source_location_id::text IS DISTINCT FROM input->>'warehouseId'
            OR after_line->>'receivedBase' IS DISTINCT FROM before_line->>'receivedBase'
            OR after_line->>'fulfilledBase' IS DISTINCT FROM ((before_line->>'fulfilledBase')::numeric+amount)::text THEN
            RAISE EXCEPTION 'request handover must increment exact approved counters' USING ERRCODE='23514'; END IF;
    END IF;
    FOR item IN SELECT value FROM jsonb_array_elements(current->'lines') LOOP
        IF item->>'id'=input->>'lineId' THEN
            IF item-ARRAY['receivedBase','fulfilledBase'] IS DISTINCT FROM before_line-ARRAY['receivedBase','fulfilledBase'] THEN
                RAISE EXCEPTION 'movement cannot change approved material' USING ERRCODE='23514'; END IF;
        ELSIF item IS DISTINCT FROM previous->'lines'->position THEN
            RAISE EXCEPTION 'movement cannot change another request line' USING ERRCODE='23514'; END IF;
        position:=position+1;
    END LOOP;
    expected_state:=CASE
        WHEN NOT EXISTS(SELECT FROM jsonb_array_elements(current->'lines') WHERE (value->>'fulfilledBase')::numeric<(value->>'approvedBase')::numeric) THEN 'FULFILLED'
        WHEN current->>'technicianId' IS NOT NULL AND EXISTS(SELECT FROM jsonb_array_elements(current->'lines') WHERE (value->>'fulfilledBase')::numeric>0) THEN 'PARTIALLY_FULFILLED'
        WHEN current->>'kind'='PROCUREMENT' THEN CASE WHEN EXISTS(SELECT FROM jsonb_array_elements(current->'lines')
            WHERE (value->>'receivedBase')::numeric<(value->>'approvedBase')::numeric) THEN 'PARTIALLY_RECEIVED' ELSE 'RECEIVED' END
        ELSE 'PARTIALLY_FULFILLED' END;
    IF current->>'state' IS DISTINCT FROM expected_state THEN
        RAISE EXCEPTION 'request movement state differs from remaining material' USING ERRCODE='23514'; END IF;
END $$;

DO $$ DECLARE definition text; anchor text:='previous:=current;'; BEGIN
    definition:=pg_get_functiondef('warehouse_assert_reference_request(uuid,uuid)'::regprocedure);
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference request replay anchor changed'; END IF;
    definition:=replace(definition,anchor,$body$IF command.action IN ('RECEIVE','HANDOVER') THEN
            PERFORM warehouse_assert_reference_request_movement(scope,command.id,previous);
        END IF;
        previous:=current;$body$);
    EXECUTE definition;
END $$;

CREATE FUNCTION warehouse_reference_movement_actor_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE technician uuid; BEGIN
    IF NEW.action NOT IN ('RECEIVE','HANDOVER') THEN RETURN NEW; END IF;
    technician:=(NEW.snapshot->>'technicianId')::uuid;
    IF technician IS NOT NULL AND NOT EXISTS(SELECT FROM app_user actor WHERE actor.tenant_id=NEW.tenant_id
        AND actor.id=technician AND actor.status='ACTIVE' AND EXISTS(SELECT FROM user_role assignment
            JOIN role ON role.id=assignment.role_id AND role.tenant_id=NEW.tenant_id
            WHERE assignment.user_id=actor.id AND role.default_key IN ('TECHNICIAN_NE','TECHNICIAN_FO','TECHNICIAN_LEGACY'))) THEN
        RAISE EXCEPTION 'request movement requires active technician' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_reference_movement_actor BEFORE INSERT ON inventory_reference_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_movement_actor_guard();
