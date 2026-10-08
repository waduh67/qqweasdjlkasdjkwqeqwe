CREATE TABLE inventory_reference_return (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), technician_id uuid NOT NULL,
    warehouse_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision IN (0,1)),
    snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,technician_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,warehouse_id) REFERENCES inventory_location(tenant_id,id)
);
CREATE TABLE inventory_reference_return_command (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), resource_id uuid NOT NULL,
    action varchar(16) NOT NULL CHECK(action IN ('SUBMIT','DECIDE')), operation_key varchar(240) NOT NULL CHECK(btrim(operation_key)<>''),
    actor_id uuid NOT NULL, authority_epoch bigint NOT NULL CHECK(authority_epoch>=0), cutover_epoch bigint NOT NULL CHECK(cutover_epoch>=0),
    revision bigint NOT NULL CHECK(revision IN (0,1)), canonical_payload text NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK(payload_hash=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex')),
    snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'), notes varchar(1000) NOT NULL, permission_code varchar(100) NOT NULL,
    movement_id uuid, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,action,operation_key), UNIQUE(tenant_id,resource_id,revision), UNIQUE(tenant_id,movement_id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,resource_id) REFERENCES inventory_reference_return(tenant_id,id),
    FOREIGN KEY(tenant_id,movement_id) REFERENCES inventory_document(tenant_id,id),
    CHECK((snapshot->>'state'='RECEIVED')=(movement_id IS NOT NULL))
);
CREATE INDEX warehouse_reference_return_list ON inventory_reference_return(tenant_id,created_at DESC,id);
CREATE INDEX warehouse_reference_return_owner ON inventory_reference_return(tenant_id,technician_id,created_at DESC,id);
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_reference_return','inventory_reference_return_command'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO warehouse_app',relation); END IF;
    END LOOP;
END $$;
CREATE TRIGGER warehouse_reference_return_command_immutable BEFORE UPDATE OR DELETE ON inventory_reference_return_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE TRIGGER warehouse_reference_return_command_authority BEFORE INSERT ON inventory_reference_return_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_command_authority();
CREATE FUNCTION warehouse_reference_return_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.revision<>OLD.revision+1 OR
        to_jsonb(NEW)-ARRAY['revision','snapshot','updated_at'] IS DISTINCT FROM to_jsonb(OLD)-ARRAY['revision','snapshot','updated_at'] THEN
        RAISE EXCEPTION 'return projection requires immutable submission' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_reference_return_revision BEFORE UPDATE OR DELETE ON inventory_reference_return
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_return_revision();

