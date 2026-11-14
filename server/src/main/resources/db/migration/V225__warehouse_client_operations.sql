CREATE TABLE inventory_client_document (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), kind varchar(16) NOT NULL CHECK(kind IN ('PURCHASE','LOAN','WARRANTY')),
    warehouse_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>=0), snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
    created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, UNIQUE(tenant_id,id),
    FOREIGN KEY(tenant_id,warehouse_id) REFERENCES inventory_location(tenant_id,id)
);
CREATE TABLE inventory_client_command (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, resource_id uuid NOT NULL, resource_kind varchar(16) NOT NULL,
    action varchar(24) NOT NULL, operation_key varchar(240) NOT NULL, actor_id uuid NOT NULL,
    authority_epoch bigint NOT NULL, cutover_epoch bigint NOT NULL, revision bigint NOT NULL,
    canonical_payload text NOT NULL, payload_hash varchar(64) NOT NULL,
    snapshot jsonb NOT NULL, notes varchar(1000) NOT NULL, permission_code varchar(100) NOT NULL,
    movement_ids jsonb NOT NULL DEFAULT '[]', original_status integer NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(), created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,resource_kind,action,operation_key), UNIQUE(tenant_id,resource_id,revision),
    FOREIGN KEY(tenant_id,resource_id) REFERENCES inventory_client_document(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    CHECK(payload_hash=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex')),
    CHECK(jsonb_typeof(movement_ids)='array' AND jsonb_typeof(snapshot)='object')
);
CREATE TABLE inventory_client_movement (
    tenant_id uuid NOT NULL, document_id uuid NOT NULL, resource_id uuid NOT NULL, resource_revision bigint NOT NULL,
    PRIMARY KEY(tenant_id,document_id), CHECK(resource_revision>0), FOREIGN KEY(tenant_id,document_id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,resource_id) REFERENCES inventory_client_document(tenant_id,id)
);
CREATE INDEX warehouse_client_list ON inventory_client_document(tenant_id,kind,warehouse_id,created_at DESC);
CREATE INDEX warehouse_purchase_request ON inventory_client_document(tenant_id,(snapshot->>'requestId')) WHERE kind='PURCHASE';
DO $$ DECLARE relation text; BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_client_document','inventory_client_command','inventory_client_movement'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',relation);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE ON %I TO warehouse_app',relation);
        END IF;
    END LOOP;
END $$;
CREATE TRIGGER warehouse_client_command_immutable BEFORE UPDATE OR DELETE ON inventory_client_command FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE TRIGGER warehouse_client_command_authority BEFORE INSERT ON inventory_client_command FOR EACH ROW EXECUTE FUNCTION warehouse_reference_command_authority();
CREATE TRIGGER warehouse_client_document_revision BEFORE UPDATE OR DELETE ON inventory_client_document FOR EACH ROW EXECUTE FUNCTION warehouse_reference_projection_guard();
CREATE TRIGGER warehouse_client_movement_immutable BEFORE UPDATE OR DELETE ON inventory_client_movement FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE FUNCTION warehouse_client_projection_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
    IF TG_OP='DELETE' OR (to_jsonb(NEW)-ARRAY['revision','snapshot','updated_at']) IS DISTINCT FROM
        (to_jsonb(OLD)-ARRAY['revision','snapshot','updated_at']) THEN
        RAISE EXCEPTION 'Client document identity is immutable' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_client_projection_guard BEFORE UPDATE OR DELETE ON inventory_client_document
    FOR EACH ROW EXECUTE FUNCTION warehouse_client_projection_guard();

CREATE FUNCTION warehouse_client_actor_permission(scope uuid,actor uuid,code text) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT EXISTS(SELECT FROM app_user u WHERE u.tenant_id=scope AND u.id=actor AND u.status='ACTIVE' AND
        (u.platform_admin OR EXISTS(SELECT FROM iam_tenant_owner WHERE tenant_id=scope AND user_id=actor) OR EXISTS(
            SELECT FROM user_role ur JOIN role r ON r.id=ur.role_id AND r.tenant_id=scope
            JOIN role_permission rp ON rp.role_id=r.id JOIN permission p ON p.id=rp.permission_id AND p.active
            WHERE ur.user_id=actor AND p.code=code AND NOT p.platform_only)))
$$;

