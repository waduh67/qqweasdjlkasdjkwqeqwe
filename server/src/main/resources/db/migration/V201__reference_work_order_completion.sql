CREATE TABLE work_order_reference_completion (
    tenant_id uuid NOT NULL REFERENCES tenant(id), work_order_id uuid NOT NULL,
    revision bigint NOT NULL CHECK(revision>0), assignment_generation bigint NOT NULL CHECK(assignment_generation>=0),
    technician_id uuid NOT NULL, document_id uuid, completed_at timestamptz NOT NULL,
    proof_canonical text NOT NULL, proof_hash varchar(64) NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,work_order_id), UNIQUE(tenant_id,document_id),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order_reference(tenant_id,id),
    FOREIGN KEY(tenant_id,technician_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,document_id) REFERENCES inventory_document(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
    CHECK(proof_hash=encode(sha256(convert_to(proof_canonical,'UTF8')),'hex'))
);
ALTER TABLE work_order_reference_completion ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_reference_completion FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_order_reference_completion
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER work_order_reference_completion_immutable BEFORE UPDATE OR DELETE ON work_order_reference_completion
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE UPDATE,DELETE,TRUNCATE ON work_order_reference_completion FROM warehouse_app;
        GRANT SELECT,INSERT ON work_order_reference_completion TO warehouse_app;
    END IF;
END $$;
ALTER TABLE work_order_reference_command DROP CONSTRAINT work_order_reference_command_action_check;
ALTER TABLE work_order_reference_command ADD CONSTRAINT work_order_reference_command_action_check
    CHECK(action IN ('SEED','TYPE_SAVE','TYPE_DELETE','CREATE','UPDATE','ASSIGN','PROGRESS','PHOTO','COMPLETE'));
ALTER TABLE inventory_reference_post DROP CONSTRAINT warehouse_reference_stock_action;
ALTER TABLE inventory_reference_post ADD CONSTRAINT warehouse_reference_stock_action
    CHECK(action IN ('RECEIPT','TRANSFER','HANDOVER','RETURN','COUNT_LOSS','COUNT_RECOVER','COUNT_RECEIPT','CONSUME'));
DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_reference_wo_authority()'::regprocedure);
    anchor:='WHEN ''PHOTO'' THEN ''workorder.order.field'' END';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference completion authority anchor changed'; END IF;
    EXECUTE replace(definition,anchor,'WHEN ''PHOTO'' THEN ''workorder.order.field'' WHEN ''COMPLETE'' THEN ''workorder.order.field'' END');
    definition:=pg_get_functiondef('warehouse_assert_reference_wo(uuid,uuid,text)'::regprocedure);
    anchor:='WHEN ''PHOTO'' THEN ARRAY[''revision'',''lastActivityAt''] ELSE';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference completion projection anchor changed'; END IF;
    EXECUTE replace(definition,anchor,'WHEN ''PHOTO'' THEN ARRAY[''revision'',''lastActivityAt'']
        WHEN ''COMPLETE'' THEN ARRAY[''revision'',''state'',''blockedReason'',''lastActivityAt''] ELSE');
END $$;

CREATE FUNCTION warehouse_assert_reference_completion(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE completion work_order_reference_completion; current work_order_reference; source work_order; command work_order_reference_command;
    proof jsonb; input jsonb; expected jsonb; actual jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT completion FROM work_order_reference_completion WHERE tenant_id=scope AND work_order_id=target;
    SELECT * INTO STRICT current FROM work_order_reference WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT source FROM work_order WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT command FROM work_order_reference_command WHERE tenant_id=scope AND resource_kind='WO'
        AND resource_id=target AND revision=completion.revision;
    proof:=completion.proof_canonical::jsonb; input:=command.canonical_payload::jsonb->'input';
    IF command.action<>'COMPLETE' OR command.actor_id IS DISTINCT FROM completion.technician_id OR
        current.technician_id IS DISTINCT FROM completion.technician_id OR current.revision<>completion.revision OR
        current.snapshot->>'state'<>'COMPLETED' OR current.snapshot->'blockedReason'<>'null'::jsonb OR
        current.snapshot->>'assignmentGeneration' IS DISTINCT FROM completion.assignment_generation::text OR
        (current.snapshot->>'lastActivityAt')::timestamptz IS DISTINCT FROM completion.completed_at OR
        source.status<>'DONE' OR source.completed_by IS DISTINCT FROM completion.technician_id OR
        source.completed_at IS DISTINCT FROM completion.completed_at OR source.proof_of_work_hash IS DISTINCT FROM completion.proof_hash OR
        source.resolution_note IS DISTINCT FROM nullif(regexp_replace(input->>'notes','^[[:space:]]+|[[:space:]]+$','','g'),'') OR
        source.approval_status IS NOT NULL OR source.approved_by IS NOT NULL OR source.approved_at IS NOT NULL OR source.approval_note IS NOT NULL OR
        command.notes IS DISTINCT FROM input->>'notes' OR command.original_status<>200 OR
        proof IS DISTINCT FROM jsonb_build_object('workOrderId',target,'revision',completion.revision,
            'assignmentGeneration',completion.assignment_generation,'technicianId',completion.technician_id,
            'notes',input->'notes','completedAt',proof->'completedAt','photos',proof->'photos','materials',proof->'materials','documentId',completion.document_id) OR
        (proof->>'completedAt')::timestamptz IS DISTINCT FROM completion.completed_at OR
        input IS DISTINCT FROM jsonb_build_object('expectedRevision',completion.revision-1,'notes',input->'notes','materials',input->'materials') OR
        jsonb_typeof(proof->'materials') IS DISTINCT FROM 'array' OR jsonb_array_length(proof->'materials')>100 OR
        (jsonb_array_length(proof->'materials')=0) IS DISTINCT FROM (completion.document_id IS NULL) OR
        (current.snapshot->'type'->'materialRequired'='true'::jsonb AND jsonb_array_length(proof->'materials')=0) OR
        EXISTS(SELECT FROM work_order_reference_command WHERE tenant_id=scope AND resource_kind='WO' AND resource_id=target
            AND revision=completion.revision-1 AND snapshot->>'state' NOT IN ('PENDING','BLOCKED')) THEN
        RAISE EXCEPTION 'reference completion differs from immutable command and source' USING ERRCODE='23514'; END IF;
    SELECT coalesce(jsonb_agg(item ORDER BY item->>'slot'),'[]') INTO expected FROM (
        SELECT DISTINCT ON (slot) jsonb_build_object('id',evidence_id,'slot',slot,'sha256',sha256,'sizeBytes',size_bytes,'contentType',content_type) item
        FROM work_order_reference_photo WHERE tenant_id=scope AND work_order_id=target
            AND assignment_generation=completion.assignment_generation AND work_order_revision<completion.revision
        ORDER BY slot,work_order_revision DESC) photos;
    SELECT coalesce(jsonb_agg(item ORDER BY item->>'slot'),'[]') INTO actual FROM jsonb_array_elements(proof->'photos') item;
    IF actual IS DISTINCT FROM expected OR
        (SELECT coalesce(jsonb_agg(item->'slot' ORDER BY item->>'slot'),'[]') FROM jsonb_array_elements(actual) item) IS DISTINCT FROM
        (SELECT jsonb_agg(slot ORDER BY slot#>>'{}') FROM jsonb_array_elements(current.snapshot->'type'->'photoSlots') slot) THEN
        RAISE EXCEPTION 'completion requires every latest current assignment photo' USING ERRCODE='23514'; END IF;
    SELECT coalesce(jsonb_agg(jsonb_build_object('stockIdentityId',item->'stockIdentityId','quantityBase',item->'quantityBase')
        ORDER BY item->>'stockIdentityId'),'[]') INTO expected FROM jsonb_array_elements(proof->'materials') item;
    SELECT coalesce(jsonb_agg(item ORDER BY item->>'stockIdentityId'),'[]') INTO actual FROM jsonb_array_elements(input->'materials') item;
    IF actual IS DISTINCT FROM expected OR
        (SELECT count(DISTINCT item->>'stockIdentityId') FROM jsonb_array_elements(expected) item)<>jsonb_array_length(expected) THEN
        RAISE EXCEPTION 'completion materials differ from requested stock' USING ERRCODE='23514'; END IF;
    IF completion.created_xid=pg_current_xact_id() THEN
        IF command.created_xid<>pg_current_xact_id() OR
            NOT EXISTS(SELECT FROM app_user actor WHERE actor.tenant_id=scope AND actor.id=completion.technician_id AND actor.status='ACTIVE'
                AND NOT actor.platform_admin AND NOT EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=scope AND user_id=actor.id)
                AND EXISTS(SELECT FROM user_role WHERE user_id=actor.id) AND NOT EXISTS(SELECT FROM user_role assignment JOIN role ON role.id=assignment.role_id
                    WHERE assignment.user_id=actor.id AND coalesce(role.default_key,'') NOT IN ('TECHNICIAN_NE','TECHNICIAN_FO'))) OR
            EXISTS(SELECT FROM jsonb_array_elements(proof->'photos') photo LEFT JOIN wo_evidence evidence
                ON evidence.tenant_id=scope AND evidence.id=(photo->>'id')::uuid LEFT JOIN evidence_object_registry registry
                ON registry.tenant_id=scope AND registry.revision_id=evidence.id
                WHERE evidence.revision_state IS DISTINCT FROM 'COMMITTED' OR evidence.purge_state IS DISTINCT FROM 'ACTIVE' OR
                    registry.state IS DISTINCT FROM 'COMMITTED' OR registry.purge_state IS DISTINCT FROM 'ACTIVE') THEN
            RAISE EXCEPTION 'completion requires live technician and committed evidence' USING ERRCODE='23514'; END IF;
    END IF;
END $$;

CREATE FUNCTION warehouse_assert_reference_consume(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE binding inventory_reference_post; completion work_order_reference_completion; document inventory_document;
    operation inventory_operation; movement inventory_movement; command work_order_reference_command;
    proof jsonb; canonical text; expected jsonb; actual jsonb; material jsonb; line inventory_document_line; piece inventory_segment;
    asset inventory_serialized_asset; sku inventory_sku; quantity bigint; debit bigint; retained bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT binding FROM inventory_reference_post WHERE tenant_id=scope AND document_id=target;
    SELECT * INTO STRICT completion FROM work_order_reference_completion WHERE tenant_id=scope AND document_id=target;
    PERFORM warehouse_assert_reference_completion(scope,completion.work_order_id);
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT operation FROM inventory_operation WHERE tenant_id=scope AND id=binding.id;
    SELECT * INTO STRICT movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=binding.id;
    SELECT * INTO STRICT command FROM work_order_reference_command WHERE tenant_id=scope AND resource_kind='WO'
        AND resource_id=completion.work_order_id AND revision=completion.revision;
    SELECT canonical_payload INTO STRICT canonical FROM inventory_command_identity WHERE tenant_id=scope AND id=binding.id;
    proof:=completion.proof_canonical::jsonb;
    IF binding.action<>'CONSUME' OR document.kind<>'USAGE' OR document.state<>'POSTED' OR document.revision<>1 OR
        document.work_order_id IS DISTINCT FROM completion.work_order_id OR document.customer_id IS DISTINCT FROM
            (SELECT customer_id FROM work_order WHERE tenant_id=scope AND id=completion.work_order_id) OR
        document.actor_id IS DISTINCT FROM completion.technician_id OR document.reason IS DISTINCT FROM proof->>'notes' OR
        document.source_document_id IS NOT NULL OR document.plan_revision IS NOT NULL OR document.use_revision IS NOT NULL OR
        document.authority_epoch IS DISTINCT FROM command.authority_epoch OR document.cutover_epoch IS DISTINCT FROM command.cutover_epoch OR
        operation.namespace IS DISTINCT FROM 'warehouse.reference.consume' OR operation.business_action<>'CONSUME' OR
        operation.operation_key IS DISTINCT FROM 'complete:'||completion.work_order_id||':'||completion.revision OR
        operation.actor_id IS DISTINCT FROM completion.technician_id OR operation.resource_id IS DISTINCT FROM target OR
        operation.resource_scope IS DISTINCT FROM 'reference:'||target OR operation.document_id IS DISTINCT FROM target OR
        operation.document_revision<>1 OR operation.original_status<>201 OR operation.original_body::jsonb IS DISTINCT FROM proof OR
        operation.cutover_epoch IS DISTINCT FROM document.cutover_epoch OR operation.authority_epoch IS DISTINCT FROM document.authority_epoch OR
        canonical IS DISTINCT FROM command.canonical_payload OR operation.payload_hash IS DISTINCT FROM command.payload_hash OR
        movement.document_id IS DISTINCT FROM target OR movement.document_revision<>1 OR movement.kind<>'CONSUME' OR movement.state<>'APPLIED' OR
        movement.compensates_movement_id IS NOT NULL OR movement.actor_id IS DISTINCT FROM operation.actor_id OR
        movement.operation_namespace IS DISTINCT FROM operation.namespace OR movement.operation_key IS DISTINCT FROM operation.operation_key OR
        movement.payload_hash IS DISTINCT FROM operation.payload_hash OR
        (SELECT count(*) FROM inventory_operation WHERE tenant_id=scope AND document_id=target)<>1 OR
        (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND document_id=target)<>1 OR
        NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=binding.id AND document_id=target
            AND document_revision=1 AND event_kind='USE_POSTED') OR
        NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=binding.source_location_id AND kind='TECHNICIAN'
            AND custodian_id=completion.technician_id) OR
        NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=binding.destination_location_id AND kind='TRANSIT' AND code='CONSUMED') OR
        EXISTS(SELECT FROM inventory_customer_material_fact WHERE tenant_id=scope AND posting_id=movement.id) OR
        EXISTS(SELECT FROM inventory_material_usage WHERE tenant_id=scope AND id=target) OR
        EXISTS(SELECT FROM inventory_usage_snapshot WHERE tenant_id=scope AND id=binding.id) THEN
        RAISE EXCEPTION 'reference consumption requires exact completion transaction' USING ERRCODE='23514'; END IF;
    IF completion.created_xid=pg_current_xact_id() AND (binding.created_xid<>pg_current_xact_id() OR
        NOT EXISTS(SELECT FROM inventory_tenant_cutover WHERE tenant_id=scope AND state='ENFORCED' AND workflow_mode='REFERENCE' AND epoch=command.cutover_epoch FOR SHARE) OR
        NOT EXISTS(SELECT FROM iam_authorization_epoch WHERE tenant_id=scope AND epoch=command.authority_epoch FOR SHARE) OR
        EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id IN (binding.source_location_id,binding.destination_location_id) AND state<>'ACTIVE')) THEN
        RAISE EXCEPTION 'reference consumption authority or locations changed' USING ERRCODE='23514'; END IF;
    SELECT jsonb_agg(item ORDER BY item::text) INTO expected FROM jsonb_array_elements(binding.expected_legs) item;
    SELECT jsonb_agg(item ORDER BY item::text) INTO actual FROM (
        SELECT jsonb_build_object('lineId',document_line_id,'direction',direction,'identityId',stock_identity_id,
            'skuId',sku_id,'lotId',lot_id,'locationId',location_id,'custodianId',custody_owner_id,'custodianKind',custody_owner_kind,
            'condition',condition,'legalOwner',legal_owner,'status',status,'quantityBase',quantity_base::text,'baseUnit',base_unit) item
        FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id) legs;
    IF actual IS DISTINCT FROM expected OR
        (SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=target)<>jsonb_array_length(proof->'materials') OR
        EXISTS(SELECT FROM inventory_movement_leg leg LEFT JOIN inventory_document_line line ON line.tenant_id=leg.tenant_id AND line.id=leg.document_line_id
            WHERE leg.tenant_id=scope AND leg.movement_id=movement.id AND line.document_id IS DISTINCT FROM target) THEN
        RAISE EXCEPTION 'reference consumption legs or line count differ' USING ERRCODE='23514'; END IF;
    FOR material IN SELECT value FROM jsonb_array_elements(proof->'materials') LOOP
        SELECT * INTO STRICT line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target AND id=(material->>'lineId')::uuid;
        SELECT * INTO STRICT piece FROM inventory_segment WHERE tenant_id=scope AND id=line.stock_identity_id;
        SELECT * INTO STRICT sku FROM inventory_sku WHERE tenant_id=scope AND id=line.sku_id;
        SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=scope AND id=piece.asset_id;
        quantity:=(material->>'quantityBase')::bigint;
        SELECT coalesce(sum(quantity_base),0) INTO debit FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id AND direction='OUT';
        SELECT coalesce(sum(quantity_base),0) INTO retained FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id
            AND direction='IN' AND status='ISSUED' AND location_id=binding.source_location_id;
        IF quantity<=0 OR line.quantity_base<>quantity OR line.document_revision<>0 OR line.stock_identity_id IS DISTINCT FROM (material->>'stockIdentityId')::uuid OR
            line.sku_id IS DISTINCT FROM (material->>'skuId')::uuid OR line.base_unit IS DISTINCT FROM material->>'baseUnit' OR line.tracking IS DISTINCT FROM material->>'tracking' OR
            material IS DISTINCT FROM jsonb_build_object('lineId',line.id,'stockIdentityId',line.stock_identity_id,'consumedIdentityId',material->'consumedIdentityId',
                'skuId',line.sku_id,'skuName',material->'skuName','quantityBase',quantity::text,'baseUnit',line.base_unit,'tracking',line.tracking,
                'serial',asset.serial_number,'mac',asset.mac_address) OR
            line.source_line_id IS NOT NULL OR line.accepted_base<>0 OR line.rejected_base<>0 OR line.missing_base<>0 OR
            line.location_id IS DISTINCT FROM binding.source_location_id OR line.destination_location_id IS DISTINCT FROM binding.destination_location_id OR
            line.custodian_id IS DISTINCT FROM completion.technician_id OR line.custodian_kind<>'TECHNICIAN' OR line.condition<>'SERVICEABLE' OR line.legal_owner<>'ISP' OR
            debit<>quantity+retained OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id AND direction='OUT')<>1 OR
            (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id AND direction='IN' AND status='CONSUMED')<>1 OR
            (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id)<>(CASE WHEN retained=0 THEN 2 ELSE 3 END) OR
            NOT EXISTS(SELECT FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id AND direction='IN'
                AND status='CONSUMED' AND stock_identity_id=(material->>'consumedIdentityId')::uuid AND location_id=binding.destination_location_id AND quantity_base=quantity) OR
            EXISTS(SELECT FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=line.id AND
                (sku_id IS DISTINCT FROM line.sku_id OR lot_id IS DISTINCT FROM line.lot_id OR base_unit<>line.base_unit OR
                custody_owner_id IS DISTINCT FROM completion.technician_id OR custody_owner_kind<>'TECHNICIAN' OR condition<>'SERVICEABLE' OR legal_owner<>'ISP' OR
                direction='OUT' AND (stock_identity_id<>line.stock_identity_id OR status<>'ISSUED' OR location_id<>binding.source_location_id) OR
                direction='IN' AND status NOT IN ('ISSUED','CONSUMED'))) THEN
            RAISE EXCEPTION 'reference consumed material dimensions or quantity differ' USING ERRCODE='23514'; END IF;
        IF retained=0 THEN
            IF material->>'consumedIdentityId' IS DISTINCT FROM line.stock_identity_id::text THEN
                RAISE EXCEPTION 'unsplit consumption must retain exact identity' USING ERRCODE='23514'; END IF;
        ELSE
            IF line.base_unit<>'MM' OR piece.state<>'SPLIT' OR piece.quantity_base<>debit OR
                (SELECT count(*) FROM inventory_segment WHERE tenant_id=scope AND parent_segment_id=piece.id)<>2 OR
                NOT EXISTS(SELECT FROM inventory_segment WHERE tenant_id=scope AND id=(material->>'consumedIdentityId')::uuid
                    AND parent_segment_id=piece.id AND kind='CUT' AND quantity_base=quantity) OR
                NOT EXISTS(SELECT FROM inventory_movement_leg leg JOIN inventory_segment child ON child.tenant_id=leg.tenant_id AND child.id=leg.stock_identity_id
                    WHERE leg.tenant_id=scope AND leg.movement_id=movement.id AND leg.document_line_id=line.id AND leg.direction='IN' AND leg.status='ISSUED'
                    AND child.parent_segment_id=piece.id AND child.kind='REMNANT' AND child.quantity_base=retained AND leg.quantity_base=retained
                    AND leg.location_id=binding.source_location_id) THEN
                RAISE EXCEPTION 'reference cable consumption requires exact cut and remnant' USING ERRCODE='23514'; END IF;
        END IF;
        IF completion.created_xid=pg_current_xact_id() THEN
            IF material->>'skuName' IS DISTINCT FROM sku.name OR sku.state<>'ACTIVE' OR
                EXISTS(WITH RECURSIVE ancestry AS (SELECT id,parent_segment_id FROM inventory_segment WHERE tenant_id=scope AND id=piece.id
                    UNION ALL SELECT parent.id,parent.parent_segment_id FROM inventory_segment parent JOIN ancestry child ON child.parent_segment_id=parent.id WHERE parent.tenant_id=scope)
                    SELECT FROM inventory_document_line allocated JOIN inventory_document origin ON origin.tenant_id=allocated.tenant_id AND origin.id=allocated.document_id
                    WHERE allocated.tenant_id=scope AND allocated.stock_identity_id IN (SELECT id FROM ancestry) AND origin.kind='ISSUE') OR
                EXISTS(SELECT FROM inventory_reservation WHERE tenant_id=scope AND stock_identity_id=piece.id AND state='OPEN'
                    AND (reserved_unpicked_base>0 OR reserved_picked_base>0)) THEN
                RAISE EXCEPTION 'reference consumption cannot use legacy allocated stock' USING ERRCODE='23514'; END IF;
        END IF;
    END LOOP;
