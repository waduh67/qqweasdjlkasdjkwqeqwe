CREATE TABLE inventory_reference_request (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), requester_id uuid NOT NULL,
    warehouse_id uuid, technician_id uuid, revision bigint NOT NULL CHECK(revision>=0),
    snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    UNIQUE(tenant_id,id), CHECK((warehouse_id IS NULL)<>(technician_id IS NULL)),
    FOREIGN KEY(tenant_id,requester_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,technician_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,warehouse_id) REFERENCES inventory_location(tenant_id,id)
);
CREATE TABLE inventory_reference_settings (
    tenant_id uuid PRIMARY KEY REFERENCES tenant(id), revision bigint NOT NULL CHECK(revision>0),
    snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object')
);
CREATE TABLE inventory_reference_command (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), resource_id uuid NOT NULL,
    resource_kind varchar(16) NOT NULL CHECK(resource_kind IN ('REQUEST','SETTINGS')),
    action varchar(16) NOT NULL CHECK(action IN ('SUBMIT','REVIEW','DECIDE','SETTINGS')),
    operation_key varchar(240) NOT NULL CHECK(btrim(operation_key)<>''), actor_id uuid NOT NULL,
    authority_epoch bigint NOT NULL CHECK(authority_epoch>=0), cutover_epoch bigint NOT NULL CHECK(cutover_epoch>=0),
    revision bigint NOT NULL CHECK(revision>=0), canonical_payload text NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK(payload_hash=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex')),
    snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'), notes varchar(1000) NOT NULL,
    permission_code varchar(100) NOT NULL,
    movement_id uuid, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(tenant_id,id),
    UNIQUE(tenant_id,action,operation_key), UNIQUE(tenant_id,resource_kind,resource_id,revision),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,movement_id) REFERENCES inventory_document(tenant_id,id),
    CHECK((resource_kind='SETTINGS')=(action='SETTINGS')),
    CHECK((action IN ('RECEIVE','HANDOVER'))=(movement_id IS NOT NULL))
);
CREATE INDEX warehouse_reference_request_list ON inventory_reference_request(tenant_id,created_at DESC,id);
CREATE INDEX warehouse_reference_request_owner ON inventory_reference_request(tenant_id,technician_id,created_at DESC);
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_reference_request','inventory_reference_settings','inventory_reference_command'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO warehouse_app',relation);
        END IF;
    END LOOP;
END $$;
CREATE TRIGGER warehouse_reference_command_immutable BEFORE UPDATE OR DELETE ON inventory_reference_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE FUNCTION warehouse_reference_command_authority() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    PERFORM 1 FROM inventory_tenant_cutover WHERE tenant_id=NEW.tenant_id AND state='ENFORCED'
        AND workflow_mode='REFERENCE' AND epoch=NEW.cutover_epoch FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'request command requires current reference writer' USING ERRCODE='23514'; END IF;
    PERFORM 1 FROM iam_authorization_epoch WHERE tenant_id=NEW.tenant_id AND epoch=NEW.authority_epoch FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'request command requires current authority epoch' USING ERRCODE='23514'; END IF;
    IF NEW.created_xid IS DISTINCT FROM pg_current_xact_id() OR NOT EXISTS (
        SELECT FROM app_user actor WHERE actor.tenant_id=NEW.tenant_id AND actor.id=NEW.actor_id AND actor.status='ACTIVE'
        AND (EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=NEW.tenant_id AND user_id=NEW.actor_id)
            OR NEW.permission_code<>'OWNER' AND (actor.platform_admin OR EXISTS (
                SELECT FROM user_role assignment JOIN role ON role.id=assignment.role_id AND role.tenant_id=NEW.tenant_id
                JOIN role_permission grant_row ON grant_row.role_id=role.id
                JOIN permission ON permission.id=grant_row.permission_id AND permission.active AND NOT permission.platform_only
                WHERE assignment.user_id=NEW.actor_id AND permission.code=NEW.permission_code)))) THEN
        RAISE EXCEPTION 'request command requires active permitted actor' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_reference_command_authority BEFORE INSERT ON inventory_reference_command
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_command_authority();

CREATE FUNCTION warehouse_reference_projection_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR TG_OP='UPDATE' AND
        (NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.revision IS DISTINCT FROM OLD.revision+1 OR
        TG_TABLE_NAME='inventory_reference_request' AND (to_jsonb(NEW)-ARRAY['revision','snapshot','updated_at'])
            IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['revision','snapshot','updated_at'])) THEN
        RAISE EXCEPTION 'reference projection requires one immutable revision' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_reference_request_revision BEFORE UPDATE OR DELETE ON inventory_reference_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_projection_guard();