CREATE FUNCTION warehouse_assert_client_history(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE doc inventory_client_document; c inventory_client_command; cur jsonb; prev jsonb; input jsonb;
    item jsonb; old_item jsonb; selected jsonb; old_stock jsonb; return_item jsonb; stock_index integer; expected_state text; changed integer; total numeric; done numeric;
BEGIN
    SELECT * INTO STRICT doc FROM inventory_client_document WHERE tenant_id=scope AND id=target;
    FOR c IN SELECT * FROM inventory_client_command WHERE tenant_id=scope AND resource_id=target ORDER BY revision LOOP
        cur:=c.snapshot; input:=c.canonical_payload::jsonb->'input'; changed:=0;
        IF c.resource_kind<>doc.kind OR cur->>'id' IS DISTINCT FROM target::text OR cur->>'revision' IS DISTINCT FROM c.revision::text
            OR cur->>'warehouseId' IS DISTINCT FROM doc.warehouse_id::text OR c.original_status<>(CASE WHEN c.revision=0 THEN 201 ELSE 200 END)
            OR c.revision>0 AND (input->>'expectedRevision' IS DISTINCT FROM (c.revision-1)::text
                OR c.canonical_payload::jsonb->>'id' IS DISTINCT FROM target::text)
            OR c.revision=0 AND c.action<>'CREATE' OR c.revision>0 AND c.action='CREATE'
            OR c.revision>0 AND (cur->>'createdAt' IS DISTINCT FROM prev->>'createdAt'
                OR (cur->>'updatedAt')::timestamptz<(prev->>'updatedAt')::timestamptz) THEN
            RAISE EXCEPTION 'Invalid client command identity or revision' USING ERRCODE='23514'; END IF;
        IF doc.kind IN ('PURCHASE','LOAN') THEN
            IF jsonb_typeof(cur->'lines') IS DISTINCT FROM 'array' OR jsonb_array_length(cur->'lines') NOT BETWEEN 1 AND 100
                OR (SELECT count(DISTINCT value->>'id') FROM jsonb_array_elements(cur->'lines'))<>jsonb_array_length(cur->'lines')
                OR c.revision>0 AND jsonb_array_length(cur->'lines')<>jsonb_array_length(prev->'lines') THEN
                RAISE EXCEPTION 'Invalid client document lines' USING ERRCODE='23514'; END IF;
            FOR item IN SELECT value FROM jsonb_array_elements(cur->'lines') LOOP
                IF coalesce(item->>'quantityBase','') !~ '^[1-9][0-9]*$' OR (item->>'quantityBase')::numeric>9223372036854775807
                    OR NOT EXISTS(SELECT FROM inventory_sku s WHERE s.tenant_id=scope AND s.id=(item->>'skuId')::uuid
                        AND s.base_unit=item->>'baseUnit') THEN
                    RAISE EXCEPTION 'Invalid client SKU or quantity' USING ERRCODE='23514'; END IF;
                IF c.revision=0 THEN
                    SELECT value INTO STRICT selected FROM jsonb_array_elements(input->'lines') WHERE value->>'skuId'=item->>'skuId'
                        AND value->>'requestLineId' IS NOT DISTINCT FROM item->>'requestLineId';
                    IF selected IS NULL OR selected->>'quantityBase' IS DISTINCT FROM item->>'quantityBase'
                        OR selected->>'baseUnit' IS DISTINCT FROM item->>'baseUnit' THEN
                        RAISE EXCEPTION 'Client line differs from submission' USING ERRCODE='23514'; END IF;
                ELSE
                    SELECT value INTO STRICT old_item FROM jsonb_array_elements(prev->'lines') WHERE value->>'id'=item->>'id';
                    IF (item-ARRAY['receivedBase','approvedBase','stocks']) IS DISTINCT FROM
                        (old_item-ARRAY['receivedBase','approvedBase','stocks']) THEN
                        RAISE EXCEPTION 'Client line identity or order quantity changed' USING ERRCODE='23514'; END IF;
                END IF;
                done:=(item->>(CASE doc.kind WHEN 'PURCHASE' THEN 'receivedBase' ELSE 'approvedBase' END))::numeric;
                IF coalesce(item->>(CASE doc.kind WHEN 'PURCHASE' THEN 'receivedBase' ELSE 'approvedBase' END),'') !~ '^(0|[1-9][0-9]*)$' OR done IS NULL OR done<0 OR done>(item->>'quantityBase')::numeric THEN
                    RAISE EXCEPTION 'Client processed quantity out of bounds' USING ERRCODE='23514'; END IF;
                IF doc.kind='PURCHASE' THEN
                    IF c.revision=0 AND done<>0 THEN RAISE EXCEPTION 'New purchase already received' USING ERRCODE='23514'; END IF;
                    IF c.revision>0 AND item->>'receivedBase' IS DISTINCT FROM old_item->>'receivedBase' THEN
                        changed:=changed+1;
                        IF c.action<>'RECEIVE' OR item->>'id' IS DISTINCT FROM input->>'lineId'
                            OR done<>(old_item->>'receivedBase')::numeric+(input->>'quantityBase')::numeric
                            OR coalesce(input->>'quantityBase','') !~ '^[1-9][0-9]*$' THEN
                            RAISE EXCEPTION 'Purchase receipt differs from command quantity' USING ERRCODE='23514'; END IF;
                    END IF;
                ELSE
                    IF jsonb_typeof(item->'stocks') IS DISTINCT FROM 'array' OR c.revision=0 AND (done<>0 OR item->'stocks'<>'[]'::jsonb)
                        OR c.revision>0 AND c.action<>'REVIEW' AND item->>'approvedBase' IS DISTINCT FROM old_item->>'approvedBase'
                        OR c.revision>0 AND c.action NOT IN ('ISSUE','RETURN') AND item->'stocks' IS DISTINCT FROM old_item->'stocks' THEN
                        RAISE EXCEPTION 'Loan approval or custody changed outside command' USING ERRCODE='23514'; END IF;
                    IF c.action='REVIEW' THEN
                        SELECT value INTO STRICT selected FROM jsonb_array_elements(input->'lines') WHERE value->>'lineId'=item->>'id';
                        IF item->>'approvedBase' IS DISTINCT FROM selected->>'approvedBase' THEN
                            RAISE EXCEPTION 'Loan review differs from approved quantity' USING ERRCODE='23514'; END IF;
                    END IF;
                    total:=0; stock_index:=0;
                    FOR selected IN SELECT value FROM jsonb_array_elements(item->'stocks') LOOP
                        IF coalesce(selected->>'quantityBase','') !~ '^[1-9][0-9]*$' OR coalesce(selected->>'returnedBase','') !~ '^(0|[1-9][0-9]*)$'
                            OR (selected->>'quantityBase')::numeric<=0 OR (selected->>'returnedBase')::numeric<0
                            OR (selected->>'returnedBase')::numeric>(selected->>'quantityBase')::numeric THEN
                            RAISE EXCEPTION 'Loan return quantity out of bounds' USING ERRCODE='23514'; END IF;
                        total:=total+(selected->>'quantityBase')::numeric;
                        IF c.action='RETURN' THEN
                            old_stock:=old_item->'stocks'->stock_index;
                            SELECT l INTO return_item FROM jsonb_array_elements(input->'lines') l WHERE l->>'lineId'=item->>'id' AND l->>'stockIdentityId'=old_stock->>'stockIdentityId';
                            IF old_stock IS NULL OR selected->>'quantityBase' IS DISTINCT FROM old_stock->>'quantityBase'
                                OR selected->>'serial' IS DISTINCT FROM old_stock->>'serial'
                                OR (selected->>'returnedBase')::numeric<>(old_stock->>'returnedBase')::numeric+coalesce((return_item->>'quantityBase')::numeric,0)
                                OR return_item IS NULL AND selected->>'stockIdentityId' IS DISTINCT FROM old_stock->>'stockIdentityId' THEN
                                RAISE EXCEPTION 'Loan stock return differs from selected item' USING ERRCODE='23514'; END IF;
                        ELSIF c.action='ISSUE' AND selected->>'returnedBase'<>'0' THEN
                            RAISE EXCEPTION 'Loan issue already marked returned' USING ERRCODE='23514';
                        END IF;
                        stock_index:=stock_index+1;
                    END LOOP;
                    IF cur->>'state' IN ('ACTIVE','PARTIALLY_RETURNED','RETURNED') AND total<>done THEN
                        RAISE EXCEPTION 'Loan issue must equal approved quantity' USING ERRCODE='23514'; END IF;
                END IF;
            END LOOP;
        END IF;
        IF doc.kind='PURCHASE' THEN
            IF c.permission_code<>'warehouse.request.receive' OR c.action NOT IN ('CREATE','RECEIVE','CANCEL')
                OR c.action IN ('CREATE','CANCEL') AND c.movement_ids<>'[]'::jsonb
                OR c.revision>0 AND (cur-ARRAY['revision','state','lines','updatedAt']) IS DISTINCT FROM (prev-ARRAY['revision','state','lines','updatedAt']) THEN
                RAISE EXCEPTION 'Invalid purchase command or immutable fields' USING ERRCODE='23514'; END IF;
            IF c.action='CREATE' THEN
                expected_state:='ORDERED';
                IF cur->>'supplierId' IS DISTINCT FROM input->>'supplierId' OR cur->>'reference' IS DISTINCT FROM input->>'reference'
                    OR cur->>'warehouseId' IS DISTINCT FROM input->>'warehouseId' OR cur->>'requestId' IS DISTINCT FROM input->>'requestId' THEN
                    RAISE EXCEPTION 'Purchase differs from submission' USING ERRCODE='23514'; END IF;
            ELSE
                IF prev->>'state' NOT IN ('ORDERED','PARTIALLY_RECEIVED') THEN RAISE EXCEPTION 'Purchase is closed' USING ERRCODE='23514'; END IF;
                IF c.action='CANCEL' THEN expected_state:='CANCELLED';
                ELSE
                    IF changed<>1 OR jsonb_array_length(c.movement_ids)<>1 THEN RAISE EXCEPTION 'Receipt requires one changed line and movement' USING ERRCODE='23514'; END IF;
                    expected_state:=CASE WHEN EXISTS(SELECT FROM jsonb_array_elements(cur->'lines') l WHERE l->>'receivedBase'<>l->>'quantityBase')
                        THEN 'PARTIALLY_RECEIVED' ELSE 'RECEIVED' END;
                END IF;
            END IF;
        ELSIF doc.kind='LOAN' THEN
            IF c.revision>0 AND (cur-ARRAY['revision','state','lines','externalLocationId','updatedAt','issueEvidence','issuedBy']) IS DISTINCT FROM
                (prev-ARRAY['revision','state','lines','externalLocationId','updatedAt','issueEvidence','issuedBy'])
                OR c.action NOT IN ('CREATE','REVIEW','DECIDE','ISSUE','RETURN')
                OR c.action IN ('CREATE','REVIEW','DECIDE') AND c.movement_ids<>'[]'::jsonb
                OR c.revision>0 AND c.action<>'ISSUE' AND (cur->'externalLocationId' IS DISTINCT FROM prev->'externalLocationId'
                    OR cur->'issueEvidence' IS DISTINCT FROM prev->'issueEvidence' OR cur->'issuedBy' IS DISTINCT FROM prev->'issuedBy')
                OR c.permission_code IS DISTINCT FROM (CASE WHEN c.action='DECIDE' AND prev->>'state'='MANAGER_REVIEW'
                    THEN 'warehouse.request.approve' ELSE 'warehouse.loan.manage' END) THEN
                RAISE EXCEPTION 'Invalid loan command or immutable fields' USING ERRCODE='23514'; END IF;
            CASE c.action
                WHEN 'CREATE' THEN
                    expected_state:='SUBMITTED';
                    IF cur->>'warehouseId' IS DISTINCT FROM input->>'warehouseId' OR cur->>'borrowerName' IS DISTINCT FROM trim(input->>'borrowerName')
                        OR c.created_xid=pg_current_xact_id() AND NOT (
                            cur->>'policyRevision'='0' AND (cur->>'requiresManagerApproval')::boolean AND NOT EXISTS(SELECT FROM inventory_reference_settings WHERE tenant_id=scope)
                            OR EXISTS(SELECT FROM inventory_reference_settings s WHERE s.tenant_id=scope AND s.revision::text=cur->>'policyRevision'
                                AND s.snapshot->>'requireManagerApproval'=cur->>'requiresManagerApproval')) THEN
                        RAISE EXCEPTION 'Loan submission or approval policy mismatch' USING ERRCODE='23514'; END IF;
                WHEN 'REVIEW' THEN
                    IF prev->>'state'<>'SUBMITTED' OR c.created_xid=pg_current_xact_id() AND NOT warehouse_client_actor_permission(scope,c.actor_id,'warehouse.request.review')
                        OR NOT EXISTS(SELECT FROM jsonb_array_elements(cur->'lines') l WHERE (l->>'approvedBase')::numeric>0) THEN
                        RAISE EXCEPTION 'Loan requires Admin review' USING ERRCODE='23514'; END IF;
                    expected_state:=CASE WHEN (cur->>'requiresManagerApproval')::boolean THEN 'MANAGER_REVIEW' ELSE 'APPROVED' END;
                WHEN 'DECIDE' THEN
                    IF prev->>'state' NOT IN ('SUBMITTED','MANAGER_REVIEW') OR (input->>'approved')::boolean AND prev->>'state'<>'MANAGER_REVIEW' THEN
                        RAISE EXCEPTION 'Loan decision before review' USING ERRCODE='23514'; END IF;
                    expected_state:=CASE WHEN (input->>'approved')::boolean THEN 'APPROVED' ELSE 'REJECTED' END;
                WHEN 'ISSUE' THEN
                    IF prev->>'state'<>'APPROVED' OR jsonb_array_length(c.movement_ids)=0 OR cur->>'externalLocationId' IS NULL
                        OR cur->>'issueEvidence' IS DISTINCT FROM input->>'evidence' THEN RAISE EXCEPTION 'Loan must be approved before issue' USING ERRCODE='23514'; END IF;
                    expected_state:='ACTIVE';
                WHEN 'RETURN' THEN
                    IF prev->>'state' NOT IN ('ACTIVE','PARTIALLY_RETURNED') OR jsonb_array_length(c.movement_ids)<>jsonb_array_length(input->'lines') THEN
                        RAISE EXCEPTION 'Loan return requires active custody and movements' USING ERRCODE='23514'; END IF;
                    IF (SELECT sum((s->>'returnedBase')::numeric) FROM jsonb_array_elements(cur->'lines') l CROSS JOIN LATERAL jsonb_array_elements(l->'stocks') s)
                        IS DISTINCT FROM (SELECT sum((s->>'returnedBase')::numeric) FROM jsonb_array_elements(prev->'lines') l CROSS JOIN LATERAL jsonb_array_elements(l->'stocks') s)
                            +(SELECT sum((l->>'quantityBase')::numeric) FROM jsonb_array_elements(input->'lines') l) THEN
                        RAISE EXCEPTION 'Loan returned quantity differs from command' USING ERRCODE='23514'; END IF;
                    expected_state:=CASE WHEN EXISTS(SELECT FROM jsonb_array_elements(cur->'lines') l CROSS JOIN LATERAL jsonb_array_elements(l->'stocks') s
                        WHERE s->>'returnedBase'<>s->>'quantityBase') THEN 'PARTIALLY_RETURNED' ELSE 'RETURNED' END;
            END CASE;
        ELSE
            IF c.action NOT IN ('CREATE','DISPATCH','RECEIVE','INSPECT') OR c.action='CREATE' AND c.movement_ids<>'[]'::jsonb OR c.permission_code IS DISTINCT FROM
                (CASE WHEN c.action='CREATE' THEN 'warehouse.loan.manage' ELSE 'warehouse.warranty.manage' END)
                OR c.revision>0 AND (cur-ARRAY['revision','state','locationId','stockIdentityId','serial','result','supplierId','supplierName','vendorReference','evidence','sentAt','returnedAt','replacementAssetId','updatedAt']) IS DISTINCT FROM
                (prev-ARRAY['revision','state','locationId','stockIdentityId','serial','result','supplierId','supplierName','vendorReference','evidence','sentAt','returnedAt','replacementAssetId','updatedAt']) THEN
                RAISE EXCEPTION 'Invalid warranty command or immutable fields' USING ERRCODE='23514'; END IF;
            CASE c.action
                WHEN 'CREATE' THEN
                    expected_state:='MASUK';
                    IF c.canonical_payload::jsonb->>'id' IS DISTINCT FROM cur->>'loanId' OR NOT EXISTS(
                        SELECT FROM inventory_client_document loan JOIN inventory_client_movement link ON link.tenant_id=loan.tenant_id AND link.resource_id=loan.id
                        JOIN inventory_movement m ON m.tenant_id=link.tenant_id AND m.document_id=link.document_id
                        JOIN inventory_movement_leg leg ON leg.tenant_id=m.tenant_id AND leg.movement_id=m.id
                        WHERE loan.tenant_id=scope AND loan.id=(cur->>'loanId')::uuid AND loan.kind='LOAN'
                            AND link.document_id=(input->>'returnMovementId')::uuid AND leg.direction='IN'
                            AND leg.stock_identity_id=(cur->>'stockIdentityId')::uuid AND leg.sku_id=(cur->>'skuId')::uuid
                            AND leg.quantity_base=(cur->>'quantityBase')::bigint AND leg.status='QUARANTINE' AND leg.condition='DAMAGED')
                        OR input->>'stockIdentityId' IS DISTINCT FROM cur->>'stockIdentityId' THEN
                        RAISE EXCEPTION 'Warranty requires damaged return from its loan' USING ERRCODE='23514'; END IF;
                WHEN 'DISPATCH' THEN
                    IF prev->>'state'<>'MASUK' OR prev->>'returnedAt' IS NOT NULL OR cur->>'supplierId' IS DISTINCT FROM input->>'supplierId'
                        OR cur->>'vendorReference' IS DISTINCT FROM input->>'vendorReference' OR jsonb_array_length(c.movement_ids)<>1 THEN
                        RAISE EXCEPTION 'Invalid warranty dispatch' USING ERRCODE='23514'; END IF;
                    expected_state:='DIKIRIM';
                WHEN 'RECEIVE' THEN
                    IF prev->>'state'<>'DIKIRIM' OR input->>'result' NOT IN ('REPAIRED','UNREPAIRED','REJECTED','REPLACED')
                        OR cur->>'result' IS DISTINCT FROM input->>'result' OR cur->>'returnedAt' IS NULL
                        OR jsonb_array_length(c.movement_ids)<>(CASE WHEN input->>'result'='REPLACED' THEN 2 ELSE 1 END) THEN
                        RAISE EXCEPTION 'Invalid warranty vendor receipt' USING ERRCODE='23514'; END IF;
                    expected_state:='MASUK';
                WHEN 'INSPECT' THEN
                    IF prev->>'state'<>'MASUK' OR prev->>'returnedAt' IS NULL
                        OR (input->>'serviceable')::boolean AND cur->>'tracking'='SERIAL' AND NOT (input->>'resetConfirmed')::boolean
                        OR jsonb_array_length(c.movement_ids)<>(CASE WHEN (input->>'serviceable')::boolean THEN 1 ELSE 0 END) THEN
                        RAISE EXCEPTION 'Warranty requires inspection and device reset' USING ERRCODE='23514'; END IF;
                    expected_state:='SELESAI';
            END CASE;
        END IF;
        IF cur->>'state' IS DISTINCT FROM expected_state THEN RAISE EXCEPTION 'Client state differs from command' USING ERRCODE='23514'; END IF;
        IF doc.kind='PURCHASE' AND c.action='RECEIVE' THEN
            SELECT l INTO STRICT item FROM jsonb_array_elements(cur->'lines') l WHERE l->>'id'=input->>'lineId';
            IF (SELECT sum(leg.quantity_base::numeric) FROM inventory_client_movement link
                JOIN inventory_movement m ON m.tenant_id=link.tenant_id AND m.document_id=link.document_id
                JOIN inventory_movement_leg leg ON leg.tenant_id=m.tenant_id AND leg.movement_id=m.id
                WHERE link.tenant_id=scope AND link.resource_id=target AND link.resource_revision=c.revision AND leg.direction='IN'
                    AND leg.location_id=doc.warehouse_id AND leg.sku_id=(item->>'skuId')::uuid AND leg.status='AVAILABLE')
                IS DISTINCT FROM (input->>'quantityBase')::numeric THEN
                RAISE EXCEPTION 'Purchase receipt does not add its ordered SKU quantity' USING ERRCODE='23514'; END IF;
        END IF;
        IF EXISTS(SELECT FROM jsonb_array_elements_text(c.movement_ids) id WHERE NOT EXISTS(
            SELECT FROM inventory_client_movement link WHERE link.tenant_id=scope AND link.resource_id=target AND link.resource_revision=c.revision AND link.document_id=id::uuid)) THEN
            RAISE EXCEPTION 'Client command movement is not bound' USING ERRCODE='23514'; END IF;
        prev:=cur;
    END LOOP;
END $$;
CREATE FUNCTION warehouse_client_final() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target uuid; scope uuid; projection inventory_client_document; command inventory_client_command; BEGIN
    scope:=NEW.tenant_id;
    target:=(to_jsonb(NEW)->>(CASE TG_TABLE_NAME WHEN 'inventory_client_document' THEN 'id' ELSE 'resource_id' END))::uuid;
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO STRICT projection FROM inventory_client_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT command FROM inventory_client_command WHERE tenant_id=scope AND resource_id=target AND revision=projection.revision;
    PERFORM warehouse_assert_client_history(scope,target);
    IF projection.snapshot IS DISTINCT FROM command.snapshot OR command.resource_kind<>projection.kind
        OR projection.snapshot->>'id' IS DISTINCT FROM target::text
        OR projection.snapshot->>'revision' IS DISTINCT FROM projection.revision::text
        OR projection.snapshot->>'warehouseId' IS DISTINCT FROM projection.warehouse_id::text
        OR (SELECT count(*) FROM inventory_client_command WHERE tenant_id=scope AND resource_id=target)<>projection.revision+1 THEN
        RAISE EXCEPTION 'Client document differs from immutable command history' USING ERRCODE='23514'; END IF;
    IF EXISTS(SELECT FROM inventory_client_movement link WHERE tenant_id=scope AND resource_id=target
        AND NOT EXISTS(SELECT FROM inventory_client_command c WHERE c.tenant_id=scope AND c.resource_id=target
            AND c.revision=link.resource_revision AND c.movement_ids @> jsonb_build_array(link.document_id::text))) THEN
        RAISE EXCEPTION 'Client movement requires its immutable command' USING ERRCODE='23514'; END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_client_final AFTER INSERT OR UPDATE ON inventory_client_document DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_client_final();
CREATE CONSTRAINT TRIGGER warehouse_client_final AFTER INSERT ON inventory_client_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_client_final();
CREATE CONSTRAINT TRIGGER warehouse_client_final AFTER INSERT ON inventory_client_movement DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_client_final();

ALTER TABLE inventory_reference_post DROP CONSTRAINT warehouse_reference_stock_action;
ALTER TABLE inventory_reference_post ADD CONSTRAINT warehouse_reference_stock_action CHECK(action IN
    ('RECEIPT','TRANSFER','HANDOVER','RETURN','COUNT_LOSS','COUNT_RECOVER','COUNT_RECEIPT','CONSUME','CLIENT_ISSUE','CLIENT_RETURN','WARRANTY_SEND','WARRANTY_BACK','WARRANTY_INSPECT'));
ALTER FUNCTION warehouse_assert_reference_post(uuid,uuid) RENAME TO warehouse_assert_reference_post_before_client;
CREATE FUNCTION warehouse_assert_reference_post(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE binding inventory_reference_post; document inventory_document; operation inventory_operation; movement inventory_movement;
    expected jsonb; actual jsonb; link inventory_client_movement; command inventory_client_command;
    current_snapshot jsonb; prior_snapshot jsonb; input jsonb; item jsonb; stock_input jsonb; dest inventory_movement_leg; source inventory_movement_leg;
    line inventory_document_line; expected_condition text; canonical text; body jsonb; BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO binding FROM inventory_reference_post WHERE tenant_id=scope AND document_id=target;
    IF binding.action IS NULL OR binding.action NOT IN ('CLIENT_ISSUE','CLIENT_RETURN','WARRANTY_SEND','WARRANTY_BACK','WARRANTY_INSPECT') THEN
        PERFORM warehouse_assert_reference_post_before_client(scope,target); RETURN; END IF;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT operation FROM inventory_operation WHERE tenant_id=scope AND id=binding.id;
    SELECT * INTO STRICT movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=binding.id;
    SELECT * INTO STRICT link FROM inventory_client_movement WHERE tenant_id=scope AND document_id=target;
    SELECT * INTO STRICT command FROM inventory_client_command WHERE tenant_id=scope AND resource_id=link.resource_id AND revision=link.resource_revision;
    current_snapshot:=command.snapshot; input:=command.canonical_payload::jsonb->'input';
    SELECT canonical_payload INTO STRICT canonical FROM inventory_command_identity WHERE tenant_id=scope AND id=operation.id;
    stock_input:=canonical::jsonb; body:=operation.original_body::jsonb;
    SELECT snapshot INTO prior_snapshot FROM inventory_client_command WHERE tenant_id=scope AND resource_id=link.resource_id AND revision=link.resource_revision-1;
    IF document.kind<>'TRANSFER' OR document.state<>'RECEIVED' OR document.revision<>1
        OR operation.namespace IS DISTINCT FROM 'warehouse.reference.'||lower(binding.action)
        OR operation.business_action IS DISTINCT FROM binding.action OR operation.document_id IS DISTINCT FROM target
        OR operation.resource_id IS DISTINCT FROM target OR operation.document_revision IS DISTINCT FROM 1
        OR operation.cutover_epoch IS DISTINCT FROM document.cutover_epoch OR movement.kind IS DISTINCT FROM 'TRANSFER'
        OR movement.operation_namespace IS DISTINCT FROM operation.namespace
        OR document.work_order_id IS NOT NULL OR document.source_document_id IS NOT NULL
        OR movement.document_id IS DISTINCT FROM target OR movement.document_revision<>1 OR movement.state<>'APPLIED'
        OR NOT command.movement_ids @> jsonb_build_array(target::text)
        OR (SELECT count(*) FROM inventory_operation WHERE tenant_id=scope AND document_id=target)<>1 THEN
        RAISE EXCEPTION 'Client stock requires bound operation and command' USING ERRCODE='23514'; END IF;
    IF operation.payload_hash IS DISTINCT FROM encode(sha256(convert_to(canonical,'UTF8')),'hex')
        OR operation.actor_id IS DISTINCT FROM document.actor_id OR movement.actor_id IS DISTINCT FROM operation.actor_id
        OR movement.payload_hash IS DISTINCT FROM operation.payload_hash OR movement.operation_key IS DISTINCT FROM operation.operation_key
        OR operation.authority_epoch IS DISTINCT FROM document.authority_epoch OR operation.resource_scope IS DISTINCT FROM 'reference:'||target
        OR operation.original_status IS DISTINCT FROM 201 OR document.reason IS DISTINCT FROM stock_input#>>'{input,evidence}'
        OR body->>'documentId' IS DISTINCT FROM target::text OR body->>'quantityBase' IS DISTINCT FROM stock_input#>>'{input,quantity}'
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND document_id=target)<>1
        OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=binding.id AND document_id=target AND document_revision=1) THEN
        RAISE EXCEPTION 'Client posting requires exact canonical command, response and actor' USING ERRCODE='23514'; END IF;
    SELECT jsonb_agg(legs_json.value ORDER BY legs_json.value::text) INTO expected FROM jsonb_array_elements(binding.expected_legs) AS legs_json(value);
    SELECT jsonb_agg(rows.item ORDER BY rows.item::text) INTO actual FROM (SELECT jsonb_build_object('lineId',document_line_id,
        'direction',direction,'identityId',stock_identity_id,'skuId',sku_id,'lotId',lot_id,'locationId',location_id,
        'custodianId',custody_owner_id,'custodianKind',custody_owner_kind,'condition',condition,'legalOwner',legal_owner,
        'status',status,'quantityBase',quantity_base::text,'baseUnit',base_unit) item
        FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id) rows;
    IF actual IS DISTINCT FROM expected OR EXISTS(SELECT FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id
        AND (legal_owner<>'ISP' OR location_id NOT IN (binding.source_location_id,binding.destination_location_id))) THEN
        RAISE EXCEPTION 'Client movement differs from frozen physical legs' USING ERRCODE='23514'; END IF;
    SELECT * INTO STRICT line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    SELECT * INTO STRICT dest FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN' AND location_id=binding.destination_location_id;
    SELECT * INTO STRICT source FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
    IF operation.actor_id<>command.actor_id OR operation.authority_epoch<>command.authority_epoch OR operation.cutover_epoch<>command.cutover_epoch
        OR stock_input->>'id' IS DISTINCT FROM link.resource_id::text
        OR stock_input#>>'{input,revision}' IS DISTINCT FROM link.resource_revision::text
        OR source.stock_identity_id<>line.stock_identity_id OR source.sku_id<>line.sku_id OR dest.sku_id<>line.sku_id
        OR source.location_id<>binding.source_location_id OR dest.quantity_base<>line.quantity_base
        OR line.base_unit<>dest.base_unit OR line.condition<>source.condition OR line.custodian_id<>source.custody_owner_id
        OR line.custodian_kind<>source.custody_owner_kind OR line.location_id<>source.location_id OR line.destination_location_id<>dest.location_id
        OR stock_input#>>'{input,action}' IS DISTINCT FROM binding.action OR stock_input#>>'{input,identity}' IS DISTINCT FROM source.stock_identity_id::text
        OR stock_input#>>'{input,source}' IS DISTINCT FROM source.location_id::text OR stock_input#>>'{input,destination}' IS DISTINCT FROM dest.location_id::text
        OR stock_input#>>'{input,quantity}' IS DISTINCT FROM dest.quantity_base::text OR stock_input#>>'{input,custodian}' IS DISTINCT FROM dest.custody_owner_id::text
        OR stock_input#>>'{input,kind}' IS DISTINCT FROM dest.custody_owner_kind OR stock_input#>>'{input,status}' IS DISTINCT FROM dest.status
        OR stock_input#>>'{input,condition}' IS DISTINCT FROM dest.condition OR body->>'stockIdentityId' IS DISTINCT FROM dest.stock_identity_id::text
        OR EXISTS(SELECT FROM inventory_movement_leg leg WHERE leg.tenant_id=scope AND leg.movement_id=movement.id
            AND (leg.document_line_id IS DISTINCT FROM line.id OR leg.sku_id IS DISTINCT FROM line.sku_id
                OR leg.base_unit IS DISTINCT FROM line.base_unit OR leg.lot_id IS DISTINCT FROM line.lot_id)) THEN
        RAISE EXCEPTION 'Client physical document or actor differs from command' USING ERRCODE='23514'; END IF;
    CASE binding.action
        WHEN 'CLIENT_ISSUE' THEN
            SELECT l INTO STRICT item FROM jsonb_array_elements(current_snapshot->'lines') l CROSS JOIN LATERAL jsonb_array_elements(l->'stocks') s
                WHERE s->>'stockIdentityId'=dest.stock_identity_id::text AND s->>'quantityBase'=dest.quantity_base::text;
            IF command.resource_kind<>'LOAN' OR command.action<>'ISSUE' OR source.location_id::text<>current_snapshot->>'warehouseId'
                OR source.status<>'AVAILABLE' OR source.condition<>'SERVICEABLE' OR source.custody_owner_kind<>'WAREHOUSE'
                OR source.custody_owner_id<>source.location_id OR item->>'skuId'<>dest.sku_id::text
                OR dest.location_id::text<>current_snapshot->>'externalLocationId' OR dest.status<>'ISSUED' OR dest.condition<>'SERVICEABLE'
                OR dest.custody_owner_kind<>'EXTERNAL' OR dest.custody_owner_id<>link.resource_id THEN
                RAISE EXCEPTION 'Loan issue dimensions differ from approved document' USING ERRCODE='23514'; END IF;
        WHEN 'CLIENT_RETURN' THEN
            SELECT l INTO STRICT item FROM jsonb_array_elements(input->'lines') l WHERE l->>'stockIdentityId'=source.stock_identity_id::text;
            expected_condition:=item->>'condition';
            IF command.resource_kind<>'LOAN' OR command.action<>'RETURN' OR source.location_id::text<>prior_snapshot->>'externalLocationId'
                OR source.status<>'ISSUED' OR source.custody_owner_kind<>'EXTERNAL' OR source.custody_owner_id<>link.resource_id
                OR item->>'quantityBase'<>dest.quantity_base::text OR expected_condition NOT IN ('SERVICEABLE','DAMAGED')
                OR NOT EXISTS(SELECT FROM jsonb_array_elements(prior_snapshot->'lines') l CROSS JOIN LATERAL jsonb_array_elements(l->'stocks') s
                    WHERE l->>'id'=item->>'lineId' AND l->>'skuId'=dest.sku_id::text AND s->>'stockIdentityId'=source.stock_identity_id::text)
                OR dest.custody_owner_kind<>'WAREHOUSE' OR dest.custody_owner_id<>dest.location_id OR dest.condition<>expected_condition
                OR expected_condition='SERVICEABLE' AND (dest.status<>'AVAILABLE' OR dest.location_id::text<>current_snapshot->>'warehouseId')
                OR expected_condition='DAMAGED' AND (dest.status<>'QUARANTINE' OR NOT EXISTS(SELECT FROM inventory_location q
                    WHERE q.tenant_id=scope AND q.id=dest.location_id AND q.kind='QUARANTINE' AND q.parent_location_id::text=current_snapshot->>'warehouseId')) THEN
                RAISE EXCEPTION 'Loan return dimensions differ from return command' USING ERRCODE='23514'; END IF;
        WHEN 'WARRANTY_SEND' THEN
            IF command.resource_kind<>'WARRANTY' OR command.action<>'DISPATCH' OR source.stock_identity_id::text<>prior_snapshot->>'stockIdentityId'
                OR source.location_id::text<>prior_snapshot->>'locationId' OR source.custody_owner_id<>source.location_id
                OR source.status<>'QUARANTINE' OR source.custody_owner_kind<>'WAREHOUSE' OR source.condition<>'DAMAGED'
                OR dest.status<>'IN_TRANSIT' OR dest.custody_owner_kind<>'REPAIR' OR dest.condition<>'DAMAGED'
                OR dest.custody_owner_id::text<>current_snapshot->>'supplierId' THEN
                RAISE EXCEPTION 'Warranty dispatch dimensions differ from claim' USING ERRCODE='23514'; END IF;
        WHEN 'WARRANTY_BACK' THEN
            IF command.resource_kind<>'WARRANTY' OR command.action<>'RECEIVE' OR dest.status<>'QUARANTINE'
                OR dest.condition<>'QUARANTINE' OR dest.custody_owner_kind<>'WAREHOUSE' OR dest.custody_owner_id<>dest.location_id
                OR current_snapshot->>'result'<>'REPLACED' AND (source.stock_identity_id::text<>prior_snapshot->>'stockIdentityId'
                    OR source.status<>'IN_TRANSIT' OR source.custody_owner_kind<>'REPAIR' OR source.custody_owner_id::text<>prior_snapshot->>'supplierId')
                OR current_snapshot->>'result'='REPLACED' AND (source.stock_identity_id::text<>current_snapshot->>'replacementAssetId'
                    OR source.status<>'AVAILABLE' OR source.custody_owner_kind<>'WAREHOUSE' OR source.location_id::text<>current_snapshot->>'warehouseId') THEN
                RAISE EXCEPTION 'Warranty vendor receipt dimensions differ from claim' USING ERRCODE='23514'; END IF;
        WHEN 'WARRANTY_INSPECT' THEN
            IF command.resource_kind<>'WARRANTY' OR command.action<>'INSPECT' OR NOT (input->>'serviceable')::boolean
                OR source.stock_identity_id::text<>prior_snapshot->>'stockIdentityId' OR source.status<>'QUARANTINE' OR source.custody_owner_kind<>'WAREHOUSE'
                OR dest.location_id::text<>current_snapshot->>'warehouseId' OR dest.status<>'AVAILABLE' OR dest.condition<>'SERVICEABLE'
                OR dest.custody_owner_kind<>'WAREHOUSE' OR dest.custody_owner_id<>dest.location_id THEN
                RAISE EXCEPTION 'Warranty stock release requires inspection' USING ERRCODE='23514'; END IF;
    END CASE;
    IF command.resource_kind='WARRANTY' AND (dest.stock_identity_id::text<>current_snapshot->>'stockIdentityId'
        OR dest.location_id::text<>current_snapshot->>'locationId'
        OR dest.sku_id::text<>current_snapshot->>'skuId' OR dest.quantity_base::text<>current_snapshot->>'quantityBase') THEN
        RAISE EXCEPTION 'Warranty stock identity or quantity differs from claim' USING ERRCODE='23514'; END IF;
END $$;
DO $$ DECLARE definition text; anchor text:='IF NOT permitted THEN'; BEGIN
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'Client document guard anchor changed'; END IF;
    definition:=replace(definition,anchor,$body$IF OLD.state='DRAFT' AND NEW.kind='TRANSFER' AND NEW.state='RECEIVED' AND EXISTS(
        SELECT FROM inventory_reference_post binding JOIN inventory_operation op ON op.tenant_id=binding.tenant_id AND op.id=binding.id
        WHERE binding.tenant_id=NEW.tenant_id AND binding.document_id=NEW.id AND binding.created_xid=pg_current_xact_id()
            AND binding.action IN ('CLIENT_ISSUE','CLIENT_RETURN','WARRANTY_SEND','WARRANTY_BACK','WARRANTY_INSPECT')
            AND op.namespace='warehouse.reference.'||lower(binding.action) AND op.document_revision=NEW.revision) THEN permitted:=true; END IF;
        IF NOT permitted THEN$body$);
    EXECUTE definition;
END $$;
