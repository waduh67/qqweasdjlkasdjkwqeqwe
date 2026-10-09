CREATE TABLE work_order_reference_intake (
    work_order_id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id),
    source text NOT NULL CHECK(source IN ('PSB','HELPDESK','PREVENTIVE')), source_id uuid NOT NULL,
    actor_id uuid, authority_epoch bigint NOT NULL CHECK(authority_epoch>=0), cutover_epoch bigint NOT NULL CHECK(cutover_epoch>=1),
    snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'), base_snapshot jsonb NOT NULL CHECK(jsonb_typeof(base_snapshot)='object'),
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,work_order_id), FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    CHECK((source='PREVENTIVE')=(actor_id IS NULL))
);
CREATE UNIQUE INDEX reference_intake_business_source ON work_order_reference_intake(tenant_id,source,source_id) WHERE source<>'PREVENTIVE';
ALTER TABLE work_order_reference_intake ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order_reference_intake FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON work_order_reference_intake
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        REVOKE UPDATE,DELETE,TRUNCATE ON work_order_reference_intake FROM warehouse_app;
        GRANT SELECT,INSERT ON work_order_reference_intake TO warehouse_app;
    END IF;
END $$;
CREATE TRIGGER reference_intake_immutable BEFORE UPDATE OR DELETE ON work_order_reference_intake
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE FUNCTION warehouse_reference_intake_authority() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE base work_order; area_scope uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT base FROM work_order WHERE tenant_id=NEW.tenant_id AND id=NEW.work_order_id;
    area_scope:=base.area_id;
    IF NEW.created_xid IS DISTINCT FROM pg_current_xact_id() OR
        NOT EXISTS(SELECT FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id AND state='ENFORCED'
            AND workflow_mode='REFERENCE' AND epoch=NEW.cutover_epoch FOR SHARE) OR
        NEW.base_snapshot IS DISTINCT FROM to_jsonb(base) OR base.status<>'DRAFT' OR
        base.created_by IS DISTINCT FROM NEW.actor_id OR base.customer_id IS NULL OR base.incident_id IS NOT NULL OR base.order_id IS NOT NULL OR
        base.completed_at IS NOT NULL OR base.started_at IS NOT NULL OR base.assigned_at IS NOT NULL OR base.approval_status IS NOT NULL OR
        EXISTS(SELECT FROM work_order_assignee WHERE tenant_id=NEW.tenant_id AND work_order_id=base.id) OR
        NEW.snapshot IS DISTINCT FROM jsonb_build_object('id',base.id,'code',base.code,'source',NEW.source,'sourceId',NEW.source_id,
            'type',base.type,'title',base.title,'description',coalesce(base.description,''),'priority',base.priority,
            'customerId',base.customer_id,'areaId',base.area_id,'scheduledAt',base.scheduled_at,'createdAt',base.created_at,'dispatched',false) THEN
        RAISE EXCEPTION 'intake requires an exact unassigned source work order' USING ERRCODE='23514'; END IF;
    IF NEW.source<>'PREVENTIVE' AND (
        NOT EXISTS(SELECT FROM iam_authorization_epoch WHERE tenant_id=NEW.tenant_id AND epoch=NEW.authority_epoch FOR SHARE) OR
        NOT EXISTS(SELECT FROM app_user actor WHERE actor.tenant_id=NEW.tenant_id AND actor.id=NEW.actor_id AND actor.status='ACTIVE' AND
            (actor.platform_admin OR EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=NEW.tenant_id AND user_id=actor.id) OR
                (EXISTS(SELECT FROM user_area WHERE user_id=actor.id AND area_id=area_scope) AND
                EXISTS(SELECT FROM user_role assignment JOIN role ON role.id=assignment.role_id AND role.tenant_id=NEW.tenant_id
                    JOIN role_permission grant_row ON grant_row.role_id=role.id JOIN permission ON permission.id=grant_row.permission_id
                    WHERE assignment.user_id=actor.id AND permission.active AND NOT permission.platform_only AND permission.code='workorder.order.create'))))) THEN
        RAISE EXCEPTION 'intake requires current operator authority and area' USING ERRCODE='23514'; END IF;
    IF (NEW.source='PSB' AND (base.type<>'PSB' OR base.subscription_id IS DISTINCT FROM NEW.source_id OR
        NOT EXISTS(SELECT FROM subscription WHERE tenant_id=NEW.tenant_id AND id=NEW.source_id AND customer_id=base.customer_id))) OR
        (NEW.source='HELPDESK' AND (base.type<>'REPAIR' OR base.subscription_id IS NOT NULL OR
        NOT EXISTS(SELECT FROM helpdesk_ticket WHERE tenant_id=NEW.tenant_id AND id=NEW.source_id AND customer_id=base.customer_id))) OR
        (NEW.source='PREVENTIVE' AND (base.type<>'PREVENTIVE' OR base.subscription_id IS NOT NULL OR NEW.authority_epoch<>0 OR
        NOT EXISTS(SELECT FROM onu WHERE tenant_id=NEW.tenant_id AND id=NEW.source_id AND customer_id=base.customer_id))) THEN
        RAISE EXCEPTION 'intake source does not belong to work order customer' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER reference_intake_authority BEFORE INSERT ON work_order_reference_intake
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_intake_authority();

