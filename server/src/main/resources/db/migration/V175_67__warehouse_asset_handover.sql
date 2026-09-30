CREATE TABLE inventory_asset_acceptance (
    tenant_id uuid NOT NULL, handover_id uuid NOT NULL, authorization_id uuid NOT NULL,
    operation_id uuid NOT NULL, operation_key text NOT NULL CHECK (btrim(operation_key)<>''),
    payload_hash text NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    evidence_digest text NOT NULL CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    source_asset_revision bigint NOT NULL CHECK (source_asset_revision>=0),
    source_title_revision bigint NOT NULL CHECK (source_title_revision=0),
    work_order_revision bigint NOT NULL CHECK (work_order_revision>=0),
    authority_epoch bigint NOT NULL CHECK (authority_epoch>=0), cutover_epoch bigint NOT NULL CHECK (cutover_epoch>=0),
    snapshot text NOT NULL, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY (tenant_id,handover_id), UNIQUE (tenant_id,operation_id), UNIQUE (tenant_id,operation_key),
    FOREIGN KEY (tenant_id,handover_id) REFERENCES inventory_asset_handover(tenant_id,id),
    FOREIGN KEY (tenant_id,authorization_id) REFERENCES inventory_deployment_authorization(tenant_id,id),
    FOREIGN KEY (tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE inventory_asset_recovery_obligation (
    tenant_id uuid NOT NULL, assignment_id uuid NOT NULL, asset_id uuid NOT NULL, customer_id uuid NOT NULL,
    handover_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id,assignment_id), UNIQUE (tenant_id,handover_id),
    FOREIGN KEY (tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY (tenant_id,asset_id) REFERENCES inventory_serialized_asset(tenant_id,id),
    FOREIGN KEY (tenant_id,customer_id) REFERENCES customer(tenant_id,id),
    FOREIGN KEY (tenant_id,handover_id) REFERENCES inventory_asset_handover(tenant_id,id)
);

DO $$ DECLARE table_name text; definition text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_acceptance','inventory_asset_recovery_obligation'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF strpos(definition,'CASE OLD.kind')=0 OR strpos(definition,'NEW.kind NOT IN (''DEMAND'',''USAGE'')')=0 THEN
        RAISE EXCEPTION 'expected document lifecycle missing';
    END IF;
    definition:=replace(definition,'CASE OLD.kind','CASE OLD.kind WHEN ''ASSET_HANDOVER'' THEN (OLD.state,NEW.state)=(''DRAFT'',''POSTED'')');
    EXECUTE replace(definition,'NEW.kind NOT IN (''DEMAND'',''USAGE'')','NEW.kind NOT IN (''DEMAND'',''USAGE'',''ASSET_HANDOVER'')');
END $$;

CREATE FUNCTION warehouse_asset_expected_owner(scope uuid, assignment_id uuid) RETURNS text LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    IF EXISTS(SELECT FROM inventory_asset_handover WHERE tenant_id=scope AND inventory_asset_handover.assignment_id=$2
        AND ownership_mode='SALE' AND warehouse_admission='VERIFIED') THEN RETURN 'CUSTOMER'; END IF;
    RETURN 'ISP';
END $$;

CREATE OR REPLACE FUNCTION warehouse_assignment_history_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_OP='UPDATE' THEN
        IF OLD.ended_at IS NULL AND NEW.ended_at IS NULL AND NEW.revision=OLD.revision+1
            AND to_jsonb(NEW)-ARRAY['legal_owner','revision']=to_jsonb(OLD)-ARRAY['legal_owner','revision']
            AND OLD.legal_owner='ISP' AND NEW.legal_owner=warehouse_asset_expected_owner(NEW.tenant_id,NEW.id)
            AND EXISTS(SELECT FROM inventory_asset_handover handover JOIN inventory_asset_acceptance acceptance
                ON acceptance.tenant_id=handover.tenant_id AND acceptance.handover_id=handover.id
                WHERE handover.tenant_id=NEW.tenant_id AND handover.assignment_id=NEW.id
                AND handover.assignment_revision=OLD.revision AND acceptance.created_xid=pg_current_xact_id()) THEN
            RETURN NEW;
        END IF;
        IF (to_jsonb(NEW)-ARRAY['ended_at','revision']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['ended_at','revision'])
            OR OLD.ended_at IS NOT NULL OR NEW.ended_at IS NULL OR NEW.revision<>OLD.revision+1 THEN
            RAISE EXCEPTION 'assignment changes require an immutable accepted transfer or closure' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.revision<>0 THEN
        RAISE EXCEPTION 'assignment begins at revision zero' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_result(uuid,uuid)'::regprocedure);
    IF strpos(definition,'permit.purpose,permit.ownership_mode,''ISP''::text,permit.operation_id')=0
        OR strpos(definition,'condition=''SERVICEABLE'' AND legal_owner=''ISP''')=0
        OR strpos(definition,'status=''CUSTOMER_INSTALLED'' AND legal_owner=''ISP''')=0 THEN
        RAISE EXCEPTION 'expected deployment owner clauses missing';
    END IF;
    definition:=replace(definition,'permit.purpose,permit.ownership_mode,''ISP''::text,permit.operation_id',
        'permit.purpose,permit.ownership_mode,warehouse_asset_expected_owner(scope,assignment.id),permit.operation_id');
    definition:=replace(definition,'condition=''SERVICEABLE'' AND legal_owner=''ISP''',
        'condition=''SERVICEABLE'' AND legal_owner=warehouse_asset_expected_owner(scope,assignment.id)');
    definition:=replace(definition,'status=''CUSTOMER_INSTALLED'' AND legal_owner=''ISP''',
        'status=''CUSTOMER_INSTALLED'' AND legal_owner=warehouse_asset_expected_owner(scope,assignment.id)');
    EXECUTE definition;
END $$;