CREATE TRIGGER warehouse_reference_settings_revision BEFORE UPDATE OR DELETE ON inventory_reference_settings
    FOR EACH ROW EXECUTE FUNCTION warehouse_reference_projection_guard();

CREATE FUNCTION warehouse_assert_reference_request(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE request inventory_reference_request; command inventory_reference_command; previous jsonb; current jsonb;
    input jsonb; item jsonb; prior_line jsonb; review_line jsonb; position integer; positive integer; policy jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT request FROM inventory_reference_request WHERE tenant_id=scope AND id=target;
    IF request.snapshot->>'id' IS DISTINCT FROM target::text
        OR request.snapshot->>'requesterId' IS DISTINCT FROM request.requester_id::text
        OR request.snapshot->>'warehouseId' IS DISTINCT FROM request.warehouse_id::text
        OR request.snapshot->>'technicianId' IS DISTINCT FROM request.technician_id::text
        OR request.snapshot->>'revision' IS DISTINCT FROM request.revision::text
        OR (request.snapshot->>'createdAt')::timestamptz IS DISTINCT FROM request.created_at
        OR (request.snapshot->>'updatedAt')::timestamptz IS DISTINCT FROM request.updated_at
        OR (SELECT count(*) FROM inventory_reference_command WHERE tenant_id=scope AND resource_kind='REQUEST' AND resource_id=target)<>request.revision+1 THEN
        RAISE EXCEPTION 'reference request projection differs from command history' USING ERRCODE='23514';
    END IF;
    FOR command IN SELECT * FROM inventory_reference_command WHERE tenant_id=scope AND resource_kind='REQUEST' AND resource_id=target ORDER BY revision LOOP
        current:=command.snapshot; input:=command.canonical_payload::jsonb->'input';
        IF command.canonical_payload::jsonb->>'id' IS DISTINCT FROM (CASE WHEN command.action='SUBMIT' THEN NULL ELSE target::text END)
            OR current->>'id' IS DISTINCT FROM target::text OR current->>'revision' IS DISTINCT FROM command.revision::text
            OR current->>'kind' IS NULL OR current->>'kind' NOT IN ('RESTOCK','PROCUREMENT')
            OR jsonb_typeof(current->'requiresManagerApproval') IS DISTINCT FROM 'boolean'
            OR jsonb_typeof(current->'policyRevision') IS DISTINCT FROM 'number'
            OR jsonb_typeof(current->'lines') IS DISTINCT FROM 'array' OR jsonb_array_length(current->'lines') NOT BETWEEN 1 AND 100
            OR (SELECT count(DISTINCT value->>'id') FROM jsonb_array_elements(current->'lines'))<>jsonb_array_length(current->'lines')
            OR command.revision>0 AND input->>'expectedRevision' IS DISTINCT FROM (command.revision-1)::text THEN
            RAISE EXCEPTION 'reference request command revision mismatch' USING ERRCODE='23514';
        END IF;
        IF command.action='SUBMIT' THEN
            IF command.revision<>0 OR current->>'state' IS DISTINCT FROM 'SUBMITTED'
                OR current->>'requesterId' IS DISTINCT FROM command.actor_id::text
                OR current->>'kind' IS DISTINCT FROM input->>'kind' OR current->>'reason' IS DISTINCT FROM input->>'reason'
                OR current->>'warehouseId' IS DISTINCT FROM input->>'warehouseId'
                OR current->>'warehouseId' IS NOT NULL AND current->>'kind'<>'PROCUREMENT'
                OR current->>'reason' IS NULL OR btrim(current->>'reason')='' OR length(current->>'reason')>1000
                OR current->>'technicianId' IS DISTINCT FROM coalesce(input->>'technicianId',CASE WHEN input->>'warehouseId' IS NULL THEN command.actor_id::text END)
                OR command.permission_code NOT IN ('warehouse.request.review','warehouse.request.own')
                OR command.permission_code='warehouse.request.own' AND current->>'technicianId' IS DISTINCT FROM command.actor_id::text
                OR jsonb_array_length(current->'lines') IS DISTINCT FROM jsonb_array_length(input->'lines') THEN
                RAISE EXCEPTION 'reference submission differs from requested destination' USING ERRCODE='23514';
            END IF;
            IF current->>'policyRevision'='0' THEN policy:='{"revision":0,"requireManagerApproval":true,"overdueDays":3}'::jsonb;
            ELSE SELECT snapshot INTO policy FROM inventory_reference_command WHERE tenant_id=scope AND resource_kind='SETTINGS'
                AND revision=(current->>'policyRevision')::bigint; END IF;
            IF policy IS NULL OR current->'requiresManagerApproval' IS DISTINCT FROM policy->'requireManagerApproval'
                OR command.created_xid=pg_current_xact_id() AND (current->>'policyRevision')::bigint<>
                    coalesce((SELECT revision FROM inventory_reference_settings WHERE tenant_id=scope),0) THEN
                RAISE EXCEPTION 'request policy must match submitted settings revision' USING ERRCODE='23514'; END IF;
        ELSE
            IF previous IS NULL OR (current-ARRAY['revision','state','lines','updatedAt'])
                IS DISTINCT FROM (previous-ARRAY['revision','state','lines','updatedAt'])
                OR jsonb_array_length(current->'lines') IS DISTINCT FROM jsonb_array_length(previous->'lines') THEN
                RAISE EXCEPTION 'reference destination and policy snapshot are immutable' USING ERRCODE='23514';
            END IF;
        END IF;
        positive:=0; position:=0;
        FOR item IN SELECT value FROM jsonb_array_elements(current->'lines') LOOP
            prior_line:=previous->'lines'->position;
            IF item->>'id' IS NULL OR item->>'name' IS NULL OR btrim(item->>'name')=''
                OR item->>'baseUnit' IS NULL OR item->>'baseUnit' NOT IN ('EA','MM')
                OR EXISTS(SELECT FROM unnest(ARRAY['requestedBase','approvedBase','receivedBase','fulfilledBase']) field
                    WHERE jsonb_typeof(item->field) IS DISTINCT FROM 'string' OR item->>field !~ '^[0-9]+$'
                        OR (item->>field)::numeric>9223372036854775807)
                OR (item->>'requestedBase')::numeric<=0
                OR (item->>'approvedBase')::numeric NOT BETWEEN 0 AND (item->>'requestedBase')::numeric
                OR (item->>'receivedBase')::numeric NOT BETWEEN 0 AND (item->>'approvedBase')::numeric
                OR (item->>'fulfilledBase')::numeric NOT BETWEEN 0 AND (item->>'approvedBase')::numeric
                OR current->>'kind'='PROCUREMENT' AND (item->>'fulfilledBase')::numeric>(item->>'receivedBase')::numeric
                OR command.created_xid=pg_current_xact_id() AND command.action IN ('SUBMIT','REVIEW') AND item->>'skuId' IS NOT NULL AND NOT EXISTS(SELECT FROM inventory_sku WHERE tenant_id=scope
                    AND id=(item->>'skuId')::uuid AND base_unit=item->>'baseUnit') THEN
                RAISE EXCEPTION 'reference request quantity or catalog unit mismatch' USING ERRCODE='23514';
            END IF;
            IF (item->>'approvedBase')::numeric>0 THEN positive:=positive+1; END IF;
            IF command.action='SUBMIT' THEN
                review_line:=input->'lines'->position;
                IF (item-ARRAY['id','name','approvedBase','receivedBase','fulfilledBase']) IS DISTINCT FROM review_line
                    OR item->>'approvedBase' IS DISTINCT FROM '0' OR item->>'receivedBase' IS DISTINCT FROM '0'
                    OR item->>'fulfilledBase' IS DISTINCT FROM '0'
                    OR (item->>'skuId' IS NULL)=(item->>'proposedName' IS NULL)
                    OR current->>'kind'='RESTOCK' AND item->>'skuId' IS NULL THEN
                    RAISE EXCEPTION 'reference submission line mismatch' USING ERRCODE='23514'; END IF;
            ELSIF command.action='REVIEW' THEN
                SELECT value INTO review_line FROM jsonb_array_elements(input->'lines') WHERE value->>'lineId'=item->>'id';
                IF review_line IS NULL OR (item-ARRAY['approvedBase','skuId','name']) IS DISTINCT FROM (prior_line-ARRAY['approvedBase','skuId','name'])
                    OR item->>'approvedBase' IS DISTINCT FROM review_line->>'approvedBase'
                    OR item->>'skuId' IS DISTINCT FROM coalesce(review_line->>'skuId',prior_line->>'skuId')
                    OR (item->>'approvedBase')::numeric>0 AND item->>'skuId' IS NULL
                    OR prior_line->>'skuId' IS NOT NULL AND item->>'skuId' IS DISTINCT FROM prior_line->>'skuId' THEN
                    RAISE EXCEPTION 'admin review differs from immutable requested quantity' USING ERRCODE='23514'; END IF;
            ELSIF command.action='DECIDE' AND item IS DISTINCT FROM prior_line THEN
                RAISE EXCEPTION 'manager cannot change request quantities' USING ERRCODE='23514';
            END IF;
            position:=position+1;
        END LOOP;
        IF command.action='REVIEW' AND (command.permission_code<>'warehouse.request.review'
            OR previous->>'state' IS DISTINCT FROM 'SUBMITTED' OR positive=0
            OR jsonb_array_length(input->'lines')<>jsonb_array_length(current->'lines')
            OR current->>'state' IS DISTINCT FROM CASE WHEN (current->>'requiresManagerApproval')::boolean THEN 'MANAGER_REVIEW' ELSE 'APPROVED' END)
            OR command.action='DECIDE' AND (previous->>'state' IS NULL OR previous->>'state' NOT IN ('SUBMITTED','MANAGER_REVIEW')
                OR command.permission_code IS DISTINCT FROM CASE WHEN previous->>'state'='SUBMITTED' THEN 'warehouse.request.review' ELSE 'warehouse.request.approve' END
                OR jsonb_typeof(input->'approved') IS DISTINCT FROM 'boolean'
                OR (input->>'approved')::boolean AND previous->>'state'<>'MANAGER_REVIEW'
                OR current->>'state' IS DISTINCT FROM CASE WHEN (input->>'approved')::boolean THEN 'APPROVED' ELSE 'REJECTED' END
                OR NOT (input->>'approved')::boolean AND coalesce(btrim(input->>'reason'),'')='') THEN
            RAISE EXCEPTION 'reference decision state mismatch' USING ERRCODE='23514';
        END IF;
        IF command.created_xid=pg_current_xact_id() AND NOT EXISTS(SELECT FROM inventory_tenant_cutover
            WHERE tenant_id=scope AND workflow_mode='REFERENCE' AND state='ENFORCED' AND epoch=command.cutover_epoch) THEN
            RAISE EXCEPTION 'request command requires current reference writer' USING ERRCODE='23514'; END IF;
        previous:=current;
    END LOOP;
    IF previous IS DISTINCT FROM request.snapshot THEN
        RAISE EXCEPTION 'reference projection must equal latest command' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_reference_request_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; latest inventory_reference_command; setting inventory_reference_settings; data jsonb:=to_jsonb(NEW); BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_reference_request' OR TG_TABLE_NAME='inventory_reference_command' AND data->>'resource_kind'='REQUEST' THEN
        target:=CASE WHEN TG_TABLE_NAME='inventory_reference_request' THEN (data->>'id')::uuid ELSE (data->>'resource_id')::uuid END;
        PERFORM warehouse_assert_reference_request(NEW.tenant_id,target);
    ELSE
        SELECT * INTO STRICT setting FROM inventory_reference_settings WHERE tenant_id=NEW.tenant_id;
        SELECT * INTO STRICT latest FROM inventory_reference_command WHERE tenant_id=NEW.tenant_id AND resource_kind='SETTINGS' ORDER BY revision DESC LIMIT 1;
        IF latest.snapshot IS DISTINCT FROM setting.snapshot OR latest.revision IS DISTINCT FROM setting.revision
            OR latest.snapshot->>'revision' IS DISTINCT FROM latest.revision::text
            OR latest.permission_code<>'OWNER' OR latest.resource_id IS DISTINCT FROM NEW.tenant_id
            OR jsonb_typeof(latest.snapshot->'requireManagerApproval') IS DISTINCT FROM 'boolean'
            OR jsonb_typeof(latest.snapshot->'overdueDays') IS DISTINCT FROM 'number'
            OR latest.snapshot->>'requireManagerApproval' IS DISTINCT FROM latest.canonical_payload::jsonb#>>'{input,requireManagerApproval}'
            OR latest.snapshot->>'overdueDays' IS DISTINCT FROM latest.canonical_payload::jsonb#>>'{input,overdueDays}'
            OR (latest.snapshot->>'overdueDays')::integer NOT BETWEEN 1 AND 365
            OR latest.canonical_payload::jsonb#>>'{input,expectedRevision}' IS DISTINCT FROM (latest.revision-1)::text
            OR (SELECT count(*) FROM inventory_reference_command WHERE tenant_id=NEW.tenant_id AND resource_kind='SETTINGS')<>setting.revision THEN
            RAISE EXCEPTION 'operational settings require owner command and exact snapshot' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NULL;
END $$;
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_reference_request','inventory_reference_settings','inventory_reference_command'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_reference_request_final AFTER INSERT OR UPDATE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_reference_request_final_guard()',relation);
    END LOOP;
END $$;