CREATE FUNCTION warehouse_assert_reference_intake(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE intake work_order_reference_intake; base work_order;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT intake FROM work_order_reference_intake WHERE tenant_id=scope AND work_order_id=target;
    SELECT * INTO STRICT base FROM work_order WHERE tenant_id=scope AND id=target;
    IF base.customer_id::text IS DISTINCT FROM intake.snapshot->>'customerId' OR
        (to_jsonb(base)-ARRAY['title','description','priority','area_id','scheduled_at','assigned_at','started_at','completed_at',
            'resolution_note','cancel_reason','status','completed_by','proof_of_work_hash','updated_at','warehouse_revision']) IS DISTINCT FROM
        (intake.base_snapshot-ARRAY['title','description','priority','area_id','scheduled_at','assigned_at','started_at','completed_at',
            'resolution_note','cancel_reason','status','completed_by','proof_of_work_hash','updated_at','warehouse_revision']) THEN
        RAISE EXCEPTION 'intake source identity is immutable' USING ERRCODE='23514'; END IF;
    IF EXISTS(SELECT FROM work_order_reference WHERE tenant_id=scope AND id=target) THEN
        PERFORM warehouse_assert_reference_wo(scope,target,'WO');
    ELSIF to_jsonb(base) IS DISTINCT FROM intake.base_snapshot OR
        EXISTS(SELECT FROM work_order_assignee WHERE tenant_id=scope AND work_order_id=target) THEN
        RAISE EXCEPTION 'intake must be dispatched through reference workflow' USING ERRCODE='23514'; END IF;
    IF intake.source='HELPDESK' AND NOT EXISTS(SELECT FROM helpdesk_ticket WHERE tenant_id=scope AND id=intake.source_id
        AND customer_id=base.customer_id AND work_order_id=target AND work_order_code=base.code) THEN
        RAISE EXCEPTION 'helpdesk intake must retain its ticket link' USING ERRCODE='23514'; END IF;
END $$;
CREATE FUNCTION warehouse_reference_intake_binding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    PERFORM warehouse_assert_reference_intake(NEW.tenant_id,NEW.work_order_id);
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER reference_intake_binding AFTER INSERT ON work_order_reference_intake
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_intake_binding();

ALTER TABLE work_order_reference_command DROP CONSTRAINT work_order_reference_command_action_check;
ALTER TABLE work_order_reference_command ADD CONSTRAINT work_order_reference_command_action_check
    CHECK(action IN ('SEED','TYPE_SAVE','TYPE_DELETE','CREATE','DISPATCH','UPDATE','ASSIGN','PROGRESS','PHOTO','COMPLETE'));
DO $$ DECLARE definition text; anchor text; BEGIN
    definition:=pg_get_functiondef('warehouse_reference_wo_authority()'::regprocedure);
    anchor:='WHEN ''CREATE'' THEN ''workorder.order.create''';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'dispatch authority anchor changed'; END IF;
    EXECUTE replace(definition,anchor,anchor||' WHEN ''DISPATCH'' THEN ''workorder.order.assign''');
    definition:=pg_get_functiondef('warehouse_assert_reference_wo_v196(uuid,uuid,text)'::regprocedure);
    anchor:='command.action IN (''CREATE'',''ASSIGN'')';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'dispatch technician anchor changed'; END IF;
    definition:=replace(definition,anchor,'command.action IN (''CREATE'',''DISPATCH'',''ASSIGN'')');
    anchor:='command.action IN (''CREATE'',''ASSIGN'',''PROGRESS'')';
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'dispatch pure technician anchor changed'; END IF;
    EXECUTE replace(definition,anchor,'command.action IN (''CREATE'',''DISPATCH'',''ASSIGN'',''PROGRESS'')');
END $$;
ALTER FUNCTION warehouse_assert_reference_wo(uuid,uuid,text) RENAME TO warehouse_assert_reference_wo_v208;
CREATE FUNCTION warehouse_assert_reference_wo(scope uuid,target uuid,kind text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE command work_order_reference_command; intake work_order_reference_intake; base work_order; input jsonb;
BEGIN
    PERFORM warehouse_assert_reference_wo_v208(scope,target,kind);
    IF kind<>'WO' THEN RETURN; END IF;
    SELECT * INTO STRICT command FROM work_order_reference_command WHERE tenant_id=scope AND resource_kind=kind AND resource_id=target ORDER BY revision DESC LIMIT 1;
    IF command.action<>'DISPATCH' THEN RETURN; END IF;
    SELECT * INTO STRICT intake FROM work_order_reference_intake WHERE tenant_id=scope AND work_order_id=target;
    SELECT * INTO STRICT base FROM work_order WHERE tenant_id=scope AND id=target;
    input:=command.canonical_payload::jsonb->'input';
    IF command.revision<>0 OR command.original_status<>200 OR
        command.snapshot->'type'->'id' IS DISTINCT FROM input->'typeId' OR
        command.snapshot->'areaId' IS DISTINCT FROM input->'areaId' OR
        (command.snapshot->>'scheduledAt')::timestamptz IS DISTINCT FROM (input->>'scheduledAt')::timestamptz OR
        (command.snapshot->>'createdAt')::timestamptz IS DISTINCT FROM (intake.snapshot->>'createdAt')::timestamptz OR
        (command.snapshot-ARRAY['revision','type','technicianId','technicianName','state','assignmentGeneration','lastActivityAt','blockedReason','areaId','scheduledAt','createdAt']) IS DISTINCT FROM
        (intake.snapshot-ARRAY['source','sourceId','dispatched','type','areaId','scheduledAt','createdAt']) OR
        command.snapshot->'blockedReason'<>'null'::jsonb OR command.snapshot->>'state'<>'PENDING' OR
        base.created_by IS DISTINCT FROM intake.actor_id OR
        (command.created_xid=pg_current_xact_id() AND NOT EXISTS(SELECT FROM app_user actor WHERE actor.tenant_id=scope AND actor.id=command.actor_id AND
            (actor.platform_admin OR EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=scope AND user_id=actor.id) OR
            EXISTS(SELECT FROM user_area WHERE user_id=actor.id AND area_id=(intake.snapshot->>'areaId')::uuid)))) THEN
        RAISE EXCEPTION 'dispatch must retain source details and scope' USING ERRCODE='23514'; END IF;
END $$;

CREATE OR REPLACE FUNCTION warehouse_reference_wo_source_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_data jsonb; scope uuid; target uuid;
BEGIN
    row_data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(row_data->>'tenant_id')::uuid;
    target:=(row_data->>CASE WHEN TG_TABLE_NAME='work_order' THEN 'id' ELSE 'work_order_id' END)::uuid;
    IF EXISTS(SELECT FROM work_order_reference_intake WHERE tenant_id=scope AND work_order_id=target) THEN
        PERFORM warehouse_assert_reference_intake(scope,target);
    ELSIF EXISTS(SELECT FROM work_order_reference WHERE tenant_id=scope AND id=target) THEN
        PERFORM warehouse_assert_reference_wo(scope,target,'WO');
    ELSIF TG_TABLE_NAME='work_order' AND TG_OP='INSERT' THEN
        PERFORM warehouse_assert_deferred_scope(scope);
        IF EXISTS(SELECT FROM work_order WHERE tenant_id=scope AND id=target) AND
            EXISTS(SELECT FROM inventory_tenant_cutover WHERE tenant_id=scope AND workflow_mode='REFERENCE') THEN
            RAISE EXCEPTION 'reference workflow requires a reference work order' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NULL;
END $$;
