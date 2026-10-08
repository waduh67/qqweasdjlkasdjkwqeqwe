CREATE OR REPLACE FUNCTION warehouse_assert_reference_wo(scope uuid,target uuid,kind text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE current jsonb; prior jsonb; command work_order_reference_command; input jsonb; allowed text[]; target_area uuid;
BEGIN
    PERFORM warehouse_assert_reference_wo_v196(scope,target,kind);
    SELECT * INTO STRICT command FROM work_order_reference_command
        WHERE tenant_id=scope AND resource_kind=kind AND resource_id=target ORDER BY revision DESC LIMIT 1;
    current:=command.snapshot;
    input:=command.canonical_payload::jsonb->'input';
    IF command.revision>0 THEN
        SELECT snapshot INTO STRICT prior FROM work_order_reference_command
            WHERE tenant_id=scope AND resource_kind=kind AND resource_id=target AND revision=command.revision-1;
    END IF;
    IF kind='TYPE' THEN
        IF jsonb_typeof(current->'active') IS DISTINCT FROM 'boolean' OR
            jsonb_typeof(current->'deleted') IS DISTINCT FROM 'boolean' OR
            jsonb_typeof(current->'materialRequired') IS DISTINCT FROM 'boolean' OR
            EXISTS(SELECT FROM jsonb_array_elements(current->'photoSlots') slot WHERE jsonb_typeof(slot)<>'string' OR
                btrim(slot#>>'{}')='' OR length(slot#>>'{}')>100 OR btrim(slot#>>'{}') IS DISTINCT FROM slot#>>'{}' OR slot#>>'{}' ~ '[<>]') OR
            (SELECT count(DISTINCT lower(slot#>>'{}')) FROM jsonb_array_elements(current->'photoSlots') slot)
                <>jsonb_array_length(current->'photoSlots') OR current->>'name' ~ '[<>]' OR btrim(current->>'name') IS DISTINCT FROM current->>'name' THEN
            RAISE EXCEPTION 'work order type snapshot invalid' USING ERRCODE='23514'; END IF;
        IF command.created_xid=pg_current_xact_id() AND command.action='TYPE_SAVE' AND
            ((current-'id'-'revision'-'deleted') IS DISTINCT FROM (input-'expectedRevision') OR current->'deleted'<>'false'::jsonb OR
            prior->'deleted'='true'::jsonb) THEN
            RAISE EXCEPTION 'type differs from input' USING ERRCODE='23514'; END IF;
        IF command.action='TYPE_DELETE' AND ((current-'revision'-'active'-'deleted') IS DISTINCT FROM (prior-'revision'-'active'-'deleted') OR
            current->'active'<>'false'::jsonb OR current->'deleted'<>'true'::jsonb OR
            EXISTS(SELECT FROM work_order_reference WHERE tenant_id=scope AND type_id=target)) THEN
            RAISE EXCEPTION 'used type cannot be deleted' USING ERRCODE='23514'; END IF;
        RETURN;
    END IF;
    IF command.created_xid<>pg_current_xact_id() THEN RETURN; END IF;
    target_area:=(current->>'areaId')::uuid;
    IF NOT EXISTS(SELECT FROM area WHERE tenant_id=scope AND id=target_area) OR
        NOT EXISTS(SELECT FROM app_user actor WHERE actor.tenant_id=scope AND actor.id=command.actor_id AND
            (actor.platform_admin OR EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=scope AND user_id=actor.id) OR
            (EXISTS(SELECT FROM user_area WHERE user_id=actor.id AND area_id=target_area) AND
            (prior IS NULL OR EXISTS(SELECT FROM user_area WHERE user_id=actor.id AND area_id=(prior->>'areaId')::uuid))))) THEN
        RAISE EXCEPTION 'work order command requires current area scope' USING ERRCODE='23514'; END IF;
    IF command.action IN ('CREATE','UPDATE') AND
        (current->>'title' IS DISTINCT FROM btrim(input->>'title') OR current->'description' IS DISTINCT FROM input->'description' OR
        current->'priority' IS DISTINCT FROM input->'priority' OR current->'customerId' IS DISTINCT FROM input->'customerId' OR
        current->'areaId' IS DISTINCT FROM input->'areaId' OR
        (current->>'scheduledAt')::timestamptz IS DISTINCT FROM (input->>'scheduledAt')::timestamptz OR
        current->>'title' ~ '[<>]' OR current->>'description' ~ '[<>]') THEN
        RAISE EXCEPTION 'work order details differ from input' USING ERRCODE='23514'; END IF;
    IF command.action='CREATE' AND
        (current->'type'->'id' IS DISTINCT FROM input->'typeId' OR current->'blockedReason'<>'null'::jsonb OR
        current->'lastActivityAt' IS DISTINCT FROM current->'createdAt' OR
        NOT EXISTS(SELECT FROM work_order WHERE tenant_id=scope AND id=target AND created_by=command.actor_id AND
            subscription_id IS NOT DISTINCT FROM (input->>'subscriptionId')::uuid AND order_id IS NOT DISTINCT FROM (input->>'orderId')::uuid)) THEN
        RAISE EXCEPTION 'new work order source differs from input' USING ERRCODE='23514'; END IF;
    IF command.action='ASSIGN' AND (current->'technicianId' IS NOT DISTINCT FROM prior->'technicianId' OR
        current->>'state'<>'PENDING' OR current->'blockedReason'<>'null'::jsonb) THEN
        RAISE EXCEPTION 'reassignment must reset progress' USING ERRCODE='23514'; END IF;
    IF command.action='PROGRESS' AND (current->'blockedReason' IS DISTINCT FROM
        (CASE WHEN input->>'state'='BLOCKED' THEN input->'notes' ELSE 'null'::jsonb END) OR
        command.notes IS DISTINCT FROM input->>'notes') THEN
        RAISE EXCEPTION 'work order progress differs from input' USING ERRCODE='23514'; END IF;
    IF prior IS NOT NULL THEN
        allowed:=CASE command.action WHEN 'UPDATE' THEN ARRAY['revision','title','description','priority','customerId','areaId','scheduledAt']
            WHEN 'ASSIGN' THEN ARRAY['revision','technicianId','technicianName','assignmentGeneration','state','blockedReason','lastActivityAt']
            WHEN 'PROGRESS' THEN ARRAY['revision','state','blockedReason','lastActivityAt'] ELSE ARRAY['revision'] END;
        IF (current-allowed) IS DISTINCT FROM (prior-allowed) THEN
            RAISE EXCEPTION 'work order command changed unrelated fields' USING ERRCODE='23514'; END IF;
    END IF;
END $$;
