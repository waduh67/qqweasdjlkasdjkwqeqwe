CREATE FUNCTION warehouse_reference_count_state(scope uuid,sku uuid,location uuid) RETURNS jsonb LANGUAGE plpgsql AS $$
DECLARE positions jsonb; assets jsonb; result jsonb; BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT coalesce(jsonb_agg(jsonb_build_object(
        'balanceId',balance.id,'dimension',jsonb_build_object('skuId',balance.sku_id,'stockIdentityId',balance.stock_identity_id,
            'lotId',balance.lot_id,'locationId',balance.location_id,'custodianId',balance.custody_owner_id,
            'custodianKind',balance.custody_owner_kind,'condition',balance.condition,'legalOwner',balance.legal_owner),
        'quantityBase',balance.quantity_base::text,'status',balance.status,'balanceRevision',balance.revision,
        'pieceRevision',segment.revision,'serial',asset.serial_number,'mac',asset.mac_address) ORDER BY balance.id),'[]'::jsonb) INTO positions
        FROM inventory_balance_projection balance JOIN inventory_segment segment ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
        LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
        WHERE balance.tenant_id=scope AND balance.sku_id=sku AND balance.location_id=location
            AND balance.warehouse_admission='VERIFIED' AND segment.warehouse_admission='VERIFIED' AND segment.state='ACTIVE';
    SELECT coalesce(jsonb_agg(jsonb_build_object('id',asset.id,'revision',asset.revision,'locationId',asset.location_id,
        'custodianId',asset.custody_owner_id,'custodianKind',asset.custody_owner_kind,'status',asset.status,
        'condition',asset.condition,'legalOwner',asset.legal_owner) ORDER BY asset.id),'[]'::jsonb) INTO assets
        FROM inventory_serialized_asset asset WHERE asset.tenant_id=scope AND asset.warehouse_sku_id=sku AND asset.location_id=location;
    SELECT jsonb_build_object('sku',jsonb_build_object('id',item.id,'revision',item.revision,'state',item.state,'tracking',item.tracking,'baseUnit',item.base_unit),
        'location',jsonb_build_object('id',place.id,'revision',place.revision,'state',place.state,'kind',place.kind,'custodianId',place.custodian_id),
        'positions',positions,'assets',assets) INTO result FROM inventory_sku item CROSS JOIN inventory_location place
        WHERE item.tenant_id=scope AND item.id=sku AND place.tenant_id=scope AND place.id=location;
    IF result IS NULL THEN RAISE EXCEPTION 'count source missing' USING ERRCODE='23514'; END IF;
    RETURN result;
END $$;

