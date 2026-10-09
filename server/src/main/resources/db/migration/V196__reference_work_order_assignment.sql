CREATE TABLE work_order_reference_type (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), name varchar(200) NOT NULL,
    revision bigint NOT NULL CHECK(revision>=0), snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
    UNIQUE(tenant_id,id)
);
CREATE UNIQUE INDEX work_order_reference_type_name ON work_order_reference_type(tenant_id,lower(name));
CREATE TABLE work_order_reference (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), type_id uuid NOT NULL,
    technician_id uuid NOT NULL, area_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>=0),
    snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'), UNIQUE(tenant_id,id),
    FOREIGN KEY(tenant_id,id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,type_id) REFERENCES work_order_reference_type(tenant_id,id),
    FOREIGN KEY(tenant_id,technician_id) REFERENCES app_user(tenant_id,id)
);
CREATE INDEX work_order_reference_owner ON work_order_reference(tenant_id,technician_id,area_id,id);
CREATE TABLE work_order_reference_command (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), resource_id uuid NOT NULL,
    resource_kind varchar(8) NOT NULL CHECK(resource_kind IN ('TYPE','WO')),
    action varchar(20) NOT NULL CHECK(action IN ('SEED','TYPE_SAVE','TYPE_DELETE','CREATE','UPDATE','ASSIGN','PROGRESS')),
    operation_key varchar(240) NOT NULL CHECK(operation_key ~ '^[!-~]+$'), revision bigint NOT NULL CHECK(revision>=0),
    actor_id uuid, authority_epoch bigint NOT NULL CHECK(authority_epoch>=0), cutover_epoch bigint NOT NULL CHECK(cutover_epoch>=0),
    canonical_payload text NOT NULL, payload_hash varchar(64) NOT NULL, snapshot jsonb NOT NULL,
    notes varchar(1000) NOT NULL, permission_code varchar(100) NOT NULL, original_status integer NOT NULL CHECK(original_status IN (200,201)),
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,action,operation_key), UNIQUE(tenant_id,resource_kind,resource_id,revision),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    CHECK(payload_hash=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex')),
    CHECK((resource_kind='TYPE')=(action IN ('SEED','TYPE_SAVE','TYPE_DELETE'))),
    CHECK((actor_id IS NULL)=(action='SEED'))
);
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['work_order_reference_type','work_order_reference','work_order_reference_command'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO warehouse_app',relation); END IF;
    END LOOP;
END $$;
CREATE TRIGGER work_order_reference_command_immutable BEFORE UPDATE OR DELETE ON work_order_reference_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE FUNCTION warehouse_reference_wo_authority() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE input jsonb:=NEW.canonical_payload::jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.action='SEED' THEN
        IF NEW.permission_code<>'SYSTEM' OR NEW.revision<>0 OR NEW.resource_kind<>'TYPE' OR
            NOT ((NEW.snapshot->>'name'='Pasang Baru' AND NEW.snapshot->>'workType'='PSB' AND
                NEW.snapshot->'photoSlots'='["Bukti Pasang","Bukti Kedatangan"]'::jsonb AND NEW.snapshot->'materialRequired'='true'::jsonb) OR
                (NEW.snapshot->>'name'='Maintenance' AND NEW.snapshot->>'workType'='REPAIR' AND
                NEW.snapshot->'photoSlots'='["Bukti"]'::jsonb AND NEW.snapshot->'materialRequired'='false'::jsonb)) OR
            NEW.snapshot->'active'<>'true'::jsonb OR NEW.snapshot->'deleted'<>'false'::jsonb THEN
            RAISE EXCEPTION 'only exact missing defaults may be seeded' USING ERRCODE='23514'; END IF;
        RETURN NEW;
    END IF;
    IF NEW.created_xid IS DISTINCT FROM pg_current_xact_id() OR
        NOT EXISTS(SELECT FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id AND state='ENFORCED'
        AND workflow_mode='REFERENCE' AND epoch=NEW.cutover_epoch FOR SHARE) OR
        NOT EXISTS(SELECT FROM iam_authorization_epoch WHERE tenant_id=NEW.tenant_id AND epoch=NEW.authority_epoch FOR SHARE) OR
        NOT EXISTS(SELECT FROM app_user actor WHERE actor.tenant_id=NEW.tenant_id AND actor.id=NEW.actor_id AND actor.status='ACTIVE' AND
            (EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=NEW.tenant_id AND user_id=actor.id) OR
            (NEW.permission_code<>'OWNER' AND (actor.platform_admin OR EXISTS(
                SELECT FROM user_role assignment JOIN role ON role.id=assignment.role_id AND role.tenant_id=NEW.tenant_id
                JOIN role_permission grant_row ON grant_row.role_id=role.id
                JOIN permission ON permission.id=grant_row.permission_id AND permission.active AND NOT permission.platform_only
                WHERE assignment.user_id=NEW.actor_id AND permission.code=NEW.permission_code))))) THEN
        RAISE EXCEPTION 'reference work order command requires current authority' USING ERRCODE='23514'; END IF;
    IF (NEW.resource_kind='TYPE') IS DISTINCT FROM (NEW.permission_code='OWNER') OR
        input->>'id' IS DISTINCT FROM (CASE WHEN NEW.action IN ('CREATE','TYPE_SAVE') AND NEW.revision=0 THEN NULL ELSE NEW.resource_id::text END) OR
        (NEW.revision>0 AND input->'input'->>'expectedRevision' IS DISTINCT FROM (NEW.revision-1)::text) OR
        NEW.permission_code IS DISTINCT FROM (CASE NEW.action WHEN 'TYPE_SAVE' THEN 'OWNER' WHEN 'TYPE_DELETE' THEN 'OWNER'
            WHEN 'CREATE' THEN 'workorder.order.create' WHEN 'UPDATE' THEN 'workorder.order.update'
            WHEN 'ASSIGN' THEN 'workorder.order.assign' WHEN 'PROGRESS' THEN 'workorder.order.field' END) THEN
        RAISE EXCEPTION 'reference work order command shape differs' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER work_order_reference_command_authority BEFORE INSERT ON work_order_reference_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_wo_authority();

