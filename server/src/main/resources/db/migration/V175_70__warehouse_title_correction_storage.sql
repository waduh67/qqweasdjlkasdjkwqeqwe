CREATE TABLE inventory_asset_title_request (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, assignment_id uuid NOT NULL, handover_id uuid NOT NULL,
    asset_id uuid NOT NULL, customer_id uuid NOT NULL, work_order_id uuid NOT NULL, actor_id uuid NOT NULL,
    source_assignment_revision bigint NOT NULL CHECK (source_assignment_revision>=1),
    source_title_revision bigint NOT NULL CHECK (source_title_revision>=0), source_asset_revision bigint NOT NULL CHECK (source_asset_revision>=0),
    source_owner text NOT NULL CHECK (source_owner IN ('ISP','CUSTOMER')), target_owner text NOT NULL CHECK (target_owner IN ('ISP','CUSTOMER')),
    evidence_id uuid NOT NULL, evidence_digest text NOT NULL CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500), operation_key text NOT NULL CHECK (btrim(operation_key)<>''),
    payload_hash text NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'), snapshot text NOT NULL,
    created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,operation_key), UNIQUE(tenant_id,assignment_id,source_title_revision,payload_hash),
    CHECK (source_owner<>target_owner),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,handover_id) REFERENCES inventory_asset_handover(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY(tenant_id,evidence_id) REFERENCES evidence_object_registry(tenant_id,revision_id)
);
CREATE TABLE inventory_asset_title_transfer (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, request_id uuid NOT NULL, approval_id uuid NOT NULL,
    operation_id uuid NOT NULL, posting_id uuid NOT NULL, assignment_id uuid NOT NULL, handover_id uuid NOT NULL,
    asset_id uuid NOT NULL, customer_id uuid NOT NULL, work_order_id uuid NOT NULL,
    source_owner text NOT NULL CHECK (source_owner IN ('ISP','CUSTOMER')), target_owner text NOT NULL CHECK (target_owner IN ('ISP','CUSTOMER')),
    source_assignment_revision bigint NOT NULL, assignment_revision bigint NOT NULL,
    source_title_revision bigint NOT NULL, title_revision bigint NOT NULL, source_asset_revision bigint NOT NULL,
    actor_id uuid NOT NULL, recorded_at timestamptz NOT NULL, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,request_id), UNIQUE(tenant_id,approval_id), UNIQUE(tenant_id,operation_id), UNIQUE(tenant_id,posting_id),
    UNIQUE(tenant_id,assignment_id,title_revision), UNIQUE(tenant_id,assignment_id,assignment_revision),
    CHECK(source_owner<>target_owner AND source_assignment_revision>=1 AND source_title_revision>=0 AND source_asset_revision>=0),
    CHECK(assignment_revision=source_assignment_revision+1 AND title_revision=source_title_revision+1),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_asset_title_request(tenant_id,id),
    FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY(tenant_id,posting_id) REFERENCES inventory_movement(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,handover_id) REFERENCES inventory_asset_handover(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY(tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY(tenant_id,actor_id) REFERENCES app_user(tenant_id,id)
);
CREATE TABLE inventory_asset_recovery_transition (
    tenant_id uuid NOT NULL, transfer_id uuid NOT NULL, assignment_id uuid NOT NULL, asset_id uuid NOT NULL,
    customer_id uuid NOT NULL, required boolean NOT NULL,
    PRIMARY KEY(tenant_id,transfer_id),
    FOREIGN KEY(tenant_id,transfer_id) REFERENCES inventory_asset_title_transfer(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id)
);
DO $$ DECLARE table_name text; item record; definition text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_title_request','inventory_asset_title_transfer','inventory_asset_recovery_transition'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    FOR item IN SELECT conname,pg_get_constraintdef(oid) definition FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND conname IN ('inventory_document_kind_check','inventory_document_check2') LOOP
        EXECUTE format('ALTER TABLE inventory_document DROP CONSTRAINT %I',item.conname);
        EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT %I CHECK ((%s) OR (kind=''TITLE_CORRECTION'' AND state IN (''DRAFT'',''POSTED'')))',
            item.conname,substring(item.definition FROM 8 FOR length(item.definition)-8));
    END LOOP;
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF strpos(definition,'CASE OLD.kind')=0 THEN RAISE EXCEPTION 'document lifecycle missing'; END IF;
    EXECUTE replace(definition,'CASE OLD.kind','CASE OLD.kind WHEN ''TITLE_CORRECTION'' THEN (OLD.state,NEW.state)=(''DRAFT'',''POSTED'')');
END $$;

CREATE OR REPLACE FUNCTION warehouse_asset_expected_owner(scope uuid, assignment_id uuid) RETURNS text LANGUAGE plpgsql AS $$
DECLARE owner text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT target_owner INTO owner FROM inventory_asset_title_transfer WHERE tenant_id=scope
        AND inventory_asset_title_transfer.assignment_id=$2 ORDER BY title_revision DESC LIMIT 1;
    IF FOUND THEN RETURN owner; END IF;
    IF EXISTS(SELECT FROM inventory_asset_handover WHERE tenant_id=scope AND inventory_asset_handover.assignment_id=$2
        AND ownership_mode='SALE' AND warehouse_admission='VERIFIED') THEN RETURN 'CUSTOMER'; END IF;
    RETURN 'ISP';
END $$;

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_asset_handover(uuid,uuid)'::regprocedure);
    IF strpos(definition,'SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=scope AND id=handover.assignment_id;')=0 THEN
        RAISE EXCEPTION 'handover assignment clause missing';
    END IF;
    definition:=replace(definition,'SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=scope AND id=handover.assignment_id;',
        'SELECT (jsonb_populate_record(NULL::inventory_asset_assignment,history.snapshot)).* INTO assignment FROM inventory_asset_assignment_history history WHERE history.tenant_id=scope AND history.assignment_id=handover.assignment_id AND history.revision=handover.assignment_revision+1;');
    definition:=replace(definition,'(original-ARRAY[''legal_owner'',''revision''])',
        '(to_jsonb(jsonb_populate_record(NULL::inventory_asset_assignment,original))-ARRAY[''legal_owner'',''revision''])');
    EXECUTE definition;
END $$;
ALTER FUNCTION warehouse_assert_asset_handover(uuid,uuid) RENAME TO warehouse_assert_asset_handover_v69;
