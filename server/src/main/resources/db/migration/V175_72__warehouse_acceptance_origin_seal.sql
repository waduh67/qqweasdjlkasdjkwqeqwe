CREATE TABLE inventory_asset_acceptance_origin (
    tenant_id uuid NOT NULL, handover_id uuid NOT NULL, origin_snapshot jsonb NOT NULL,
    assignment_snapshot jsonb NOT NULL, position_snapshot jsonb NOT NULL, signature_snapshot jsonb NOT NULL,
    acceptance_hash text NOT NULL CHECK (acceptance_hash ~ '^[0-9a-f]{64}$'), created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,handover_id), FOREIGN KEY(tenant_id,handover_id) REFERENCES inventory_asset_handover(tenant_id,id)
);
ALTER TABLE inventory_asset_acceptance_origin ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_asset_acceptance_origin FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_asset_acceptance_origin
    USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_asset_acceptance_origin FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();

CREATE FUNCTION warehouse_asset_origin_snapshot(scope uuid, asset_id uuid) RETURNS jsonb LANGUAGE plpgsql SET TimeZone='UTC' AS $$
DECLARE result jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM warehouse_assert_verified_segment(scope,asset_id,true);
    SELECT jsonb_build_object('assetId',asset.id,'skuId',asset.sku_id,'originLine',to_jsonb(line),
        'receiptId',document.id,'receiptCode',document.code,'receiptSnapshot',intake.snapshot::jsonb,
        'claims',(SELECT jsonb_agg(to_jsonb(claim) ORDER BY claim.identity_type,claim.canonical_value)
            FROM inventory_identity_claim claim WHERE claim.tenant_id=scope AND claim.admitted_asset_id=asset.id)) INTO result
    FROM inventory_serialized_asset asset JOIN inventory_document_line line ON line.tenant_id=asset.tenant_id AND line.id=asset.origin_document_line_id
    JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
    JOIN inventory_receipt_intake intake ON intake.tenant_id=document.tenant_id AND intake.id=document.id
    WHERE asset.tenant_id=scope AND asset.id=$2 AND asset.warehouse_admission='VERIFIED'
        AND document.kind='RECEIPT' AND document.state IN ('PUTAWAY','CLOSED');
    IF result IS NULL THEN RAISE EXCEPTION 'TITLE_VERIFIED_RECEIPT_ORIGIN_REQUIRED' USING ERRCODE='23514'; END IF;
    RETURN result;
END $$;

