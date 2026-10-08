CREATE TABLE work_order_reference_photo (
    evidence_id uuid PRIMARY KEY REFERENCES wo_evidence(id), tenant_id uuid NOT NULL REFERENCES tenant(id),
    work_order_id uuid NOT NULL, assignment_generation bigint NOT NULL CHECK(assignment_generation>=0),
    work_order_revision bigint NOT NULL CHECK(work_order_revision>0), slot varchar(100) NOT NULL, uploaded_by uuid NOT NULL,
    object_key varchar(300) NOT NULL, sha256 varchar(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes bigint NOT NULL CHECK(size_bytes BETWEEN 1 AND 5242880),
    content_type varchar(100) NOT NULL CHECK(content_type IN ('image/png','image/jpeg','image/gif','image/webp')),
    received_at timestamptz NOT NULL, UNIQUE(tenant_id,work_order_id,work_order_revision), UNIQUE(tenant_id,object_key),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order_reference(tenant_id,id),
    FOREIGN KEY(tenant_id,uploaded_by) REFERENCES app_user(tenant_id,id)
);
CREATE INDEX work_order_reference_photo_slot ON work_order_reference_photo(tenant_id,work_order_id,assignment_generation,slot,work_order_revision DESC);
ALTER TABLE work_order_reference_photo ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_reference_photo FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_order_reference_photo
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT ON work_order_reference_photo TO warehouse_app;
    END IF;
END $$;
CREATE TRIGGER work_order_reference_photo_immutable BEFORE UPDATE OR DELETE ON work_order_reference_photo
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

ALTER TABLE work_order_reference_command DROP CONSTRAINT work_order_reference_command_action_check;
ALTER TABLE work_order_reference_command ADD CONSTRAINT work_order_reference_command_action_check
    CHECK(action IN ('SEED','TYPE_SAVE','TYPE_DELETE','CREATE','UPDATE','ASSIGN','PROGRESS','PHOTO'));
DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_reference_wo_authority()'::regprocedure);
    anchor:='WHEN ''PROGRESS'' THEN ''workorder.order.field'' END';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference photo authority anchor changed'; END IF;
    EXECUTE replace(definition,anchor,'WHEN ''PROGRESS'' THEN ''workorder.order.field'' WHEN ''PHOTO'' THEN ''workorder.order.field'' END');
    definition:=pg_get_functiondef('warehouse_assert_reference_wo(uuid,uuid,text)'::regprocedure);
    anchor:='WHEN ''PROGRESS'' THEN ARRAY[''revision'',''state'',''blockedReason'',''lastActivityAt''] ELSE';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'reference photo projection anchor changed'; END IF;
    EXECUTE replace(definition,anchor,'WHEN ''PROGRESS'' THEN ARRAY[''revision'',''state'',''blockedReason'',''lastActivityAt''] WHEN ''PHOTO'' THEN ARRAY[''revision'',''lastActivityAt''] ELSE');
END $$;

CREATE FUNCTION warehouse_assert_reference_photo(scope uuid,target uuid,rev bigint) RETURNS void LANGUAGE plpgsql AS $$
DECLARE photo work_order_reference_photo; command work_order_reference_command; input jsonb; original wo_evidence;
    object_row evidence_object_registry;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT photo FROM work_order_reference_photo WHERE tenant_id=scope AND work_order_id=target AND work_order_revision=rev;
    SELECT * INTO STRICT command FROM work_order_reference_command WHERE tenant_id=scope AND resource_kind='WO' AND resource_id=target AND revision=rev;
    SELECT * INTO STRICT original FROM wo_evidence WHERE tenant_id=scope AND id=photo.evidence_id;
    SELECT * INTO STRICT object_row FROM evidence_object_registry WHERE tenant_id=scope AND revision_id=photo.evidence_id;
    input:=command.canonical_payload::jsonb->'input';
    IF command.action<>'PHOTO' OR command.actor_id IS DISTINCT FROM photo.uploaded_by OR
        command.snapshot->>'technicianId' IS DISTINCT FROM photo.uploaded_by::text OR
        command.snapshot->>'assignmentGeneration' IS DISTINCT FROM photo.assignment_generation::text OR
        command.snapshot->>'state' NOT IN ('PENDING','BLOCKED') OR
        NOT (command.snapshot->'type'->'photoSlots' ? photo.slot) OR
        input IS DISTINCT FROM jsonb_build_object('expectedRevision',rev-1,'slot',photo.slot,'contentType',photo.content_type,'sizeBytes',photo.size_bytes,'sha256',photo.sha256) OR
        (command.snapshot->>'lastActivityAt')::timestamptz IS DISTINCT FROM photo.received_at OR
        command.notes IS DISTINCT FROM 'Foto: '||photo.slot OR command.original_status<>201 OR
        original.work_order_id IS DISTINCT FROM target OR original.kind<>'OTHER' OR original.caption IS DISTINCT FROM photo.slot OR
        original.storage_key IS DISTINCT FROM photo.object_key OR original.sha256 IS DISTINCT FROM photo.sha256 OR
        original.size_bytes IS DISTINCT FROM photo.size_bytes OR original.expected_size_bytes IS DISTINCT FROM photo.size_bytes OR
        original.content_type IS DISTINCT FROM photo.content_type OR original.expected_content_type IS DISTINCT FROM photo.content_type OR
        original.uploaded_by IS DISTINCT FROM photo.uploaded_by OR original.receipt_at IS DISTINCT FROM photo.received_at OR
        object_row.object_key IS DISTINCT FROM photo.object_key OR object_row.expected_sha256 IS DISTINCT FROM photo.sha256 OR
        object_row.expected_size_bytes IS DISTINCT FROM photo.size_bytes OR object_row.expected_content_type IS DISTINCT FROM photo.content_type OR
        object_row.actor_id IS DISTINCT FROM photo.uploaded_by THEN
        RAISE EXCEPTION 'work order photo differs from immutable upload command' USING ERRCODE='23514'; END IF;
    IF command.created_xid=pg_current_xact_id() AND
        (original.revision_state<>'COMMITTED' OR original.purge_state<>'ACTIVE' OR object_row.state<>'COMMITTED' OR object_row.purge_state<>'ACTIVE' OR
        NOT EXISTS(SELECT FROM app_user actor WHERE actor.tenant_id=scope AND actor.id=photo.uploaded_by AND actor.status='ACTIVE' AND NOT actor.platform_admin AND
            NOT EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=scope AND user_id=actor.id) AND
            EXISTS(SELECT FROM user_role WHERE user_id=actor.id) AND NOT EXISTS(SELECT FROM user_role assignment JOIN role ON role.id=assignment.role_id
                WHERE assignment.user_id=actor.id AND coalesce(role.default_key,'') NOT IN ('TECHNICIAN_NE','TECHNICIAN_FO')))) THEN
        RAISE EXCEPTION 'work order photo requires committed object and current technician' USING ERRCODE='23514'; END IF;