DO $$ DECLARE definition text; anchor text; BEGIN
    ALTER TABLE inventory_reference_post DROP CONSTRAINT warehouse_reference_stock_action;
    ALTER TABLE inventory_reference_post ADD CONSTRAINT warehouse_reference_stock_action CHECK(action IN ('RECEIPT','TRANSFER','HANDOVER','RETURN'));
    definition:=pg_get_functiondef('warehouse_assert_reference_post_legs(uuid,uuid)'::regprocedure);
    anchor:='CASE WHEN binding.action=''HANDOVER'' THEN ''TRANSFER'' ELSE binding.action END';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference return document anchor changed'; END IF;
    definition:=replace(definition,anchor,'CASE WHEN binding.action IN (''HANDOVER'',''RETURN'') THEN ''TRANSFER'' ELSE binding.action END');
    definition:=replace(definition,'CASE binding.action WHEN ''RECEIPT'' THEN ''RECEIVE'' ELSE ''TRANSFER'' END',
        'CASE binding.action WHEN ''RECEIPT'' THEN ''RECEIVE'' WHEN ''RETURN'' THEN ''RETURN'' ELSE ''TRANSFER'' END');
    definition:=replace(definition,'line.custodian_kind<>''WAREHOUSE''',
        'line.custodian_kind IS DISTINCT FROM (CASE WHEN binding.action=''RETURN'' THEN ''TECHNICIAN'' ELSE ''WAREHOUSE'' END)');
    definition:=replace(definition,'line.custodian_id<>line.location_id',
        'line.custodian_id IS DISTINCT FROM (CASE WHEN binding.action=''RETURN'' THEN (SELECT custodian_id FROM inventory_location WHERE tenant_id=scope AND id=binding.source_location_id) ELSE line.location_id END)');
    anchor:='direction=''IN'' AND (CASE WHEN binding.action=''HANDOVER'' AND location_id=binding.destination_location_id';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference return incoming anchor changed'; END IF;
    definition:=replace(definition,anchor,'direction=''IN'' AND (CASE WHEN binding.action=''RETURN'' AND location_id=binding.source_location_id
                    THEN status IS DISTINCT FROM ''ISSUED'' OR custody_owner_kind IS DISTINCT FROM ''TECHNICIAN'' OR custody_owner_id IS DISTINCT FROM
                        (SELECT custodian_id FROM inventory_location WHERE tenant_id=scope AND id=binding.source_location_id)
                    WHEN binding.action=''HANDOVER'' AND location_id=binding.destination_location_id');
    anchor:='(binding.action<>''RECEIPT'' AND (custody_owner_kind<>''WAREHOUSE'' OR custody_owner_id<>binding.source_location_id))';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference return outgoing anchor changed'; END IF;
    definition:=replace(definition,anchor,'(binding.action<>''RECEIPT'' AND (custody_owner_kind IS DISTINCT FROM
                    (CASE WHEN binding.action=''RETURN'' THEN ''TECHNICIAN'' ELSE ''WAREHOUSE'' END) OR custody_owner_id IS DISTINCT FROM
                    (CASE WHEN binding.action=''RETURN'' THEN (SELECT custodian_id FROM inventory_location WHERE tenant_id=scope AND id=binding.source_location_id) ELSE binding.source_location_id END)))');
    definition:=replace(definition,'CASE binding.action WHEN ''RECEIPT'' THEN ''RECEIPT_SOURCE'' ELSE ''AVAILABLE'' END',
        'CASE binding.action WHEN ''RECEIPT'' THEN ''RECEIPT_SOURCE'' WHEN ''RETURN'' THEN ''ISSUED'' ELSE ''AVAILABLE'' END');
    EXECUTE definition;
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    anchor:='binding.action=''HANDOVER'' AND NEW.kind=''TRANSFER''';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference return transition anchor changed'; END IF;
    EXECUTE replace(definition,anchor,'binding.action IN (''HANDOVER'',''RETURN'') AND NEW.kind=''TRANSFER''');
    definition:=pg_get_functiondef('warehouse_assert_reference_post(uuid,uuid)'::regprocedure);
    definition:=replace(definition,'IF binding.action IN (''TRANSFER'',''HANDOVER'') THEN','IF binding.action IN (''TRANSFER'',''HANDOVER'',''RETURN'') THEN');
    definition:=replace(definition,'CASE WHEN binding.action=''HANDOVER'' THEN jsonb_build_object','CASE WHEN binding.action IN (''HANDOVER'',''RETURN'') THEN jsonb_build_object');
    definition:=replace(definition,'IF binding.action=''HANDOVER'' AND request->>''skuId''','IF binding.action IN (''HANDOVER'',''RETURN'') AND request->>''skuId''');
    definition:=replace(definition,'line.custodian_kind IS DISTINCT FROM ''WAREHOUSE''',
        'line.custodian_kind IS DISTINCT FROM (CASE WHEN binding.action=''RETURN'' THEN ''TECHNICIAN'' ELSE ''WAREHOUSE'' END)');
    definition:=replace(definition,'line.custodian_id IS DISTINCT FROM binding.source_location_id',
        'line.custodian_id IS DISTINCT FROM (CASE WHEN binding.action=''RETURN'' THEN (SELECT custodian_id FROM inventory_location WHERE tenant_id=scope AND id=binding.source_location_id) ELSE binding.source_location_id END)');
    anchor:='FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference return binding anchor changed'; END IF;
    definition:=replace(definition,anchor,$body$IF binding.action='RETURN' AND (request->>'technicianId' IS DISTINCT FROM
                (SELECT custodian_id::text FROM inventory_location WHERE tenant_id=scope AND id=binding.source_location_id AND kind='TECHNICIAN' AND state='ACTIVE')
            OR body->>'technicianId' IS DISTINCT FROM request->>'technicianId'
            OR NOT EXISTS(SELECT FROM inventory_reference_return_command WHERE tenant_id=scope AND movement_id=target AND snapshot->>'state'='RECEIVED')) THEN
            RAISE EXCEPTION 'return must bind owned material and receipt decision' USING ERRCODE='23514'; END IF;
        FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP$body$);
    EXECUTE definition;
END $$;

