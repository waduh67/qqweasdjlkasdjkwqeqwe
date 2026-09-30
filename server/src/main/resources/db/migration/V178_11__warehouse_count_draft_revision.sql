-- Only an operational COUNT that has never started can replace its scope/assignments.
-- Observations, rounds, results and all former command receipts remain immutable.
CREATE FUNCTION warehouse_assert_count_draft(scope uuid, target uuid) RETURNS void
LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE document inventory_document; binding inventory_count_scope; operation inventory_operation;
    snapshot jsonb; canonical jsonb; canonical_text text; request jsonb; assignments jsonb; entries jsonb; command_count bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO binding FROM inventory_count_scope WHERE tenant_id=scope AND id=target;
    IF binding.id IS NULL OR document.state<>'DRAFT' THEN RETURN; END IF;
    IF document.kind<>'COUNT' OR
        EXISTS(SELECT FROM inventory_count_round WHERE tenant_id=scope AND document_id=target) OR
        EXISTS(SELECT FROM inventory_cycle_count WHERE tenant_id=scope AND document_id=target) OR
        EXISTS(SELECT FROM inventory_count_result WHERE tenant_id=scope AND id=target) OR
        EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=target) OR
        EXISTS(SELECT FROM inventory_approval WHERE tenant_id=scope AND source_document_id=target) THEN
        RAISE EXCEPTION 'editable count cannot have counting or posting history' USING ERRCODE='23514';
    END IF;
    SELECT count(*) INTO command_count FROM inventory_operation WHERE tenant_id=scope AND document_id=target;
    IF command_count<>document.revision+1 OR
        (SELECT count(DISTINCT document_revision) FROM inventory_operation WHERE tenant_id=scope AND document_id=target)<>document.revision+1 THEN
        RAISE EXCEPTION 'count draft command revision gap' USING ERRCODE='23514';
    END IF;
    FOR operation IN SELECT * FROM inventory_operation WHERE tenant_id=scope AND document_id=target ORDER BY document_revision LOOP
        snapshot:=operation.original_body::jsonb;
        SELECT canonical_payload INTO canonical_text FROM inventory_command_identity WHERE tenant_id=scope AND id=operation.id;
        canonical:=canonical_text::jsonb;
        request:=CASE WHEN operation.document_revision=0 THEN canonical ELSE canonical#>'{input,draft}' END;
        IF operation.namespace IS DISTINCT FROM (CASE WHEN operation.document_revision=0 THEN 'warehouse.count.create' ELSE 'warehouse.count.update' END) OR
            operation.actor_id IS DISTINCT FROM document.actor_id OR operation.resource_id IS DISTINCT FROM target OR
            operation.resource_scope IS DISTINCT FROM 'count:'||target OR
            operation.business_action IS DISTINCT FROM (CASE WHEN operation.document_revision=0 THEN 'COUNT_CREATE' ELSE 'COUNT_UPDATE' END) OR
            operation.original_status IS DISTINCT FROM (CASE WHEN operation.document_revision=0 THEN 201 ELSE 200 END) OR
            canonical IS NULL OR request IS NULL OR
            operation.payload_hash IS DISTINCT FROM encode(sha256(convert_to(canonical_text,'UTF8')),'hex') OR
            (operation.document_revision>0 AND (canonical->>'id' IS DISTINCT FROM target::text OR
                (canonical#>>'{input,expectedRevision}')::bigint IS DISTINCT FROM operation.document_revision-1)) OR
            snapshot->>'id' IS DISTINCT FROM target::text OR snapshot->>'state' IS DISTINCT FROM 'DRAFT' OR
            (snapshot->>'revision')::bigint IS DISTINCT FROM operation.document_revision OR
            snapshot->>'locationId' IS DISTINCT FROM request->>'locationId' OR
            snapshot->>'partialLocation' IS DISTINCT FROM 'true' OR request->>'partialLocation' IS DISTINCT FROM 'true' OR
            snapshot->>'roundRevision' IS NOT NULL THEN
            RAISE EXCEPTION 'count draft command binding mismatch' USING ERRCODE='23514';
        END IF;
    END LOOP;
    SELECT jsonb_agg(jsonb_build_object('balanceId',entry.balance_id,'counterId',entry.counter_id) ORDER BY line.line_number),
        jsonb_agg(jsonb_build_object('balanceId',entry.balance_id,'counterId',entry.counter_id,
            'stockIdentityId',line.stock_identity_id,'skuId',line.sku_id,'baseUnit',line.base_unit) ORDER BY line.line_number)
        INTO assignments,entries FROM inventory_count_entry entry JOIN inventory_document_line line
            ON line.tenant_id=entry.tenant_id AND line.id=entry.id
        WHERE entry.tenant_id=scope AND entry.document_id=target AND line.document_id=target;
    IF assignments IS NULL OR jsonb_array_length(assignments) NOT BETWEEN 1 AND 100 OR
        jsonb_array_length(assignments)<>(SELECT count(*) FROM inventory_document_line WHERE tenant_id=scope AND document_id=target) OR
        request->'entries' IS DISTINCT FROM assignments OR request->>'reason' IS DISTINCT FROM document.reason OR
        request->>'locationId' IS DISTINCT FROM binding.location_id::text OR
        EXISTS(SELECT FROM inventory_document_line WHERE tenant_id=scope AND document_id=target AND
            (document_revision<>document.revision OR location_id IS DISTINCT FROM binding.location_id)) OR
        snapshot IS DISTINCT FROM jsonb_build_object('id',target,'revision',document.revision,'state','DRAFT',
            'locationId',binding.location_id,'partialLocation',binding.partial_location,'roundRevision',NULL,'entries',entries) THEN
        RAISE EXCEPTION 'count draft snapshot does not match its current assignments' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_count_draft_child_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE document inventory_document; target uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(OLD.tenant_id);
    IF TG_OP='UPDATE' THEN PERFORM warehouse_assert_deferred_scope(NEW.tenant_id); END IF;
    target:=(to_jsonb(OLD)->>CASE WHEN TG_TABLE_NAME='inventory_count_scope' THEN 'id' ELSE 'document_id' END)::uuid;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=OLD.tenant_id AND id=target FOR UPDATE;
    IF document.kind IS DISTINCT FROM 'COUNT' OR document.state IS DISTINCT FROM 'DRAFT' OR
        EXISTS(SELECT FROM inventory_count_round WHERE tenant_id=OLD.tenant_id AND document_id=target) OR
        EXISTS(SELECT FROM inventory_cycle_count WHERE tenant_id=OLD.tenant_id AND document_id=target) OR
        EXISTS(SELECT FROM inventory_count_result WHERE tenant_id=OLD.tenant_id AND id=target) OR
        EXISTS(SELECT FROM inventory_movement WHERE tenant_id=OLD.tenant_id AND document_id=target) OR
        EXISTS(SELECT FROM inventory_approval WHERE tenant_id=OLD.tenant_id AND source_document_id=target) THEN
        RAISE EXCEPTION 'count scope and assignments are frozen after counting starts' USING ERRCODE='23514';
    END IF;
    IF TG_TABLE_NAME='inventory_count_scope' THEN
        IF TG_OP<>'UPDATE' OR to_jsonb(NEW)-ARRAY['location_id','partial_location'] IS DISTINCT FROM
            to_jsonb(OLD)-ARRAY['location_id','partial_location'] THEN
            RAISE EXCEPTION 'count scope identity is immutable' USING ERRCODE='23514';
        END IF;
    ELSIF TG_OP<>'DELETE' THEN
        RAISE EXCEPTION 'draft count assignments must be replaced as a complete set' USING ERRCODE='23514';
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
DROP TRIGGER warehouse_count_immutable ON inventory_count_scope;
DROP TRIGGER warehouse_count_immutable ON inventory_count_entry;
CREATE TRIGGER warehouse_count_draft_child BEFORE UPDATE OR DELETE ON inventory_count_scope
    FOR EACH ROW EXECUTE FUNCTION warehouse_count_draft_child_guard();
CREATE TRIGGER warehouse_count_draft_child BEFORE UPDATE OR DELETE ON inventory_count_entry
    FOR EACH ROW EXECUTE FUNCTION warehouse_count_draft_child_guard();

CREATE FUNCTION warehouse_count_draft_line_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(OLD.tenant_id);
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF EXISTS(SELECT FROM inventory_count_scope WHERE tenant_id=OLD.tenant_id AND id=OLD.document_id) THEN
        RAISE EXCEPTION 'count lines must be replaced with their complete draft assignments' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_count_draft_line BEFORE UPDATE ON inventory_document_line
    FOR EACH ROW EXECUTE FUNCTION warehouse_count_draft_line_guard();

DO $$ DECLARE definition text; anchor text:='AND balance.location_id=scope.location_id AND balance.warehouse_admission=''VERIFIED''';
BEGIN
    definition:=pg_get_functiondef('warehouse_count_scope_guard()'::regprocedure);
    IF strpos(definition,anchor)=0 THEN RAISE EXCEPTION 'count dimension binding anchor missing'; END IF;
    EXECUTE replace(definition,anchor,anchor || $binding$
                AND line.base_unit=balance.base_unit AND EXISTS(SELECT FROM inventory_segment segment
                    WHERE segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
                        AND segment.warehouse_admission='VERIFIED' AND segment.state='ACTIVE'
                        AND line.quantity_base=segment.quantity_base)$binding$);
END $$;

CREATE FUNCTION warehouse_count_draft_header_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(OLD.tenant_id);
    IF TG_OP='UPDATE' THEN PERFORM warehouse_assert_deferred_scope(NEW.tenant_id); END IF;
    IF NOT EXISTS(SELECT FROM inventory_count_scope WHERE tenant_id=OLD.tenant_id AND id=OLD.id) THEN
        RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'count identity and history are immutable' USING ERRCODE='23514'; END IF;
    IF OLD.state='DRAFT' THEN
        IF NEW.state='DRAFT' AND (NEW.revision<>OLD.revision+1 OR
            to_jsonb(NEW)-ARRAY['revision','updated_at','reason'] IS DISTINCT FROM
            to_jsonb(OLD)-ARRAY['revision','updated_at','reason']) THEN
            RAISE EXCEPTION 'count draft edit requires its next revision and original requester' USING ERRCODE='23514';
        ELSIF NEW.state<>'DRAFT' THEN
            PERFORM warehouse_assert_count_draft(OLD.tenant_id,OLD.id);
            IF to_jsonb(NEW)-ARRAY['state','revision','updated_at','closed_at'] IS DISTINCT FROM
                to_jsonb(OLD)-ARRAY['state','revision','updated_at','closed_at'] THEN
                RAISE EXCEPTION 'starting a count cannot rewrite its draft' USING ERRCODE='23514';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_count_draft_header BEFORE UPDATE OR DELETE ON inventory_document
    FOR EACH ROW EXECUTE FUNCTION warehouse_count_draft_header_guard();

CREATE FUNCTION warehouse_count_draft_final_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE data jsonb; target uuid; scope uuid;
BEGIN
    IF TG_OP<>'INSERT' THEN PERFORM warehouse_assert_deferred_scope(OLD.tenant_id); END IF;
    IF TG_OP<>'DELETE' THEN PERFORM warehouse_assert_deferred_scope(NEW.tenant_id); END IF;
    data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(data->>'tenant_id')::uuid;
    target:=CASE TG_TABLE_NAME
        WHEN 'inventory_document' THEN (data->>'id')::uuid
        WHEN 'inventory_count_scope' THEN (data->>'id')::uuid
        WHEN 'inventory_command_identity' THEN (SELECT document_id FROM inventory_operation WHERE tenant_id=scope AND id=(data->>'id')::uuid)
        ELSE (data->>'document_id')::uuid END;
    IF target IS NOT NULL THEN PERFORM warehouse_assert_count_draft(scope,target); END IF;
    RETURN NULL;
END $$;
DO $$ DECLARE relation text;
BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_document','inventory_document_line','inventory_count_scope','inventory_count_entry',
        'inventory_operation','inventory_command_identity'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_count_draft_final AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_count_draft_final_guard()',relation);
    END LOOP;
END $$;
