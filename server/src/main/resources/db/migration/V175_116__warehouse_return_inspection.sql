CREATE TABLE inventory_return_case (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
    origin varchar(24) NOT NULL CHECK(origin='MATERIAL_RESIDUAL'),
    source_document_id uuid NOT NULL, stock_identity_id uuid NOT NULL, quarantine_location_id uuid NOT NULL,
    body text NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(tenant_id,id), UNIQUE(tenant_id,origin,source_document_id),
    FOREIGN KEY(tenant_id,id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,source_document_id) REFERENCES inventory_document(tenant_id,id),
    FOREIGN KEY(tenant_id,stock_identity_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY(tenant_id,quarantine_location_id) REFERENCES inventory_location(tenant_id,id)
);
CREATE INDEX warehouse_return_location_idx ON inventory_return_case(tenant_id,quarantine_location_id,created_at,id);
ALTER TABLE inventory_return_case ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_return_case FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_return_case
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_return_immutable BEFORE UPDATE OR DELETE ON inventory_return_case
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE FUNCTION warehouse_assert_return(scope uuid, target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE intake inventory_return_case; document inventory_document; line inventory_document_line;
    residual inventory_material_residual; operation inventory_operation; movement inventory_movement;
    outgoing inventory_movement_leg; incoming inventory_movement_leg;
    record jsonb; original jsonb; previous jsonb; snapshot jsonb; command jsonb; canonical text; inspection jsonb;
    released boolean; expected_revision bigint:=0;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO intake FROM inventory_return_case WHERE tenant_id=scope AND id=target;
    IF NOT FOUND THEN RETURN; END IF;
    SELECT * INTO STRICT document FROM inventory_document WHERE tenant_id=scope AND id=target;
    SELECT * INTO STRICT line FROM inventory_document_line WHERE tenant_id=scope AND document_id=target;
    record:=intake.body::jsonb;
    original:=record->'view';
    SELECT * INTO residual FROM inventory_material_residual WHERE tenant_id=scope AND id=intake.source_document_id;
    IF residual.id IS NULL OR residual.purpose<>'RETURN' OR residual.transit_identity_id<>intake.stock_identity_id
        OR residual.target_location_id<>intake.quarantine_location_id
        OR NOT EXISTS(SELECT FROM inventory_material_residual_ack WHERE tenant_id=scope AND residual_id=residual.id)
        OR document.kind<>'RETURN' OR document.source_document_id IS DISTINCT FROM residual.id OR document.source_revision<>2
        OR document.actor_id::text IS DISTINCT FROM original->>'receivedBy'
        OR (record->'intake'->>'sourceDocumentId')::uuid IS DISTINCT FROM residual.id
        OR record->'intake'->>'origin' IS DISTINCT FROM intake.origin
        OR (record->'intake'->>'quarantineLocationId')::uuid IS DISTINCT FROM intake.quarantine_location_id
        OR nullif(btrim(record->'intake'->>'evidenceReference'),'') IS NULL
        OR record->'source'->'dimension' IS DISTINCT FROM (residual.transit_dimension::jsonb ||
            jsonb_build_object('custodianKind','WAREHOUSE','custodianId',intake.quarantine_location_id))
        OR (record->'source'->>'quantity')::bigint IS DISTINCT FROM residual.quantity_base
        OR record->'source'->>'unit' IS DISTINCT FROM residual.base_unit
        OR line.stock_identity_id<>intake.stock_identity_id OR line.quantity_base<>residual.quantity_base
        OR line.base_unit<>residual.base_unit OR line.location_id<>intake.quarantine_location_id
        OR line.custodian_id<>intake.quarantine_location_id OR line.custodian_kind<>'WAREHOUSE'
        OR line.condition<>'QUARANTINE' OR line.legal_owner<>'ISP'
        OR line.sku_id::text IS DISTINCT FROM record->'source'->'dimension'->>'skuId'
        OR line.lot_id::text IS DISTINCT FROM record->'source'->'dimension'->>'lotId'
        OR line.source_line_id IS DISTINCT FROM (record->>'sourceLineId')::uuid
        OR NOT EXISTS(SELECT FROM inventory_document_line WHERE tenant_id=scope AND document_id=residual.id AND id=line.source_line_id)
        OR (original->>'id')::uuid IS DISTINCT FROM intake.id OR original->>'state'<>'RECEIVED_IN_INSPECTION'
        OR (original->>'revision')::bigint<>0 OR original->>'origin' IS DISTINCT FROM intake.origin
        OR (original->>'sourceDocumentId')::uuid IS DISTINCT FROM residual.id
        OR (original->>'stockIdentityId')::uuid IS DISTINCT FROM intake.stock_identity_id
        OR (original->>'skuId')::uuid IS DISTINCT FROM line.sku_id
        OR original->>'lotId' IS DISTINCT FROM line.lot_id::text OR original->>'baseUnit' IS DISTINCT FROM line.base_unit
        OR (original->>'quantityBase')::bigint IS DISTINCT FROM line.quantity_base
        OR (original->>'locationId')::uuid IS DISTINCT FROM intake.quarantine_location_id
        OR original->>'condition'<>'QUARANTINE' OR original->>'legalOwner'<>'ISP' THEN
        RAISE EXCEPTION 'RETURN_ORIGIN_BINDING' USING ERRCODE='23514';
    END IF;
    PERFORM warehouse_assert_material_residual(scope,residual.id);
    FOR operation IN SELECT * FROM inventory_operation WHERE tenant_id=scope AND document_id=target ORDER BY document_revision LOOP
        snapshot:=operation.original_body::jsonb;
        SELECT canonical_payload INTO canonical FROM inventory_command_identity WHERE tenant_id=scope AND id=operation.id;
        command:=canonical::jsonb;
        IF operation.document_revision<>expected_revision OR operation.resource_id<>target OR operation.resource_scope<>'return:'||target
            OR canonical IS NULL OR operation.payload_hash<>encode(sha256(convert_to(canonical,'UTF8')),'hex')
            OR (snapshot->>'revision')::bigint<>expected_revision
            OR snapshot-ARRAY['revision','state','locationId','condition','recordedAt','inspection'] IS DISTINCT FROM
                original-ARRAY['revision','state','locationId','condition','recordedAt','inspection'] THEN
            RAISE EXCEPTION 'RETURN_OPERATION_BINDING' USING ERRCODE='23514';
        END IF;
        IF expected_revision=0 THEN
            IF operation.namespace<>'warehouse.return.receive' OR operation.actor_id<>document.actor_id
                OR command IS DISTINCT FROM record->'intake' OR snapshot IS DISTINCT FROM original
                OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id) THEN
                RAISE EXCEPTION 'RETURN_INTAKE_MUST_NOT_RECEIVE_TWICE' USING ERRCODE='23514';
            END IF;
        ELSE
            inspection:=snapshot->'inspection';
            released:=snapshot->>'state'='ACCEPTED';
            IF operation.namespace<>'warehouse.return.inspect' OR previous->>'state'='ACCEPTED'
                OR command->>'id' IS DISTINCT FROM target::text OR command->'request' IS DISTINCT FROM inspection
                OR (inspection->>'expectedRevision')::bigint<>expected_revision-1
                OR (inspection->>'measuredQuantityBase')::bigint<>line.quantity_base
                OR nullif(btrim(inspection->>'evidenceReference'),'') IS NULL
                OR snapshot->>'condition' IS DISTINCT FROM inspection->>'condition'
                OR snapshot->>'locationId' IS DISTINCT FROM inspection->>'destinationLocationId'
                OR snapshot->>'state' NOT IN ('ACCEPTED','RECEIVED_IN_INSPECTION')
                OR snapshot->>'condition' NOT IN ('SERVICEABLE','QUARANTINE','DAMAGED')
                OR released IS DISTINCT FROM (snapshot->>'condition'='SERVICEABLE' AND snapshot->>'legalOwner'='ISP')
                OR NOT EXISTS(SELECT FROM inventory_location WHERE tenant_id=scope AND id=(snapshot->>'locationId')::uuid
                    AND (CASE WHEN released THEN kind='BIN' ELSE kind='QUARANTINE' END)) THEN
                RAISE EXCEPTION 'RETURN_INSPECTION_BINDING' USING ERRCODE='23514';
            END IF;
            IF line.tracking='SERIAL' AND (inspection->>'observedSerial' IS DISTINCT FROM record->'source'->>'serial'
                OR released AND (inspection->>'resetConfirmed' IS DISTINCT FROM 'true'
                    OR nullif(btrim(inspection->>'resetEvidenceReference'),'') IS NULL)) THEN
                RAISE EXCEPTION 'RETURN_RESET_EVIDENCE_REQUIRED' USING ERRCODE='23514';
            END IF;
            SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id;
            SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
            SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
            IF movement.id IS NULL OR movement.kind<>'RETURN' OR movement.state<>'APPLIED'
                OR movement.document_id<>target OR movement.document_revision<>expected_revision
                OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id)<>1
                OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
                OR outgoing.id IS NULL OR incoming.id IS NULL
                OR (outgoing.stock_identity_id,outgoing.sku_id,outgoing.lot_id,outgoing.quantity_base,outgoing.base_unit,
                    outgoing.document_line_id,outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,
                    outgoing.condition,outgoing.status,outgoing.legal_owner) IS DISTINCT FROM
                    (line.stock_identity_id,line.sku_id,line.lot_id,line.quantity_base,line.base_unit,line.id,
                    (previous->>'locationId')::uuid,(previous->>'locationId')::uuid,'WAREHOUSE'::varchar,
                    previous->>'condition','QUARANTINE'::varchar,line.legal_owner)
                OR (incoming.stock_identity_id,incoming.sku_id,incoming.lot_id,incoming.quantity_base,incoming.base_unit,
                    incoming.document_line_id,incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,
                    incoming.condition,incoming.status,incoming.legal_owner) IS DISTINCT FROM
                    (line.stock_identity_id,line.sku_id,line.lot_id,line.quantity_base,line.base_unit,line.id,
                    (snapshot->>'locationId')::uuid,(snapshot->>'locationId')::uuid,'WAREHOUSE'::varchar,
                    snapshot->>'condition',CASE WHEN released THEN 'AVAILABLE' ELSE 'QUARANTINE' END,line.legal_owner)
                OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=operation.id
                    AND document_id=target AND document_revision=expected_revision AND event_kind='RETURN_RECEIVED') THEN
                RAISE EXCEPTION 'RETURN_EXACT_INSPECTION_POSTING' USING ERRCODE='23514';
            END IF;
        END IF;
        previous:=snapshot;
        expected_revision:=expected_revision+1;
    END LOOP;
    IF expected_revision<>document.revision+1 OR document.state IS DISTINCT FROM
        (CASE WHEN snapshot->>'state'='ACCEPTED' THEN 'ACCEPTED' ELSE 'RECEIVED_IN_INSPECTION' END) THEN
        RAISE EXCEPTION 'RETURN_REVISION_GAP' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_return_final_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE data jsonb; scope uuid; target uuid;