END $$;
CREATE FUNCTION warehouse_reference_photo_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE scope uuid:=NEW.tenant_id; target uuid; rev bigint;
BEGIN
    IF TG_TABLE_NAME='work_order_reference_command' THEN
        IF NEW.action<>'PHOTO' THEN RETURN NULL; END IF;
        target:=NEW.resource_id; rev:=NEW.revision;
    ELSIF TG_TABLE_NAME='work_order_reference_photo' THEN
        target:=NEW.work_order_id; rev:=NEW.work_order_revision;
    ELSE
        target:=NEW.work_order_id;
        IF NOT EXISTS(SELECT FROM work_order_reference WHERE tenant_id=scope AND id=target) THEN RETURN NULL; END IF;
        SELECT work_order_revision INTO STRICT rev FROM work_order_reference_photo WHERE tenant_id=scope AND evidence_id=NEW.id;
    END IF;
    PERFORM warehouse_assert_reference_photo(scope,target,rev);
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER work_order_reference_photo_binding AFTER INSERT ON work_order_reference_photo
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_photo_binding();
CREATE CONSTRAINT TRIGGER work_order_reference_photo_command_binding AFTER INSERT ON work_order_reference_command
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_photo_binding();
CREATE CONSTRAINT TRIGGER work_order_reference_evidence_binding AFTER INSERT ON wo_evidence
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_photo_binding();

CREATE FUNCTION warehouse_reference_photo_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE photo work_order_reference_photo;
BEGIN
    SELECT * INTO photo FROM work_order_reference_photo WHERE tenant_id=OLD.tenant_id AND evidence_id=
        (CASE WHEN TG_TABLE_NAME='wo_evidence' THEN OLD.id ELSE (to_jsonb(OLD)->>'revision_id')::uuid END);
    IF NOT FOUND THEN RETURN NEW; END IF;
    IF TG_TABLE_NAME='wo_evidence' AND
        (to_jsonb(NEW)-ARRAY['revision_state','purge_state','purge_claim_id','purge_claimed_at','purged_at','updated_at']) IS DISTINCT FROM
        (to_jsonb(OLD)-ARRAY['revision_state','purge_state','purge_claim_id','purge_claimed_at','purged_at','updated_at']) OR
        TG_TABLE_NAME='evidence_object_registry' AND
        (to_jsonb(NEW)-ARRAY['state','etag','purge_state','purge_claim_id','purge_claimed_at','purged_at','updated_at']) IS DISTINCT FROM
        (to_jsonb(OLD)-ARRAY['state','etag','purge_state','purge_claim_id','purge_claimed_at','purged_at','updated_at']) THEN
        RAISE EXCEPTION 'reference work order photo identity is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER work_order_reference_photo_source_identity BEFORE UPDATE ON wo_evidence
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_photo_identity();
CREATE TRIGGER work_order_reference_photo_registry_identity BEFORE UPDATE ON evidence_object_registry
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_photo_identity();