CREATE FUNCTION warehouse_assert_asset_intent(scope uuid, asset_id uuid, intent text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE origin jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    origin:=warehouse_asset_origin_snapshot(scope,asset_id);
    IF intent NOT IN ('LOAN','SALE') OR NOT EXISTS(SELECT FROM jsonb_array_elements(origin#>'{receiptSnapshot,lines}') item
        WHERE item#>>'{sku,id}'=origin->>'skuId' AND jsonb_exists(item#>'{sku,allowedOwnershipModes}',intent)) THEN
        RAISE EXCEPTION 'TITLE_INTENT_NOT_IN_RECEIPT_SNAPSHOT' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_capture_acceptance_origin() RETURNS trigger LANGUAGE plpgsql SET TimeZone='UTC' AS $$
DECLARE handover inventory_asset_handover; assignment inventory_asset_assignment; asset inventory_serialized_asset;
    permit inventory_deployment_authorization; body jsonb; expected_assignment jsonb; position jsonb; signature jsonb; origin jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT handover FROM inventory_asset_handover WHERE tenant_id=NEW.tenant_id AND id=NEW.handover_id;
    SELECT * INTO STRICT assignment FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND id=handover.assignment_id FOR UPDATE;
    SELECT * INTO STRICT asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=handover.asset_id FOR UPDATE;
    SELECT * INTO STRICT permit FROM inventory_deployment_authorization WHERE tenant_id=NEW.tenant_id AND id=NEW.authorization_id;
    PERFORM warehouse_assert_asset_intent(NEW.tenant_id,asset.id,handover.ownership_mode);
    origin:=warehouse_asset_origin_snapshot(NEW.tenant_id,asset.id);
    origin:=origin||jsonb_build_object('authorizationId',permit.id,'issueLineId',permit.issue_line_id,
        'deployment',(SELECT to_jsonb(result)-'result' FROM inventory_deployment_result result WHERE tenant_id=NEW.tenant_id AND authorization_id=permit.id),
        'execution',(SELECT to_jsonb(execution) FROM inventory_deployment_execution execution WHERE tenant_id=NEW.tenant_id AND authorization_id=permit.id));
    body:=NEW.snapshot::jsonb;
    position:=jsonb_build_object('assetRevision',asset.revision,'dimension',jsonb_build_object('skuId',asset.sku_id,'stockIdentityId',asset.id,
        'lotId',NULL,'locationId',asset.location_id,'custodianId',asset.custody_owner_id,'custodianKind',asset.custody_owner_kind,
        'condition',asset.condition,'legalOwner',asset.legal_owner));
    SELECT jsonb_build_object('id',signed.id,'reference',signed.storage_key,'digest',signed.sha256,'receiverLabel',signed.signer_name,
        'receivedAt',signed.receipt_at) INTO signature FROM wo_signature signed JOIN evidence_object_registry registry
        ON registry.tenant_id=signed.tenant_id AND registry.revision_id=signed.id WHERE signed.tenant_id=NEW.tenant_id
        AND signed.id=handover.evidence_id AND signed.work_order_id=handover.work_order_id
        AND signed.revision_state='COMMITTED' AND signed.purge_state='ACTIVE' AND registry.state='COMMITTED' AND registry.purge_state='ACTIVE'
        AND registry.object_key=signed.storage_key AND registry.expected_sha256=signed.sha256 FOR SHARE OF signed,registry;
    SELECT result.result::jsonb->'assignment' INTO expected_assignment FROM inventory_deployment_result result
        WHERE tenant_id=NEW.tenant_id AND authorization_id=permit.id;
    expected_assignment:=expected_assignment||jsonb_build_object('revision',handover.assignment_revision+1,
        'legalOwner',CASE handover.ownership_mode WHEN 'SALE' THEN 'CUSTOMER' ELSE 'ISP' END,'handoverState','ACCEPTED',
        'titleRevision',CASE handover.ownership_mode WHEN 'SALE' THEN 1 ELSE 0 END,
        'acceptedHandover',jsonb_build_object('handoverId',handover.id,'assignmentId',assignment.id,'assetId',asset.id,
            'workOrderId',handover.work_order_id,'customerId',handover.customer_id,'evidenceId',handover.evidence_id,
            'acceptedAt',body#>'{assignment,acceptedHandover,acceptedAt}'));
    IF NEW.created_xid<>pg_current_xact_id() OR assignment.revision<>handover.assignment_revision OR handover.assignment_revision<>0
        OR NEW.source_asset_revision<>asset.revision OR asset.revision<>permit.expected_asset_revision+1 OR NEW.source_title_revision<>0
        OR asset.legal_owner<>'ISP' OR asset.status<>'CUSTOMER_INSTALLED' OR asset.custody_owner_id<>handover.customer_id
        OR body->'position' IS DISTINCT FROM position OR body->'assignment' IS DISTINCT FROM expected_assignment
        OR signature IS NULL OR body->'signature'-'receivedAt' IS DISTINCT FROM signature-'receivedAt'
        OR (body#>>'{signature,receivedAt}')::timestamptz IS DISTINCT FROM (signature->>'receivedAt')::timestamptz
        OR (body#>>'{assignment,acceptedHandover,acceptedAt}')::timestamptz IS DISTINCT FROM handover.accepted_at
        OR (body->>'sourceAssignmentRevision')::bigint IS DISTINCT FROM handover.assignment_revision
        OR (body->>'sourceTitleRevision')::bigint IS DISTINCT FROM NEW.source_title_revision
        OR (body->>'operationId')::uuid IS DISTINCT FROM NEW.operation_id OR body->>'operationKey' IS DISTINCT FROM NEW.operation_key
        OR body->>'code' IS DISTINCT FROM (SELECT code FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.operation_id)
        OR body->>'payloadHash' IS DISTINCT FROM NEW.payload_hash
        OR (body->>'authorityEpoch')::bigint IS DISTINCT FROM NEW.authority_epoch
        OR (body->>'cutoverEpoch')::bigint IS DISTINCT FROM NEW.cutover_epoch
        OR (body#>>'{workOrder,revision}')::bigint IS DISTINCT FROM NEW.work_order_revision
        OR body#>>'{workOrder,code}' IS DISTINCT FROM (SELECT code FROM work_order WHERE tenant_id=NEW.tenant_id AND id=handover.work_order_id)
        OR body#>>'{workOrder,senderLabel}' IS DISTINCT FROM (SELECT name FROM app_user WHERE tenant_id=NEW.tenant_id AND id=handover.actor_id)
        OR body#>>'{customer,label}' IS DISTINCT FROM (SELECT name FROM customer WHERE tenant_id=NEW.tenant_id AND id=handover.customer_id)
        OR body#>>'{customer,status}' IS DISTINCT FROM (SELECT status FROM customer WHERE tenant_id=NEW.tenant_id AND id=handover.customer_id) THEN
        RAISE EXCEPTION 'TITLE_ACCEPTANCE_EXACT_SOURCE_SNAPSHOT' USING ERRCODE='23514';
    END IF;
    INSERT INTO inventory_asset_acceptance_origin(tenant_id,handover_id,origin_snapshot,assignment_snapshot,position_snapshot,signature_snapshot,acceptance_hash)
        VALUES(NEW.tenant_id,NEW.handover_id,origin,(SELECT history.snapshot FROM inventory_asset_assignment_history history
            WHERE tenant_id=NEW.tenant_id AND assignment_id=assignment.id AND revision=handover.assignment_revision),
            position,signature,encode(sha256(convert_to(NEW.snapshot,'UTF8')),'hex'));
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_acceptance_origin AFTER INSERT ON inventory_asset_acceptance FOR EACH ROW EXECUTE FUNCTION warehouse_capture_acceptance_origin();

CREATE FUNCTION warehouse_assert_asset_handover(scope uuid, handover_id uuid) RETURNS void LANGUAGE plpgsql SET TimeZone='UTC' AS $$
DECLARE handover inventory_asset_handover; acceptance inventory_asset_acceptance; seal inventory_asset_acceptance_origin; origin jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    PERFORM warehouse_assert_asset_handover_v69(scope,handover_id);
    SELECT * INTO handover FROM inventory_asset_handover WHERE tenant_id=scope AND id=$2;
    SELECT * INTO acceptance FROM inventory_asset_acceptance WHERE tenant_id=scope AND inventory_asset_acceptance.handover_id=$2;
    SELECT * INTO seal FROM inventory_asset_acceptance_origin WHERE tenant_id=scope AND inventory_asset_acceptance_origin.handover_id=$2;
    origin:=warehouse_asset_origin_snapshot(scope,handover.asset_id);
    origin:=origin||jsonb_build_object('authorizationId',acceptance.authorization_id,'issueLineId',
        (SELECT issue_line_id FROM inventory_deployment_authorization WHERE tenant_id=scope AND id=acceptance.authorization_id),
        'deployment',(SELECT to_jsonb(result)-'result' FROM inventory_deployment_result result WHERE tenant_id=scope AND authorization_id=acceptance.authorization_id),
        'execution',(SELECT to_jsonb(execution) FROM inventory_deployment_execution execution WHERE tenant_id=scope AND authorization_id=acceptance.authorization_id));
    IF seal.handover_id IS NULL OR seal.origin_snapshot IS DISTINCT FROM origin
        OR seal.acceptance_hash IS DISTINCT FROM encode(sha256(convert_to(acceptance.snapshot,'UTF8')),'hex')
        OR seal.position_snapshot IS DISTINCT FROM acceptance.snapshot::jsonb->'position'
        OR seal.assignment_snapshot IS DISTINCT FROM (SELECT history.snapshot FROM inventory_asset_assignment_history history
            WHERE tenant_id=scope AND assignment_id=handover.assignment_id AND revision=handover.assignment_revision) THEN
        RAISE EXCEPTION 'TITLE_ACCEPTANCE_RECONCILIATION_REQUIRED' USING ERRCODE='23514';
    END IF;
    PERFORM warehouse_assert_asset_intent(scope,handover.asset_id,handover.ownership_mode);
END $$;

CREATE FUNCTION warehouse_acceptance_origin_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.created_xid<>pg_current_xact_id() OR NOT EXISTS(SELECT FROM inventory_asset_acceptance
        WHERE tenant_id=NEW.tenant_id AND handover_id=NEW.handover_id AND created_xid=pg_current_xact_id()) THEN
        RAISE EXCEPTION 'TITLE_ORIGIN_REQUIRES_CURRENT_ACCEPTANCE' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_acceptance_origin_insert BEFORE INSERT ON inventory_asset_acceptance_origin
    FOR EACH ROW EXECUTE FUNCTION warehouse_acceptance_origin_insert_guard();