CREATE FUNCTION warehouse_assert_reference_return(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE projection inventory_reference_return; command inventory_reference_return_command; previous jsonb; current jsonb; input jsonb;
    item jsonb; requested jsonb; binding inventory_reference_post; operation inventory_operation; canonical jsonb;
    total numeric; position integer;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT projection FROM inventory_reference_return WHERE tenant_id=scope AND id=target;
    IF projection.snapshot->>'id' IS DISTINCT FROM target::text OR projection.snapshot->>'revision' IS DISTINCT FROM projection.revision::text
        OR projection.snapshot->>'technicianId' IS DISTINCT FROM projection.technician_id::text
        OR projection.snapshot->>'warehouseId' IS DISTINCT FROM projection.warehouse_id::text
        OR (projection.snapshot->>'createdAt')::timestamptz IS DISTINCT FROM projection.created_at
        OR (projection.snapshot->>'updatedAt')::timestamptz IS DISTINCT FROM projection.updated_at
        OR (SELECT count(*) FROM inventory_reference_return_command WHERE tenant_id=scope AND resource_id=target)<>projection.revision+1 THEN
        RAISE EXCEPTION 'return projection differs from command history' USING ERRCODE='23514'; END IF;
    FOR command IN SELECT * FROM inventory_reference_return_command WHERE tenant_id=scope AND resource_id=target ORDER BY revision LOOP
        current:=command.snapshot; input:=command.canonical_payload::jsonb->'input';
        IF command.canonical_payload::jsonb->>'id' IS DISTINCT FROM (CASE WHEN command.action='SUBMIT' THEN NULL ELSE target::text END)
            OR current->>'id' IS DISTINCT FROM target::text OR current->>'revision' IS DISTINCT FROM command.revision::text
            OR coalesce(current->>'baseUnit','') NOT IN ('EA','MM') OR coalesce(current->>'tracking','') NOT IN ('BULK','LOT','SERIAL')
            OR jsonb_typeof(current->'lines') IS DISTINCT FROM 'array' OR jsonb_array_length(current->'lines') NOT BETWEEN 1 AND 100
            OR (SELECT count(DISTINCT value->>'stockIdentityId') FROM jsonb_array_elements(current->'lines'))<>jsonb_array_length(current->'lines') THEN
            RAISE EXCEPTION 'return command identity or line mismatch' USING ERRCODE='23514'; END IF;
        total:=0; position:=0;
        FOR item IN SELECT value FROM jsonb_array_elements(current->'lines') LOOP
            IF item->>'stockIdentityId' IS NULL OR jsonb_typeof(item->'quantityBase') IS DISTINCT FROM 'string'
                OR item->>'quantityBase' !~ '^[1-9][0-9]*$' OR (item->>'quantityBase')::numeric>9223372036854775807
                OR current->>'tracking'='SERIAL' AND item->>'quantityBase'<>'1' THEN
                RAISE EXCEPTION 'return requires exact positive base quantities' USING ERRCODE='23514'; END IF;
            total:=total+(item->>'quantityBase')::numeric;
            IF command.action='SUBMIT' THEN
                requested:=input->'lines'->position;
                IF item-ARRAY['serial','mac'] IS DISTINCT FROM requested THEN
                    RAISE EXCEPTION 'return submission line differs from request' USING ERRCODE='23514'; END IF;
                IF command.created_xid=pg_current_xact_id() AND NOT EXISTS(SELECT FROM inventory_balance_projection balance
                    JOIN inventory_segment segment ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
                    LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
                    JOIN inventory_location location ON location.tenant_id=balance.tenant_id AND location.id=balance.location_id
                    WHERE balance.tenant_id=scope AND balance.stock_identity_id=(item->>'stockIdentityId')::uuid
                        AND balance.sku_id=(current->>'skuId')::uuid AND balance.base_unit=current->>'baseUnit'
                        AND balance.location_id=(current->>'sourceLocationId')::uuid AND location.kind='TECHNICIAN'
                        AND location.state='ACTIVE' AND location.custodian_id=command.actor_id
                        AND balance.custody_owner_id=command.actor_id AND balance.custody_owner_kind='TECHNICIAN'
                        AND balance.status='ISSUED' AND balance.quantity_base>=(item->>'quantityBase')::numeric
                        AND balance.warehouse_admission='VERIFIED' AND balance.condition='SERVICEABLE' AND balance.legal_owner='ISP'
                        AND segment.state='ACTIVE' AND segment.warehouse_admission='VERIFIED'
                        AND (current->>'tracking'<>'SERIAL' OR asset.serial_number IS NOT DISTINCT FROM item->>'serial'
                            AND asset.mac_address IS NOT DISTINCT FROM item->>'mac' AND asset.custody_owner_id=command.actor_id
                            AND asset.location_id=balance.location_id AND asset.status='ISSUED')) THEN
                    RAISE EXCEPTION 'return submission requires current technician ownership' USING ERRCODE='23514'; END IF;
            END IF;
            position:=position+1;
        END LOOP;
        IF total>9223372036854775807 OR current->>'quantityBase' IS DISTINCT FROM total::text THEN
            RAISE EXCEPTION 'return total differs from selected material' USING ERRCODE='23514'; END IF;
        IF command.action='SUBMIT' THEN
            IF command.revision<>0 OR current->>'state' IS DISTINCT FROM 'PENDING' OR command.permission_code<>'warehouse.return.own'
                OR current->>'technicianId' IS DISTINCT FROM command.actor_id::text OR command.notes IS DISTINCT FROM input->>'reason'
                OR current->>'reason' IS DISTINCT FROM input->>'reason' OR coalesce(btrim(input->>'reason'),'')='' OR length(input->>'reason')>1000
                OR current->>'warehouseId' IS DISTINCT FROM input->>'warehouseId' OR current->>'skuId' IS DISTINCT FROM input->>'skuId'
                OR jsonb_array_length(current->'lines')<>jsonb_array_length(input->'lines')
                OR current->>'reviewedBy' IS NOT NULL OR current->>'reviewerName' IS NOT NULL OR current->>'reviewNotes' IS NOT NULL
                OR current->>'movementId' IS NOT NULL THEN
                RAISE EXCEPTION 'return pending submission cannot change stock or review' USING ERRCODE='23514'; END IF;
        ELSE
            IF previous IS NULL OR command.revision<>1 OR previous->>'state' IS DISTINCT FROM 'PENDING'
                OR input->>'expectedRevision' IS DISTINCT FROM '0' OR jsonb_typeof(input->'received') IS DISTINCT FROM 'boolean'
                OR current-ARRAY['revision','state','updatedAt','reviewedBy','reviewerName','reviewNotes','movementId'] IS DISTINCT FROM
                    previous-ARRAY['revision','state','updatedAt','reviewedBy','reviewerName','reviewNotes','movementId']
                OR command.permission_code<>'warehouse.return.manage' OR current->>'reviewedBy' IS DISTINCT FROM command.actor_id::text
                OR current->>'reviewNotes' IS DISTINCT FROM input->>'notes' OR command.notes IS DISTINCT FROM input->>'notes'
                OR current->>'movementId' IS DISTINCT FROM command.movement_id::text
                OR current->>'state' IS DISTINCT FROM (CASE WHEN (input->>'received')::boolean THEN 'RECEIVED' ELSE 'REJECTED' END)
                OR NOT (input->>'received')::boolean AND coalesce(btrim(input->>'notes'),'')='' THEN
                RAISE EXCEPTION 'return receipt or rejection must preserve submitted material' USING ERRCODE='23514'; END IF;
            IF command.movement_id IS NOT NULL THEN
                SELECT * INTO STRICT binding FROM inventory_reference_post WHERE tenant_id=scope AND document_id=command.movement_id;
                SELECT * INTO STRICT operation FROM inventory_operation WHERE tenant_id=scope AND id=binding.id;
                SELECT canonical_payload::jsonb INTO STRICT canonical FROM inventory_command_identity WHERE tenant_id=scope AND id=binding.id;
                SELECT jsonb_agg(value-ARRAY['serial','mac'] ORDER BY ordinality) INTO requested
                    FROM jsonb_array_elements(current->'lines') WITH ORDINALITY;
                IF binding.action<>'RETURN' OR binding.created_xid IS DISTINCT FROM command.created_xid
                    OR operation.actor_id IS DISTINCT FROM command.actor_id OR operation.authority_epoch IS DISTINCT FROM command.authority_epoch
                    OR operation.cutover_epoch IS DISTINCT FROM command.cutover_epoch OR operation.operation_key IS DISTINCT FROM 'return:'||target||':1'
                    OR binding.source_location_id::text IS DISTINCT FROM current->>'sourceLocationId'
                    OR binding.destination_location_id::text IS DISTINCT FROM current->>'warehouseId'
                    OR canonical IS DISTINCT FROM jsonb_build_object('sourceWarehouseId',current->'sourceLocationId','warehouseId',current->'warehouseId',
                        'technicianId',current->'technicianId','skuId',current->'skuId','lines',requested,'notes',input->'notes') THEN
                    RAISE EXCEPTION 'return receipt requires exact owned stock movement' USING ERRCODE='23514'; END IF;
                PERFORM warehouse_assert_reference_post(scope,command.movement_id);
            END IF;
        END IF;
        previous:=current;
    END LOOP;
    IF previous IS DISTINCT FROM projection.snapshot THEN RAISE EXCEPTION 'return projection must equal latest command' USING ERRCODE='23514'; END IF;
END $$;
CREATE FUNCTION warehouse_reference_return_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_reference_return(NEW.tenant_id,
        (CASE WHEN TG_TABLE_NAME='inventory_reference_return' THEN to_jsonb(NEW)->>'id' ELSE to_jsonb(NEW)->>'resource_id' END)::uuid);
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_reference_return_final AFTER INSERT OR UPDATE ON inventory_reference_return
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_return_final_guard();
CREATE CONSTRAINT TRIGGER warehouse_reference_return_final AFTER INSERT ON inventory_reference_return_command
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_return_final_guard();