CREATE FUNCTION warehouse_reference_wo_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR
        NEW.revision IS DISTINCT FROM OLD.revision+1 OR (TG_TABLE_NAME='work_order_reference' AND
        (NEW.snapshot->'type' IS DISTINCT FROM OLD.snapshot->'type' OR NEW.snapshot->'createdAt' IS DISTINCT FROM OLD.snapshot->'createdAt' OR
        OLD.snapshot->>'state' IN ('COMPLETED','CANCELLED'))) THEN
        RAISE EXCEPTION 'work order needs an immutable next revision' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER work_order_reference_type_revision BEFORE UPDATE OR DELETE ON work_order_reference_type
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_wo_revision();
CREATE TRIGGER work_order_reference_revision BEFORE UPDATE OR DELETE ON work_order_reference
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_wo_revision();

CREATE FUNCTION warehouse_assert_reference_wo(scope uuid,target uuid,kind text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE current jsonb; rev bigint; command work_order_reference_command; prior jsonb; input jsonb; base work_order;
    row_wo work_order_reference; type_row work_order_reference_type;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    IF kind='TYPE' THEN
        SELECT * INTO STRICT type_row FROM work_order_reference_type WHERE tenant_id=scope AND id=target;
        current:=type_row.snapshot; rev:=type_row.revision;
        IF current->>'name' IS DISTINCT FROM type_row.name OR btrim(type_row.name)='' OR
            jsonb_typeof(current->'photoSlots') IS DISTINCT FROM 'array' OR jsonb_array_length(current->'photoSlots') NOT BETWEEN 1 AND 12 OR
            current->>'workType' NOT IN ('PSB','REPAIR','MIGRATION','DISMANTLE','PREVENTIVE') THEN
            RAISE EXCEPTION 'work order type snapshot invalid' USING ERRCODE='23514'; END IF;
    ELSE
        SELECT * INTO STRICT row_wo FROM work_order_reference WHERE tenant_id=scope AND id=target;
        current:=row_wo.snapshot; rev:=row_wo.revision;
        SELECT * INTO STRICT base FROM work_order WHERE tenant_id=scope AND id=target;
        IF current->>'technicianId' IS DISTINCT FROM row_wo.technician_id::text OR current->>'areaId' IS DISTINCT FROM row_wo.area_id::text OR
            current->'type'->>'id' IS DISTINCT FROM row_wo.type_id::text OR current->>'code' IS DISTINCT FROM base.code OR
            current->'type'->>'workType' IS DISTINCT FROM base.type OR current->>'title' IS DISTINCT FROM base.title OR
            nullif(current->>'description','') IS DISTINCT FROM base.description OR current->>'priority' IS DISTINCT FROM base.priority OR
            current->>'customerId' IS DISTINCT FROM base.customer_id::text OR current->>'areaId' IS DISTINCT FROM base.area_id::text OR
            (current->>'scheduledAt')::timestamptz IS DISTINCT FROM base.scheduled_at OR
            (SELECT array_agg(technician_id) FROM work_order_assignee WHERE tenant_id=scope AND work_order_id=target)
                IS DISTINCT FROM ARRAY[row_wo.technician_id] OR
            current->>'state' NOT IN ('PENDING','BLOCKED','COMPLETED','CANCELLED') OR
            (current->>'state'='BLOCKED' AND length(btrim(coalesce(current->>'blockedReason','')))=0) OR
            (current->>'state' IN ('PENDING','BLOCKED') AND base.status NOT IN ('ASSIGNED','IN_PROGRESS')) OR
            (current->>'state'='COMPLETED' AND base.status<>'DONE') OR (current->>'state'='CANCELLED' AND base.status<>'CANCELLED') THEN
            RAISE EXCEPTION 'work order projection differs from source' USING ERRCODE='23514'; END IF;
    END IF;
    SELECT * INTO STRICT command FROM work_order_reference_command WHERE tenant_id=scope AND resource_kind=kind AND resource_id=target AND revision=rev;
    IF current IS DISTINCT FROM command.snapshot OR current->>'id' IS DISTINCT FROM target::text OR current->>'revision' IS DISTINCT FROM rev::text OR
        (SELECT count(*) FROM work_order_reference_command WHERE tenant_id=scope AND resource_kind=kind AND resource_id=target)<>rev+1 THEN
        RAISE EXCEPTION 'work order snapshot differs from immutable commands' USING ERRCODE='23514'; END IF;
    input:=command.canonical_payload::jsonb->'input';
    IF command.created_xid=pg_current_xact_id() THEN
        IF kind='TYPE' AND command.action<>'SEED' AND (current->>'name' IS DISTINCT FROM input->>'name' AND command.action<>'TYPE_DELETE') THEN
            RAISE EXCEPTION 'type differs from input' USING ERRCODE='23514'; END IF;
        IF kind='WO' AND command.action IN ('CREATE','ASSIGN') AND current->>'technicianId' IS DISTINCT FROM input->>'technicianId' THEN
            RAISE EXCEPTION 'assigned technician differs from command' USING ERRCODE='23514'; END IF;
        IF kind='WO' AND command.action='PROGRESS' AND (command.actor_id<>row_wo.technician_id OR
            current->>'state' IS DISTINCT FROM input->>'state' OR current->>'state' NOT IN ('PENDING','BLOCKED')) THEN
            RAISE EXCEPTION 'progress requires current technician' USING ERRCODE='23514'; END IF;
        IF kind='WO' AND command.action IN ('CREATE','ASSIGN','PROGRESS') AND NOT EXISTS(SELECT FROM app_user actor
            WHERE actor.tenant_id=scope AND actor.id=row_wo.technician_id AND actor.status='ACTIVE' AND NOT actor.platform_admin AND
            NOT EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=scope AND user_id=actor.id) AND
            EXISTS(SELECT FROM user_role WHERE user_id=actor.id) AND NOT EXISTS(SELECT FROM user_role assignment JOIN role ON role.id=assignment.role_id
                WHERE assignment.user_id=actor.id AND coalesce(role.default_key,'') NOT IN ('TECHNICIAN_NE','TECHNICIAN_FO'))) THEN
            RAISE EXCEPTION 'one pure active NE or FO technician required' USING ERRCODE='23514'; END IF;
    END IF;
    IF kind='WO' AND rev=0 THEN
        IF (command.created_xid=pg_current_xact_id() AND current->'type' IS DISTINCT FROM
            (SELECT snapshot FROM work_order_reference_type WHERE tenant_id=scope AND id=row_wo.type_id)) OR
            current->'type'->'active' IS DISTINCT FROM 'true'::jsonb OR current->'type'->'deleted' IS DISTINCT FROM 'false'::jsonb OR
            current->>'assignmentGeneration' IS DISTINCT FROM '0' OR current->>'state'<>'PENDING' THEN
            RAISE EXCEPTION 'new work order requires exact active rules' USING ERRCODE='23514'; END IF;
    ELSIF kind='WO' THEN
        SELECT snapshot INTO STRICT prior FROM work_order_reference_command WHERE tenant_id=scope AND resource_kind=kind AND resource_id=target AND revision=rev-1;
        IF current->'type' IS DISTINCT FROM prior->'type' OR current->'createdAt' IS DISTINCT FROM prior->'createdAt' OR
            (command.action='ASSIGN' AND (current->>'assignmentGeneration')::bigint<>(prior->>'assignmentGeneration')::bigint+1) OR
            (command.action<>'ASSIGN' AND (current->'technicianId' IS DISTINCT FROM prior->'technicianId' OR
                current->'assignmentGeneration' IS DISTINCT FROM prior->'assignmentGeneration')) OR
            (command.action IN ('ASSIGN','PROGRESS') AND (current->>'lastActivityAt')::timestamptz<(prior->>'lastActivityAt')::timestamptz) THEN
            RAISE EXCEPTION 'work order assignment or rule history changed' USING ERRCODE='23514'; END IF;
    END IF;