END $$;

ALTER FUNCTION warehouse_assert_reference_post(uuid,uuid) RENAME TO warehouse_assert_reference_post_v200;
ALTER FUNCTION warehouse_assert_reference_post_legs(uuid,uuid) RENAME TO warehouse_assert_reference_post_legs_v200;
CREATE FUNCTION warehouse_assert_reference_post(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
    IF EXISTS(SELECT FROM inventory_reference_post WHERE tenant_id=scope AND document_id=target AND action='CONSUME') THEN
        PERFORM warehouse_assert_reference_consume(scope,target);
    ELSE PERFORM warehouse_assert_reference_post_v200(scope,target); END IF;
END $$;
CREATE FUNCTION warehouse_assert_reference_post_legs(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
    IF EXISTS(SELECT FROM inventory_reference_post WHERE tenant_id=scope AND document_id=target AND action='CONSUME') THEN
        PERFORM warehouse_assert_reference_consume(scope,target);
    ELSE PERFORM warehouse_assert_reference_post_legs_v200(scope,target); END IF;
END $$;
ALTER FUNCTION warehouse_assert_reference_wo(uuid,uuid,text) RENAME TO warehouse_assert_reference_wo_v200;
CREATE FUNCTION warehouse_assert_reference_wo(scope uuid,target uuid,kind text) RETURNS void LANGUAGE plpgsql AS $$ BEGIN
    PERFORM warehouse_assert_reference_wo_v200(scope,target,kind);
    IF kind='WO' THEN
        IF EXISTS(SELECT FROM work_order_reference WHERE tenant_id=scope AND id=target AND snapshot->>'state'='COMPLETED') THEN
            PERFORM warehouse_assert_reference_completion(scope,target);
        ELSIF EXISTS(SELECT FROM work_order_reference_completion WHERE tenant_id=scope AND work_order_id=target) THEN
            RAISE EXCEPTION 'completion requires terminal work order' USING ERRCODE='23514'; END IF;
    END IF;
END $$;
CREATE FUNCTION warehouse_reference_completion_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; document uuid; BEGIN
    target:=CASE WHEN TG_TABLE_NAME='work_order_reference_command' THEN NEW.resource_id ELSE NEW.work_order_id END;
    IF TG_TABLE_NAME='work_order_reference_command' AND NEW.action<>'COMPLETE' THEN RETURN NULL; END IF;
    PERFORM warehouse_assert_reference_completion(NEW.tenant_id,target);
    SELECT document_id INTO document FROM work_order_reference_completion WHERE tenant_id=NEW.tenant_id AND work_order_id=target;
    IF document IS NOT NULL THEN PERFORM warehouse_assert_reference_consume(NEW.tenant_id,document); END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER work_order_reference_completion_binding AFTER INSERT ON work_order_reference_completion
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_completion_binding();
CREATE CONSTRAINT TRIGGER work_order_reference_completion_command_binding AFTER INSERT ON work_order_reference_command
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_completion_binding();
DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure); anchor:='IF NOT permitted THEN';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference usage transition anchor changed'; END IF;
    EXECUTE replace(definition,anchor,$body$IF OLD.state='DRAFT' AND NEW.kind='USAGE' AND NEW.state='POSTED' AND EXISTS(
        SELECT FROM inventory_reference_post binding JOIN work_order_reference_completion completion ON completion.tenant_id=binding.tenant_id AND completion.document_id=binding.document_id
        JOIN inventory_operation operation ON operation.tenant_id=binding.tenant_id AND operation.id=binding.id
        WHERE binding.tenant_id=NEW.tenant_id AND binding.document_id=NEW.id AND binding.action='CONSUME'
            AND completion.created_xid=pg_current_xact_id() AND binding.created_xid=pg_current_xact_id()
            AND operation.namespace='warehouse.reference.consume' AND operation.document_revision=NEW.revision) THEN permitted:=true; END IF;
    IF NOT permitted THEN$body$);
    definition:=pg_get_functiondef('warehouse_material_usage_bound_guard()'::regprocedure);
    anchor:='IF NEW.kind=''USAGE'' AND NEW.state<>''DRAFT'' THEN target:=NEW.id; END IF;';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference material usage binding anchor changed'; END IF;
    EXECUTE replace(definition,anchor,$body$IF NEW.kind='USAGE' AND NEW.state<>'DRAFT' THEN
        IF EXISTS(SELECT FROM inventory_reference_post WHERE tenant_id=NEW.tenant_id AND document_id=NEW.id AND action='CONSUME') THEN
            PERFORM warehouse_assert_reference_consume(NEW.tenant_id,NEW.id);
        ELSE target:=NEW.id; END IF;
    END IF;$body$);
END $$;