CREATE TABLE inventory_reference_count_snapshot (
    id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES tenant(id),sku_id uuid NOT NULL,location_id uuid NOT NULL,
    actor_id uuid NOT NULL,authority_epoch bigint NOT NULL CHECK(authority_epoch>=0),cutover_epoch bigint NOT NULL CHECK(cutover_epoch>=0),
    permission_code text NOT NULL DEFAULT 'warehouse.count.manage' CHECK(permission_code='warehouse.count.manage'),
    book_state jsonb NOT NULL,snapshot_hash text NOT NULL CHECK(snapshot_hash=encode(sha256(convert_to(book_state::text,'UTF8')),'hex')),
    snapshot jsonb NOT NULL,loaded_at timestamptz NOT NULL,created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(tenant_id,id),FOREIGN KEY(tenant_id,sku_id) REFERENCES inventory_sku(tenant_id,id),
    FOREIGN KEY(tenant_id,location_id) REFERENCES inventory_location(tenant_id,id),FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE TABLE inventory_reference_count_attempt (
    id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES tenant(id),snapshot_id uuid NOT NULL,operation_key varchar(240) NOT NULL CHECK(btrim(operation_key)<>''),
    actor_id uuid NOT NULL,authority_epoch bigint NOT NULL CHECK(authority_epoch>=0),cutover_epoch bigint NOT NULL CHECK(cutover_epoch>=0),
    permission_code text NOT NULL DEFAULT 'warehouse.count.manage' CHECK(permission_code='warehouse.count.manage'),canonical_payload text NOT NULL,
    payload_hash text NOT NULL CHECK(payload_hash=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex')),
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,snapshot_id),UNIQUE(tenant_id,operation_key),
    FOREIGN KEY(tenant_id,snapshot_id) REFERENCES inventory_reference_count_snapshot(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE TABLE inventory_reference_count (
    id uuid PRIMARY KEY,tenant_id uuid NOT NULL REFERENCES tenant(id),snapshot_id uuid NOT NULL,operation_key varchar(240) NOT NULL,
    actor_id uuid NOT NULL,authority_epoch bigint NOT NULL CHECK(authority_epoch>=0),cutover_epoch bigint NOT NULL CHECK(cutover_epoch>=0),
    permission_code text NOT NULL DEFAULT 'warehouse.count.manage' CHECK(permission_code='warehouse.count.manage'),canonical_payload text NOT NULL,
    payload_hash text NOT NULL CHECK(payload_hash=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex')),snapshot jsonb NOT NULL,recorded_at timestamptz NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),UNIQUE(tenant_id,id),UNIQUE(tenant_id,snapshot_id),UNIQUE(tenant_id,operation_key),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_reference_count_attempt(tenant_id,id),
    FOREIGN KEY(tenant_id,snapshot_id) REFERENCES inventory_reference_count_snapshot(tenant_id,id),FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE TABLE inventory_reference_count_movement (
    tenant_id uuid NOT NULL REFERENCES tenant(id),count_id uuid NOT NULL,document_id uuid NOT NULL,direction text NOT NULL CHECK(direction IN ('LOSS','RECOVER','SURPLUS')),
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),PRIMARY KEY(tenant_id,document_id),UNIQUE(tenant_id,count_id,direction),
    FOREIGN KEY(tenant_id,count_id) REFERENCES inventory_reference_count(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY(tenant_id,document_id) REFERENCES inventory_document(tenant_id,id)
);
CREATE INDEX warehouse_reference_count_list ON inventory_reference_count(tenant_id,recorded_at DESC,id);
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_reference_count_snapshot','inventory_reference_count_attempt','inventory_reference_count','inventory_reference_count_movement'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        EXECUTE format('CREATE TRIGGER warehouse_reference_count_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN EXECUTE format('GRANT SELECT,INSERT ON %I TO warehouse_app',relation); END IF;
    END LOOP;
END $$;

CREATE FUNCTION warehouse_reference_count_access() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE loaded inventory_reference_count_snapshot; place inventory_location; input jsonb; positions jsonb; total numeric; BEGIN
    PERFORM warehouse_reference_count_actor(NEW.tenant_id,NEW.actor_id);
    PERFORM 1 FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id AND state='ENFORCED' AND workflow_mode='REFERENCE' AND epoch=NEW.cutover_epoch FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'count requires current reference writer' USING ERRCODE='23514'; END IF;
    PERFORM 1 FROM iam_authorization_epoch WHERE tenant_id=NEW.tenant_id AND epoch=NEW.authority_epoch FOR SHARE;
    IF NOT FOUND OR NEW.created_xid<>pg_current_xact_id() THEN RAISE EXCEPTION 'count requires current authority' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_lock_location_topology(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_reference_count_snapshot' THEN
        SELECT * INTO STRICT place FROM inventory_location WHERE tenant_id=NEW.tenant_id AND id=NEW.location_id FOR UPDATE;
        PERFORM warehouse_reference_count_scope(NEW.tenant_id,NEW.actor_id,place.id);
        IF NEW.book_state IS DISTINCT FROM warehouse_reference_count_state(NEW.tenant_id,NEW.sku_id,NEW.location_id)
            OR NEW.snapshot->>'id' IS DISTINCT FROM NEW.id::text OR NEW.snapshot->>'skuId' IS DISTINCT FROM NEW.sku_id::text
            OR NEW.snapshot->>'locationId' IS DISTINCT FROM NEW.location_id::text OR NEW.snapshot->>'snapshotHash' IS DISTINCT FROM NEW.snapshot_hash
            OR (NEW.snapshot->>'loadedAt')::timestamptz IS DISTINCT FROM NEW.loaded_at
            OR NEW.snapshot->>'tracking' IS DISTINCT FROM NEW.book_state#>>'{sku,tracking}'
            OR NEW.snapshot->>'baseUnit' IS DISTINCT FROM NEW.book_state#>>'{sku,baseUnit}' THEN
            RAISE EXCEPTION 'count snapshot differs from current ledger' USING ERRCODE='23514'; END IF;
        SELECT coalesce(jsonb_agg(value ORDER BY value->>'balanceId'),'[]'::jsonb),coalesce(sum((value->>'quantityBase')::numeric),0) INTO positions,total
            FROM jsonb_array_elements(NEW.book_state->'positions') WHERE (value->>'quantityBase')::numeric>0 AND value->>'status' IN ('AVAILABLE','ISSUED')
                AND value#>>'{dimension,condition}'='SERVICEABLE' AND value#>>'{dimension,legalOwner}'='ISP'
                AND value->>'status'=(CASE place.kind WHEN 'TECHNICIAN' THEN 'ISSUED' ELSE 'AVAILABLE' END)
                AND value#>>'{dimension,custodianKind}'=(CASE place.kind WHEN 'TECHNICIAN' THEN 'TECHNICIAN' ELSE 'WAREHOUSE' END)
                AND value#>>'{dimension,custodianId}'=coalesce(place.custodian_id,place.id)::text;
        IF NEW.snapshot->'positions' IS DISTINCT FROM positions OR NEW.snapshot->>'bookBase' IS DISTINCT FROM total::text
            OR total>9223372036854775807 THEN RAISE EXCEPTION 'count book quantity mismatch' USING ERRCODE='23514'; END IF;
    ELSE
        SELECT * INTO STRICT loaded FROM inventory_reference_count_snapshot WHERE tenant_id=NEW.tenant_id AND id=NEW.snapshot_id FOR UPDATE;
        PERFORM warehouse_reference_count_scope(NEW.tenant_id,NEW.actor_id,loaded.location_id);
        IF loaded.cutover_epoch<>NEW.cutover_epoch THEN RAISE EXCEPTION 'count snapshot epoch changed' USING ERRCODE='23514'; END IF;
        IF TG_TABLE_NAME='inventory_reference_count_attempt' THEN
            PERFORM id FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND sku_id=loaded.sku_id AND location_id=loaded.location_id ORDER BY id FOR UPDATE;
            IF loaded.book_state IS DISTINCT FROM warehouse_reference_count_state(NEW.tenant_id,loaded.sku_id,loaded.location_id) THEN
                RAISE EXCEPTION 'count snapshot is stale' USING ERRCODE='23514'; END IF;
            input:=NEW.canonical_payload::jsonb->'input';
            IF NEW.canonical_payload::jsonb IS DISTINCT FROM jsonb_build_object('id',NULL,'input',input)
                OR input IS DISTINCT FROM jsonb_build_object('snapshotId',loaded.id,'physicalBase',input->'physicalBase','serials',input->'serials','reason',input->'reason')
                OR coalesce(input->>'physicalBase','')!~'^[0-9]+$' OR (input->>'physicalBase')::numeric>9223372036854775807
                OR btrim(coalesce(input->>'reason',''))='' OR length(input->>'reason')>1000
                OR jsonb_typeof(input->'serials') IS DISTINCT FROM 'array'
                OR loaded.snapshot->>'tracking'='SERIAL' AND (input->>'physicalBase')::numeric<>jsonb_array_length(input->'serials')
                OR loaded.snapshot->>'tracking'<>'SERIAL' AND input->'serials' IS DISTINCT FROM '[]'::jsonb THEN
                RAISE EXCEPTION 'count physical input mismatch' USING ERRCODE='23514'; END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE FUNCTION warehouse_reference_count_actor(scope uuid,actor uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    IF NOT EXISTS(SELECT FROM app_user user_row WHERE user_row.tenant_id=scope AND user_row.id=actor AND user_row.status='ACTIVE'
        AND (EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=scope AND user_id=actor) OR EXISTS(SELECT FROM user_role link
            JOIN role ON role.id=link.role_id AND role.tenant_id=scope AND role.default_key='ADMIN'
            JOIN role_permission grant_row ON grant_row.role_id=role.id JOIN permission ON permission.id=grant_row.permission_id
                AND permission.active AND permission.code='warehouse.count.manage' WHERE link.user_id=actor))) THEN
        RAISE EXCEPTION 'count requires owner or stable Admin role' USING ERRCODE='42501'; END IF;
END $$;
CREATE FUNCTION warehouse_reference_count_scope(scope uuid,actor uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF NOT warehouse_reference_count_visible(scope,actor,target) THEN
        RAISE EXCEPTION 'count location outside actor scope' USING ERRCODE='42501'; END IF;
END $$;
CREATE FUNCTION warehouse_reference_count_visible(scope uuid,actor uuid,target uuid) RETURNS boolean LANGUAGE plpgsql AS $$
DECLARE place inventory_location; BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO place FROM inventory_location WHERE tenant_id=scope AND id=target;
    IF NOT FOUND OR place.state<>'ACTIVE' OR place.kind NOT IN ('WAREHOUSE','BIN','TECHNICIAN') THEN RETURN false; END IF;
    IF place.kind='TECHNICIAN' AND NOT EXISTS(SELECT FROM app_user user_row WHERE user_row.tenant_id=scope AND user_row.id=place.custodian_id
        AND user_row.status='ACTIVE' AND EXISTS(SELECT FROM user_role link JOIN role ON role.id=link.role_id AND role.tenant_id=scope
            WHERE link.user_id=user_row.id AND (role.default_key IN ('TECHNICIAN_NE','TECHNICIAN_FO','TECHNICIAN_LEGACY') OR role.name='Teknisi'))) THEN RETURN false; END IF;
    IF EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=scope AND user_id=actor) THEN RETURN true; END IF;
    RETURN EXISTS(WITH RECURSIVE scoped(id) AS (SELECT location_id FROM inventory_warehouse_scope
        WHERE tenant_id=scope AND user_id=actor AND state='ACTIVE' UNION SELECT location.id FROM inventory_location location JOIN scoped
            ON location.parent_location_id=scoped.id WHERE location.tenant_id=scope AND location.state='ACTIVE')
        SELECT FROM scoped WHERE id=target AND EXISTS(SELECT FROM user_area link WHERE link.user_id=actor AND link.area_id=place.area_id)
        UNION ALL SELECT FROM inventory_reference_post handover JOIN scoped ON scoped.id=handover.source_location_id
            JOIN inventory_location warehouse ON warehouse.tenant_id=scope AND warehouse.id=scoped.id AND warehouse.state='ACTIVE'
            JOIN inventory_warehouse_scope technician_scope ON technician_scope.tenant_id=scope AND technician_scope.user_id=place.custodian_id
                AND technician_scope.location_id=warehouse.id AND technician_scope.state='ACTIVE'
            JOIN user_area admin_area ON admin_area.user_id=actor AND admin_area.area_id=warehouse.area_id
            JOIN user_area technician_area ON technician_area.user_id=place.custodian_id AND technician_area.area_id=warehouse.area_id
            WHERE place.kind='TECHNICIAN' AND place.area_id IS NULL AND handover.tenant_id=scope
                AND handover.action='HANDOVER' AND handover.destination_location_id=place.id);
END $$;
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_reference_count_snapshot','inventory_reference_count_attempt','inventory_reference_count'] LOOP
        EXECUTE format('CREATE TRIGGER warehouse_reference_count_access BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_reference_count_access()',relation);
    END LOOP;
END $$;

ALTER TABLE inventory_reference_post DROP CONSTRAINT warehouse_reference_stock_action;
ALTER TABLE inventory_reference_post ADD CONSTRAINT warehouse_reference_stock_action CHECK(action IN ('RECEIPT','TRANSFER','HANDOVER','RETURN','COUNT_LOSS','COUNT_RECOVER','COUNT_RECEIPT'));
ALTER FUNCTION warehouse_assert_reference_post(uuid,uuid) RENAME TO warehouse_assert_reference_post_v191;
ALTER FUNCTION warehouse_assert_reference_post_legs(uuid,uuid) RENAME TO warehouse_assert_reference_post_legs_v191;
CREATE FUNCTION warehouse_assert_reference_count_post(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE binding inventory_reference_post; link inventory_reference_count_movement; attempt inventory_reference_count_attempt;
    loaded inventory_reference_count_snapshot; document inventory_document; operation inventory_operation; movement inventory_movement;
    request jsonb; body jsonb; actual jsonb; expected jsonb; line inventory_document_line; item jsonb; frozen jsonb; intake jsonb;
    place inventory_location; quantity numeric; missing uuid; expected_count bigint; is_loss boolean; BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT binding FROM inventory_reference_post WHERE tenant_id=scope AND document_id=target;
    SELECT * INTO STRICT link FROM inventory_reference_count_movement WHERE tenant_id=scope AND document_id=target;
    SELECT * INTO STRICT attempt FROM inventory_reference_count_attempt WHERE tenant_id=scope AND id=link.count_id;
    SELECT * INTO STRICT loaded FROM inventory_reference_count_snapshot WHERE tenant_id=scope AND id=attempt.snapshot_id;
    SELECT * INTO STRICT place FROM inventory_location WHERE tenant_id=scope AND id=loaded.location_id;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT operation FROM inventory_operation WHERE tenant_id=scope AND id=binding.id;
    SELECT * INTO STRICT movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=binding.id;
    SELECT canonical_payload::jsonb INTO STRICT request FROM inventory_command_identity WHERE tenant_id=scope AND id=binding.id;
    body:=operation.original_body::jsonb; is_loss:=link.direction='LOSS';
    IF binding.action IS DISTINCT FROM (CASE link.direction WHEN 'SURPLUS' THEN 'COUNT_RECEIPT' ELSE 'COUNT_'||link.direction END)
        OR binding.created_xid<>attempt.created_xid OR link.created_xid<>attempt.created_xid
        OR document.kind IS DISTINCT FROM (CASE link.direction WHEN 'SURPLUS' THEN 'RECEIPT' ELSE 'ADJUSTMENT' END)
        OR document.state IS DISTINCT FROM (CASE link.direction WHEN 'SURPLUS' THEN 'PUTAWAY' ELSE 'POSTED' END) OR document.revision<>1
        OR operation.namespace IS DISTINCT FROM 'warehouse.reference.'||lower(binding.action) OR operation.business_action IS DISTINCT FROM binding.action
        OR operation.document_id IS DISTINCT FROM target OR operation.resource_id IS DISTINCT FROM target OR operation.document_revision<>1
        OR operation.resource_scope IS DISTINCT FROM 'reference:'||target OR operation.original_status<>201
        OR operation.operation_key IS DISTINCT FROM 'count:'||attempt.id||':'||lower(link.direction)
        OR document.actor_id<>attempt.actor_id OR operation.actor_id<>attempt.actor_id OR movement.actor_id<>attempt.actor_id
        OR document.cutover_epoch<>attempt.cutover_epoch OR operation.cutover_epoch<>attempt.cutover_epoch OR movement.cutover_epoch<>attempt.cutover_epoch
        OR document.authority_epoch<>attempt.authority_epoch OR operation.authority_epoch<>attempt.authority_epoch OR movement.authority_epoch<>attempt.authority_epoch
        OR movement.document_id<>target OR movement.document_revision<>1 OR movement.operation_namespace<>operation.namespace
        OR movement.operation_key<>operation.operation_key OR movement.payload_hash<>operation.payload_hash OR movement.state<>'APPLIED'
        OR movement.kind IS DISTINCT FROM (CASE link.direction WHEN 'SURPLUS' THEN 'RECEIVE' ELSE 'COUNT_VARIANCE' END)
        OR document.work_order_id IS NOT NULL OR document.source_document_id IS NOT NULL
        OR operation.payload_hash IS DISTINCT FROM (SELECT encode(sha256(convert_to(canonical_payload,'UTF8')),'hex') FROM inventory_command_identity WHERE tenant_id=scope AND id=binding.id)
        OR body->>'id' IS DISTINCT FROM target::text OR body->>'operationId' IS DISTINCT FROM binding.id::text
        OR body->>'revision' IS DISTINCT FROM '1' OR body->>'kind' IS DISTINCT FROM binding.action OR body->>'state' IS DISTINCT FROM document.state
        OR body->>'warehouseId' IS DISTINCT FROM binding.destination_location_id::text
        OR body->>'notes' IS DISTINCT FROM attempt.canonical_payload::jsonb#>>'{input,reason}'
        OR request->>'notes' IS DISTINCT FROM body->>'notes' OR movement.reason IS DISTINCT FROM body->>'notes'
        OR (SELECT count(*) FROM inventory_operation WHERE tenant_id=scope AND document_id=target)<>1
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND document_id=target)<>1
        OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=binding.id AND document_id=target AND document_revision=1) THEN
        RAISE EXCEPTION 'count posting command mismatch' USING ERRCODE='23514'; END IF;
    SELECT jsonb_agg(value ORDER BY value::text) INTO expected FROM jsonb_array_elements(binding.expected_legs);
    SELECT jsonb_agg(value ORDER BY value::text) INTO actual FROM (SELECT jsonb_build_object('lineId',document_line_id,'direction',direction,
        'identityId',stock_identity_id,'skuId',sku_id,'lotId',lot_id,'locationId',location_id,'custodianId',custody_owner_id,
        'custodianKind',custody_owner_kind,'condition',condition,'legalOwner',legal_owner,'status',status,'quantityBase',quantity_base::text,'baseUnit',base_unit) value
        FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id) rows;
    IF actual IS DISTINCT FROM expected OR EXISTS(SELECT FROM inventory_movement_leg leg LEFT JOIN inventory_document_line row
        ON row.tenant_id=leg.tenant_id AND row.id=leg.document_line_id WHERE leg.tenant_id=scope AND leg.movement_id=movement.id AND
        (row.document_id IS DISTINCT FROM target OR row.sku_id IS DISTINCT FROM loaded.sku_id OR row.base_unit IS DISTINCT FROM loaded.snapshot->>'baseUnit'
            OR leg.sku_id IS DISTINCT FROM row.sku_id OR leg.base_unit IS DISTINCT FROM row.base_unit OR leg.lot_id IS DISTINCT FROM row.lot_id)) THEN
        RAISE EXCEPTION 'count frozen legs mismatch' USING ERRCODE='23514'; END IF;
    IF link.direction='SURPLUS' THEN
        IF binding.destination_location_id<>loaded.location_id OR request IS DISTINCT FROM jsonb_build_object('warehouseId',loaded.location_id,
            'lines',request->'lines','notes',body->'notes','supplierId',NULL,'reference',NULL) OR jsonb_array_length(request->'lines')<>1
            OR body->'sourceWarehouseId' IS DISTINCT FROM 'null'::jsonb THEN RAISE EXCEPTION 'count surplus receipt request mismatch' USING ERRCODE='23514'; END IF;
        SELECT snapshot::jsonb INTO STRICT intake FROM inventory_receipt_intake WHERE tenant_id=scope AND id=target
            AND source_location_id=binding.source_location_id AND inspection_location_id=binding.destination_location_id;
        item:=request->'lines'->0;
        IF item->>'skuId' IS DISTINCT FROM loaded.sku_id::text OR item->>'quantityBase' !~ '^[0-9]+$'
            OR item->'conversion' IS DISTINCT FROM 'null'::jsonb OR item->'cost' IS DISTINCT FROM 'null'::jsonb
            OR item->>'lotCode' IS DISTINCT FROM (CASE WHEN loaded.snapshot->>'tracking'='SERIAL' THEN NULL ELSE 'OPN-'||attempt.id END) THEN
            RAISE EXCEPTION 'count surplus SKU mismatch' USING ERRCODE='23514'; END IF;
        expected_count:=CASE WHEN loaded.snapshot->>'tracking'='SERIAL' THEN jsonb_array_length(item->'serials') ELSE 1 END;
        IF expected_count<>(SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=target)
            OR expected_count<>jsonb_array_length(intake->'lines') THEN RAISE EXCEPTION 'count surplus line count mismatch' USING ERRCODE='23514'; END IF;
        FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP
            SELECT value INTO frozen FROM jsonb_array_elements(intake->'lines') WHERE value->>'id'=line.id::text;
            IF frozen IS NULL OR frozen->>'quantityBase' IS DISTINCT FROM line.quantity_base::text OR line.document_revision<>0
                OR line.location_id<>loaded.location_id OR line.destination_location_id<>binding.source_location_id OR line.condition<>'SERVICEABLE' OR line.legal_owner<>'ISP'
                OR line.custodian_kind IS DISTINCT FROM (CASE place.kind WHEN 'TECHNICIAN' THEN 'TECHNICIAN' ELSE 'WAREHOUSE' END)
                OR line.custodian_id IS DISTINCT FROM coalesce(place.custodian_id,place.id) THEN RAISE EXCEPTION 'count surplus custody mismatch' USING ERRCODE='23514'; END IF;
            IF line.tracking='SERIAL' THEN
                IF line.quantity_base<>1 OR NOT EXISTS(SELECT FROM jsonb_array_elements(item->'serials') serial
                    WHERE serial=jsonb_build_object('serial',frozen->>'serial','mac',frozen->>'mac'))
                    OR NOT EXISTS(SELECT FROM inventory_serialized_asset WHERE tenant_id=scope AND id=line.stock_identity_id
                        AND origin_document_line_id=line.id AND serial_number IS NOT DISTINCT FROM frozen->>'serial' AND mac_address IS NOT DISTINCT FROM frozen->>'mac') THEN
                    RAISE EXCEPTION 'count surplus serial mismatch' USING ERRCODE='23514'; END IF;
            ELSIF line.quantity_base::text IS DISTINCT FROM item->>'quantityBase' OR NOT EXISTS(SELECT FROM inventory_lot
                WHERE tenant_id=scope AND id=line.lot_id AND origin_document_line_id=line.id AND code='OPN-'||attempt.id) THEN
                RAISE EXCEPTION 'count surplus lot mismatch' USING ERRCODE='23514'; END IF;
            IF (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id)<>2
                OR EXISTS(SELECT FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id AND
                    (stock_identity_id<>line.stock_identity_id OR quantity_base<>line.quantity_base OR condition<>'SERVICEABLE' OR legal_owner<>'ISP'
                        OR direction='OUT' AND (status<>'RECEIPT_SOURCE' OR location_id<>binding.source_location_id OR custody_owner_kind<>'TRANSIT' OR custody_owner_id<>binding.source_location_id)
                        OR direction='IN' AND (location_id<>loaded.location_id OR custody_owner_id<>line.custodian_id OR custody_owner_kind<>line.custodian_kind
                            OR status<>(CASE place.kind WHEN 'TECHNICIAN' THEN 'ISSUED' ELSE 'AVAILABLE' END)))) THEN
                RAISE EXCEPTION 'count surplus movement dimensions mismatch' USING ERRCODE='23514'; END IF;
        END LOOP;
        RETURN;
    END IF;
    SELECT id INTO STRICT missing FROM inventory_location WHERE tenant_id=scope AND code='COUNT-MISSING-'||loaded.location_id AND kind='QUARANTINE';
    IF binding.source_location_id IS DISTINCT FROM (CASE WHEN is_loss THEN loaded.location_id ELSE missing END)
        OR binding.destination_location_id IS DISTINCT FROM (CASE WHEN is_loss THEN missing ELSE loaded.location_id END)
        OR request IS DISTINCT FROM jsonb_build_object('countId',attempt.id,'direction',link.direction,'locationId',loaded.location_id,'skuId',loaded.sku_id,'lines',request->'lines','notes',body->'notes')
        OR body->>'sourceWarehouseId' IS DISTINCT FROM binding.source_location_id::text
        OR jsonb_array_length(request->'lines')<>(SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=target) THEN
        RAISE EXCEPTION 'count adjustment source mismatch' USING ERRCODE='23514'; END IF;
    FOR line IN SELECT * FROM inventory_document_line WHERE tenant_id=scope AND document_id=target LOOP
        item:=request->'lines'->(line.line_number-1);
        IF item IS DISTINCT FROM jsonb_build_object('balanceId',item->'balanceId','stockIdentityId',line.stock_identity_id,'quantityBase',line.quantity_base::text)
            OR line.document_revision<>0 OR line.location_id<>binding.source_location_id OR line.destination_location_id<>binding.destination_location_id
            OR NOT EXISTS(SELECT FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id
                AND direction='OUT' AND stock_identity_id=line.stock_identity_id AND location_id=binding.source_location_id) THEN
            RAISE EXCEPTION 'count adjustment line mismatch' USING ERRCODE='23514'; END IF;
        IF is_loss AND NOT EXISTS(SELECT FROM jsonb_array_elements(loaded.snapshot->'positions') value
            WHERE value->>'balanceId'=item->>'balanceId' AND value#>>'{dimension,stockIdentityId}'=line.stock_identity_id::text
                AND (value->>'quantityBase')::numeric>=line.quantity_base
                AND value#>>'{dimension,lotId}' IS NOT DISTINCT FROM line.lot_id::text
                AND value#>>'{dimension,custodianId}'=line.custodian_id::text
                AND value#>>'{dimension,custodianKind}'=line.custodian_kind AND value#>>'{dimension,condition}'=line.condition) THEN
            RAISE EXCEPTION 'count loss requires loaded owned stock' USING ERRCODE='23514'; END IF;
        IF EXISTS(WITH RECURSIVE ancestry(id,parent_segment_id) AS (SELECT id,parent_segment_id FROM inventory_segment
            WHERE tenant_id=scope AND id=line.stock_identity_id UNION ALL SELECT parent.id,parent.parent_segment_id FROM inventory_segment parent
                JOIN ancestry child ON child.parent_segment_id=parent.id WHERE parent.tenant_id=scope)
            SELECT FROM inventory_document_line allocated JOIN inventory_document issue ON issue.tenant_id=allocated.tenant_id AND issue.id=allocated.document_id
                WHERE allocated.tenant_id=scope AND allocated.stock_identity_id IN (SELECT id FROM ancestry) AND issue.kind='ISSUE')
            OR EXISTS(SELECT FROM inventory_reservation WHERE tenant_id=scope AND stock_identity_id=line.stock_identity_id
                AND state='OPEN' AND (reserved_unpicked_base>0 OR reserved_picked_base>0))
            OR EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=scope AND asset_id=line.stock_identity_id AND ended_at IS NULL) THEN
            RAISE EXCEPTION 'count cannot adjust allocated stock' USING ERRCODE='23514'; END IF;
        IF NOT is_loss AND NOT EXISTS(WITH RECURSIVE ancestry(id,parent_segment_id) AS (SELECT id,parent_segment_id FROM inventory_segment
            WHERE tenant_id=scope AND id=line.stock_identity_id UNION ALL SELECT parent.id,parent.parent_segment_id FROM inventory_segment parent
                JOIN ancestry child ON child.parent_segment_id=parent.id WHERE parent.tenant_id=scope)
            SELECT FROM inventory_movement_leg leg JOIN inventory_movement history ON history.tenant_id=leg.tenant_id AND history.id=leg.movement_id
                JOIN inventory_reference_count_movement prior ON prior.tenant_id=history.tenant_id AND prior.document_id=history.document_id AND prior.direction='LOSS'
                WHERE leg.tenant_id=scope AND leg.stock_identity_id IN (SELECT id FROM ancestry) AND leg.direction='IN' AND leg.location_id=missing AND leg.status='LOST') THEN
            RAISE EXCEPTION 'count recovery requires recorded missing stock' USING ERRCODE='23514'; END IF;
        SELECT coalesce(sum(leg.quantity_base::numeric),0) INTO quantity FROM inventory_movement_leg leg WHERE leg.tenant_id=scope AND leg.movement_id=movement.id
            AND leg.document_line_id=line.id AND leg.direction='IN' AND leg.location_id=binding.destination_location_id;
        IF quantity<>line.quantity_base OR EXISTS(SELECT FROM inventory_movement_leg leg WHERE leg.tenant_id=scope AND leg.movement_id=movement.id AND leg.document_line_id=line.id AND
            (leg.legal_owner<>'ISP' OR leg.location_id NOT IN (missing,loaded.location_id)
                OR leg.location_id=missing AND (leg.condition<>'QUARANTINE' OR leg.status<>'LOST' OR leg.custody_owner_id<>missing OR leg.custody_owner_kind<>'WAREHOUSE')
                OR leg.location_id=loaded.location_id AND (leg.condition<>'SERVICEABLE' OR leg.status<>(CASE place.kind WHEN 'TECHNICIAN' THEN 'ISSUED' ELSE 'AVAILABLE' END)
                    OR leg.custody_owner_kind<>(CASE place.kind WHEN 'TECHNICIAN' THEN 'TECHNICIAN' ELSE 'WAREHOUSE' END) OR leg.custody_owner_id<>coalesce(place.custodian_id,place.id)))) THEN
            RAISE EXCEPTION 'count adjustment quantity or custody mismatch' USING ERRCODE='23514'; END IF;
    END LOOP;
END $$;
CREATE FUNCTION warehouse_assert_reference_post_legs(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
    IF EXISTS(SELECT FROM inventory_reference_post WHERE tenant_id=scope AND document_id=target AND action IN ('COUNT_LOSS','COUNT_RECOVER','COUNT_RECEIPT')) THEN
        PERFORM warehouse_assert_reference_count_post(scope,target); ELSE PERFORM warehouse_assert_reference_post_legs_v191(scope,target); END IF;
END $$;
CREATE FUNCTION warehouse_assert_reference_post(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE count_id uuid; BEGIN
    IF EXISTS(SELECT FROM inventory_reference_post WHERE tenant_id=scope AND document_id=target AND action IN ('COUNT_LOSS','COUNT_RECOVER','COUNT_RECEIPT')) THEN
        PERFORM warehouse_assert_reference_count_post(scope,target);
        SELECT link.count_id INTO STRICT count_id FROM inventory_reference_count_movement link WHERE tenant_id=scope AND document_id=target;
        PERFORM warehouse_assert_reference_count(scope,count_id);
    ELSE PERFORM warehouse_assert_reference_post_v191(scope,target); END IF;
END $$;

DO $$ DECLARE definition text; anchor text:='IF NOT permitted THEN'; BEGIN
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference count transition anchor changed'; END IF;
    definition:=replace(definition,anchor,$body$IF OLD.state='DRAFT' AND (NEW.kind,NEW.state) IN (('ADJUSTMENT','POSTED'),('RECEIPT','PUTAWAY')) AND EXISTS(
        SELECT FROM inventory_reference_post binding JOIN inventory_reference_count_movement link ON link.tenant_id=binding.tenant_id AND link.document_id=binding.document_id
        JOIN inventory_reference_count_attempt attempt ON attempt.tenant_id=link.tenant_id AND attempt.id=link.count_id
        JOIN inventory_operation operation ON operation.tenant_id=binding.tenant_id AND operation.id=binding.id
        WHERE binding.tenant_id=NEW.tenant_id AND binding.document_id=NEW.id AND binding.action IN ('COUNT_LOSS','COUNT_RECOVER','COUNT_RECEIPT')
            AND binding.created_xid=pg_current_xact_id() AND attempt.created_xid=pg_current_xact_id() AND attempt.cutover_epoch=NEW.cutover_epoch
            AND operation.document_revision=NEW.revision AND operation.namespace='warehouse.reference.'||lower(binding.action)) THEN permitted:=true; END IF;
    IF NOT permitted THEN$body$);
    EXECUTE definition;
END $$;

CREATE FUNCTION warehouse_assert_reference_count(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE audit inventory_reference_count; attempt inventory_reference_count_attempt; loaded inventory_reference_count_snapshot; input jsonb; item jsonb;
    loss numeric; recovery numeric; surplus numeric; physical numeric; serials jsonb; row_count bigint; BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT audit FROM inventory_reference_count WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT attempt FROM inventory_reference_count_attempt WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT loaded FROM inventory_reference_count_snapshot WHERE tenant_id=scope AND id=audit.snapshot_id;
    input:=audit.canonical_payload::jsonb->'input'; physical:=(input->>'physicalBase')::numeric;
    IF to_jsonb(audit)-ARRAY['snapshot','recorded_at'] IS DISTINCT FROM to_jsonb(attempt)
        OR audit.snapshot->>'id' IS DISTINCT FROM audit.id::text OR audit.snapshot->'snapshot' IS DISTINCT FROM loaded.snapshot
        OR audit.snapshot->>'physicalBase' IS DISTINCT FROM physical::text
        OR audit.snapshot->>'differenceBase' IS DISTINCT FROM (physical-(loaded.snapshot->>'bookBase')::numeric)::text
        OR audit.snapshot->'serials' IS DISTINCT FROM input->'serials' OR audit.snapshot->>'reason' IS DISTINCT FROM input->>'reason'
        OR audit.snapshot->>'actorId' IS DISTINCT FROM audit.actor_id::text
        OR (audit.snapshot->>'recordedAt')::timestamptz IS DISTINCT FROM audit.recorded_at THEN
        RAISE EXCEPTION 'count audit differs from immutable attempt' USING ERRCODE='23514'; END IF;
    SELECT coalesce(sum(line.quantity_base::numeric) FILTER(WHERE link.direction='LOSS'),0),
        coalesce(sum(line.quantity_base::numeric) FILTER(WHERE link.direction='RECOVER'),0),
        coalesce(sum(line.quantity_base::numeric) FILTER(WHERE link.direction='SURPLUS'),0) INTO loss,recovery,surplus
        FROM inventory_reference_count_movement link JOIN inventory_document_line line ON line.tenant_id=link.tenant_id AND line.document_id=link.document_id
        WHERE link.tenant_id=scope AND link.count_id=target;
    IF (loaded.snapshot->>'bookBase')::numeric-loss+recovery+surplus<>physical
        OR EXISTS(SELECT FROM jsonb_array_elements(audit.snapshot->'movementIds') value WHERE NOT EXISTS(SELECT FROM inventory_reference_count_movement
            WHERE tenant_id=scope AND count_id=target AND document_id=(value#>>'{}')::uuid))
        OR jsonb_array_length(audit.snapshot->'movementIds')<>(SELECT count(*) FROM inventory_reference_count_movement WHERE tenant_id=scope AND count_id=target) THEN
        RAISE EXCEPTION 'count physical quantity differs from exact movements' USING ERRCODE='23514'; END IF;
    FOR item IN SELECT to_jsonb(link) FROM inventory_reference_count_movement link WHERE tenant_id=scope AND count_id=target LOOP
        PERFORM warehouse_assert_reference_count_post(scope,(item->>'document_id')::uuid);
    END LOOP;
    IF loaded.snapshot->>'tracking'='SERIAL' THEN
        WITH physical_serials AS (
            SELECT warehouse_canonical_serial(value->>'serial') serial FROM jsonb_array_elements(loaded.snapshot->'positions')
                WHERE NOT EXISTS(SELECT FROM inventory_reference_count_movement link JOIN inventory_document_line line
                    ON line.tenant_id=link.tenant_id AND line.document_id=link.document_id WHERE link.tenant_id=scope AND link.count_id=target
                        AND link.direction='LOSS' AND line.stock_identity_id=(value#>>'{dimension,stockIdentityId}')::uuid)
            UNION ALL SELECT asset.canonical_serial FROM inventory_reference_count_movement link JOIN inventory_movement movement
                ON movement.tenant_id=link.tenant_id AND movement.document_id=link.document_id JOIN inventory_movement_leg leg
                ON leg.tenant_id=movement.tenant_id AND leg.movement_id=movement.id AND leg.direction='IN' AND leg.location_id=loaded.location_id
                JOIN inventory_serialized_asset asset ON asset.tenant_id=leg.tenant_id AND asset.id=leg.stock_identity_id
                WHERE link.tenant_id=scope AND link.count_id=target AND link.direction IN ('RECOVER','SURPLUS'))
        SELECT coalesce(jsonb_agg(serial ORDER BY serial),'[]'::jsonb),count(*) INTO serials,row_count FROM physical_serials;
        IF serials IS DISTINCT FROM (SELECT coalesce(jsonb_agg(warehouse_canonical_serial(value->>'serial') ORDER BY warehouse_canonical_serial(value->>'serial')),'[]'::jsonb)
            FROM jsonb_array_elements(input->'serials')) OR row_count<>physical
            OR row_count<>(SELECT count(DISTINCT warehouse_canonical_serial(value->>'serial')) FROM jsonb_array_elements(input->'serials')) THEN
            RAISE EXCEPTION 'count physical serial set differs from movements' USING ERRCODE='23514'; END IF;
    ELSIF loss>0 AND recovery+surplus>0 THEN RAISE EXCEPTION 'bulk count cannot lose and gain simultaneously' USING ERRCODE='23514';
    END IF;
END $$;
CREATE FUNCTION warehouse_reference_count_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; BEGIN
    target:=CASE TG_TABLE_NAME WHEN 'inventory_reference_count_movement' THEN NEW.count_id ELSE NEW.id END;
    PERFORM warehouse_assert_reference_count(NEW.tenant_id,target); RETURN NULL;
END $$;
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_reference_count_attempt','inventory_reference_count','inventory_reference_count_movement'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_reference_count_final AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_count_final()',relation);
    END LOOP;
END $$;