END $$;
CREATE FUNCTION warehouse_reference_wo_deferred() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='work_order_reference_command' THEN
        PERFORM warehouse_assert_reference_wo(NEW.tenant_id,NEW.resource_id,NEW.resource_kind);
    ELSE
        PERFORM warehouse_assert_reference_wo(NEW.tenant_id,NEW.id,CASE WHEN TG_TABLE_NAME='work_order_reference_type' THEN 'TYPE' ELSE 'WO' END);
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER work_order_reference_type_binding AFTER INSERT OR UPDATE ON work_order_reference_type
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_wo_deferred();
CREATE CONSTRAINT TRIGGER work_order_reference_binding AFTER INSERT OR UPDATE ON work_order_reference
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_wo_deferred();
CREATE CONSTRAINT TRIGGER work_order_reference_command_binding AFTER INSERT ON work_order_reference_command
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_wo_deferred();

CREATE FUNCTION warehouse_seed_work_order_types(scope uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE item jsonb; target uuid; body text; snapshot jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM pg_advisory_xact_lock(hashtextextended(scope::text||'|reference-workorder-types',0));
    FOR item IN SELECT value FROM jsonb_array_elements('[{"name":"Pasang Baru","workType":"PSB","materialRequired":true,"photoSlots":["Bukti Pasang","Bukti Kedatangan"]},{"name":"Maintenance","workType":"REPAIR","materialRequired":false,"photoSlots":["Bukti"]}]'::jsonb) LOOP
        IF EXISTS(SELECT FROM work_order_reference_type WHERE tenant_id=scope AND lower(name)=lower(item->>'name')) THEN CONTINUE; END IF;
        target:=gen_random_uuid();
        snapshot:=item||jsonb_build_object('id',target,'revision',0,'active',true,'deleted',false);
        body:=jsonb_build_object('input',item,'id',NULL)::text;
        INSERT INTO work_order_reference_type(id,tenant_id,name,revision,snapshot) VALUES(target,scope,item->>'name',0,snapshot);
        INSERT INTO work_order_reference_command(id,tenant_id,resource_id,resource_kind,action,operation_key,revision,actor_id,authority_epoch,
            cutover_epoch,canonical_payload,payload_hash,snapshot,notes,permission_code,original_status)
            VALUES(gen_random_uuid(),scope,target,'TYPE','SEED',target::text,0,NULL,0,0,body,encode(sha256(convert_to(body,'UTF8')),'hex'),snapshot,'Jenis bawaan','SYSTEM',201);
    END LOOP;
END $$;