BEGIN
    data:=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    scope:=(data->>'tenant_id')::uuid;
    PERFORM warehouse_assert_deferred_scope(scope);
    target:=CASE TG_TABLE_NAME
        WHEN 'inventory_return_case' THEN (data->>'id')::uuid
        WHEN 'inventory_document' THEN (data->>'id')::uuid
        WHEN 'inventory_command_identity' THEN (SELECT document_id FROM inventory_operation WHERE tenant_id=scope AND id=(data->>'id')::uuid)
        WHEN 'inventory_movement_leg' THEN (SELECT document_id FROM inventory_movement WHERE tenant_id=scope AND id=(data->>'movement_id')::uuid)
        ELSE (data->>'document_id')::uuid END;
    IF target IS NOT NULL THEN PERFORM warehouse_assert_return(scope,target); END IF;
    RETURN NULL;
END $$;
DO $$ DECLARE relation text;
BEGIN
    FOREACH relation IN ARRAY ARRAY['inventory_return_case','inventory_document','inventory_document_line','inventory_operation',
        'inventory_command_identity','inventory_movement','inventory_movement_leg','inventory_outbox'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_return_final AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_return_final_guard()',relation);
    END LOOP;
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT,INSERT ON inventory_return_case TO warehouse_app;
    END IF;
END $$;
