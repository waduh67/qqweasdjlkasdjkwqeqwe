-- Each execution has exactly one genuine source: acknowledged material ISSUE,
-- or the separately witnessed return of an inspected customer-owned repair.
ALTER TABLE inventory_deployment_execution
    ALTER COLUMN receipt_id DROP NOT NULL, ALTER COLUMN receipt_revision DROP NOT NULL, ALTER COLUMN plan_id DROP NOT NULL,
    ADD COLUMN rma_handover_id uuid, ADD COLUMN rma_origin_snapshot jsonb,
    ADD CONSTRAINT warehouse_rma_execution_handover_fk FOREIGN KEY(tenant_id,rma_handover_id) REFERENCES inventory_rma_handover(tenant_id,id),
    ADD CONSTRAINT warehouse_rma_execution_once UNIQUE(tenant_id,rma_handover_id),
    ADD CONSTRAINT warehouse_deployment_source_kind CHECK(
        (rma_handover_id IS NULL AND rma_origin_snapshot IS NULL AND receipt_id IS NOT NULL AND receipt_revision IS NOT NULL AND plan_id IS NOT NULL)
        OR (rma_handover_id IS NOT NULL AND rma_origin_snapshot IS NOT NULL AND receipt_id IS NULL AND receipt_revision IS NULL AND plan_id IS NULL));

CREATE FUNCTION warehouse_capture_rma_execution() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE permit inventory_deployment_authorization; handover inventory_rma_handover; physical inventory_serialized_asset;
    previous inventory_asset_assignment; sku inventory_sku; work work_order; origin_kind text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT permit FROM inventory_deployment_authorization WHERE tenant_id=NEW.tenant_id AND id=NEW.authorization_id;
    IF NEW.rma_handover_id IS NULL THEN
        IF permit.purpose='RETURN_CUSTOMER_RMA' THEN RAISE EXCEPTION 'RMA_HANDOVER_EXECUTION_REQUIRED' USING ERRCODE='23514'; END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT handover FROM inventory_rma_handover WHERE tenant_id=NEW.tenant_id AND id=NEW.rma_handover_id;
    PERFORM warehouse_assert_rma_handover(NEW.tenant_id,handover.id);
    SELECT * INTO STRICT physical FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=permit.asset_id FOR UPDATE;
    SELECT * INTO STRICT previous FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND id=handover.original_assignment_id;
    SELECT * INTO STRICT sku FROM inventory_sku WHERE tenant_id=NEW.tenant_id AND id=physical.sku_id;
    SELECT * INTO STRICT work FROM work_order WHERE tenant_id=NEW.tenant_id AND id=permit.work_order_id FOR UPDATE;
    SELECT document.kind INTO STRICT origin_kind FROM inventory_document_line line JOIN inventory_document document
        ON document.tenant_id=line.tenant_id AND document.id=line.document_id
        WHERE line.tenant_id=NEW.tenant_id AND line.id=physical.origin_document_line_id;
    IF permit.purpose<>'RETURN_CUSTOMER_RMA' OR permit.consumed OR permit.issue_line_id IS NOT NULL OR permit.expected_issue_revision IS NOT NULL
        OR permit.ownership_mode<>'SALE' OR permit.expected_plan_revision<>0
        OR (permit.asset_id,permit.customer_id,permit.work_order_id,permit.actor_id,permit.previous_assignment_id,permit.expected_assignment_revision)
            IS DISTINCT FROM (handover.asset_id,handover.customer_id,handover.work_order_id,handover.technician_id,previous.id,previous.revision)
        OR physical.legal_owner<>'CUSTOMER' OR physical.condition<>'SERVICEABLE' OR physical.status<>'ISSUED'
        OR physical.custody_owner_id<>permit.actor_id OR physical.custody_owner_kind<>'TECHNICIAN' OR physical.revision<>permit.expected_asset_revision
        OR physical.location_id IS DISTINCT FROM (handover.body::jsonb#>>'{request,technicianLocationId}')::uuid
        OR physical.warehouse_admission<>'VERIFIED' OR previous.ended_at IS NULL OR previous.legal_owner<>'CUSTOMER' OR previous.ownership_mode<>'SALE'
        OR sku.state<>'ACTIVE' OR sku.tracking<>'SERIAL' OR sku.base_unit<>'EA'
        OR work.type<>'REPAIR' OR work.status NOT IN ('ASSIGNED','IN_PROGRESS') OR work.customer_id IS DISTINCT FROM handover.customer_id
        OR work.warehouse_revision<>permit.expected_work_order_revision
        OR work.warehouse_revision IS DISTINCT FROM (handover.body::jsonb#>>'{request,workOrderRevision}')::bigint
        OR NOT EXISTS(SELECT FROM work_order_assignee WHERE tenant_id=NEW.tenant_id AND work_order_id=work.id AND technician_id=permit.actor_id)
        OR NOT EXISTS(SELECT FROM inventory_rma_receipt WHERE tenant_id=NEW.tenant_id AND handover_id=handover.id AND actor_id=permit.actor_id)
        OR NOT EXISTS(SELECT FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=handover.id AND state='RECEIVED' AND revision=2)
        OR EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND asset_id=physical.id AND ended_at IS NULL)
        OR NOT EXISTS(SELECT FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND stock_identity_id=physical.id
            AND quantity_base=1 AND base_unit='EA' AND location_id=physical.location_id AND custody_owner_id=permit.actor_id
            AND custody_owner_kind='TECHNICIAN' AND condition='SERVICEABLE' AND legal_owner='CUSTOMER' AND status='ISSUED')
        OR NEW.use_revision<>greatest(
            (SELECT coalesce(max(use_revision),0) FROM inventory_usage_snapshot WHERE tenant_id=NEW.tenant_id AND work_order_id=work.id),
            (SELECT coalesce(max(use_revision),0) FROM inventory_document WHERE tenant_id=NEW.tenant_id AND work_order_id=work.id AND kind='DEPLOYMENT' AND state='POSTED')) THEN
        RAISE EXCEPTION 'RMA_ACKNOWLEDGED_PHYSICAL_SOURCE_REQUIRED' USING ERRCODE='23514'; END IF;
    NEW.rma_origin_snapshot:=jsonb_build_object('asset',to_jsonb(physical),'assignment',to_jsonb(previous),'sku',to_jsonb(sku),'provenance',origin_kind);
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_rma_execution_capture BEFORE INSERT ON inventory_deployment_execution FOR EACH ROW EXECUTE FUNCTION warehouse_capture_rma_execution();

CREATE FUNCTION warehouse_assert_rma_execution(scope uuid, permit_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE permit inventory_deployment_authorization; execution inventory_deployment_execution; handover inventory_rma_handover;
    physical inventory_serialized_asset; previous inventory_asset_assignment; repair inventory_repair_case;
    source jsonb; binding jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO permit FROM inventory_deployment_authorization WHERE tenant_id=scope AND id=permit_id;
    SELECT * INTO execution FROM inventory_deployment_execution WHERE tenant_id=scope AND authorization_id=permit_id;
    SELECT * INTO handover FROM inventory_rma_handover WHERE tenant_id=scope AND id=execution.rma_handover_id;
    IF permit.id IS NULL OR execution.authorization_id IS NULL OR handover.id IS NULL THEN
        RAISE EXCEPTION 'RMA_HANDOVER_EXECUTION_REQUIRED' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_rma_handover(scope,handover.id);
    SELECT * INTO STRICT repair FROM inventory_repair_case WHERE tenant_id=scope AND id=handover.repair_case_id;
    physical:=jsonb_populate_record(NULL::inventory_serialized_asset,execution.rma_origin_snapshot->'asset');
    previous:=jsonb_populate_record(NULL::inventory_asset_assignment,execution.rma_origin_snapshot->'assignment');
    source:=execution.source::jsonb; binding:=execution.binding::jsonb;
    IF permit.purpose<>'RETURN_CUSTOMER_RMA' OR permit.ownership_mode<>'SALE' OR permit.issue_line_id IS NOT NULL
        OR permit.expected_issue_revision IS NOT NULL OR permit.expected_plan_revision<>0
        OR execution.receipt_id IS NOT NULL OR execution.receipt_revision IS NOT NULL OR execution.plan_id IS NOT NULL
        OR physical.id IS DISTINCT FROM permit.asset_id OR physical.revision IS DISTINCT FROM permit.expected_asset_revision
        OR (permit.asset_id,permit.customer_id,permit.work_order_id,permit.actor_id,permit.previous_assignment_id,permit.expected_assignment_revision)
            IS DISTINCT FROM (handover.asset_id,handover.customer_id,handover.work_order_id,handover.technician_id,previous.id,previous.revision)
        OR previous.id IS DISTINCT FROM handover.original_assignment_id OR previous.ended_at IS NULL OR previous.legal_owner<>'CUSTOMER'
        OR repair.state<>'CLOSED' OR repair.revision<>2 OR repair.asset_id<>permit.asset_id OR repair.legal_owner<>'CUSTOMER'
        OR (source->>'handoverId')::uuid IS DISTINCT FROM handover.id OR source->>'handoverRevision' IS DISTINCT FROM '2'
        OR (source->>'repairCaseId')::uuid IS DISTINCT FROM repair.id OR (source->>'repairRevision')::bigint IS DISTINCT FROM repair.revision
        OR (source->>'originalAssignmentId')::uuid IS DISTINCT FROM previous.id
        OR (source->>'originalAssignmentRevision')::bigint IS DISTINCT FROM previous.revision
        OR source->'custody' IS DISTINCT FROM jsonb_build_object('skuId',physical.sku_id,'stockIdentityId',physical.id,'lotId',NULL,
            'locationId',physical.location_id,'custodianId',permit.actor_id,'custodianKind','TECHNICIAN','condition','SERVICEABLE','legalOwner','CUSTOMER')
        OR source->>'serial' IS DISTINCT FROM physical.canonical_serial
        OR source->'model' IS DISTINCT FROM execution.rma_origin_snapshot->'sku'->'model'
        OR (source->>'createsOnu')::boolean IS DISTINCT FROM ((execution.rma_origin_snapshot->'sku'->>'category') IN ('ONU','ONT'))
        OR source->>'provenance' IS DISTINCT FROM execution.rma_origin_snapshot->>'provenance'
        OR binding->'repairReturn' IS DISTINCT FROM jsonb_build_object('originalAssignmentId',previous.id,'originalCustomerId',handover.customer_id,
            'repairCaseId',repair.id,'repairRevision',repair.revision,'returnHandoverId',handover.id)
        OR NOT EXISTS(SELECT FROM inventory_rma_receipt WHERE tenant_id=scope AND handover_id=handover.id AND actor_id=permit.actor_id) THEN
        RAISE EXCEPTION 'RMA_EXECUTION_SOURCE_BINDING' USING ERRCODE='23514'; END IF;
END $$;

DO $$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure);
    anchor:='WHEN ''INSTALL'', ''REPLACE'', ''RETURN_CUSTOMER_RMA'' THEN';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'deployment source purpose entry changed'; END IF;
    definition:=replace(definition,anchor,'WHEN ''INSTALL'', ''REPLACE'' THEN');
    anchor:='WHEN ''REMOVE'' THEN';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'deployment removal source entry changed'; END IF;
    definition:=replace(definition,anchor,$patch$WHEN 'RETURN_CUSTOMER_RMA' THEN
            PERFORM warehouse_assert_rma_execution(scope,permit.id);
        WHEN 'REMOVE' THEN$patch$);
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_deployment_result(uuid,uuid)'::regprocedure);
    anchor:='permit.purpose NOT IN (''INSTALL'',''REPLACE'')';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'deployment result purpose entry changed'; END IF;
    definition:=replace(definition,anchor,'permit.purpose NOT IN (''INSTALL'',''REPLACE'',''RETURN_CUSTOMER_RMA'')');
    anchor:='IF NOT EXISTS(SELECT FROM inventory_material_receipt receipt';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'deployment receipt entry changed'; END IF;
    definition:=replace(definition,anchor,$patch$IF permit.purpose='RETURN_CUSTOMER_RMA' THEN
        PERFORM warehouse_assert_rma_execution(scope,permit.id);
    ELSIF NOT EXISTS(SELECT FROM inventory_material_receipt receipt$patch$);
    anchor:='outcome.result::jsonb#>>''{assignment,legalOwner}'' IS DISTINCT FROM ''ISP''';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'deployment initial title entry changed'; END IF;
    definition:=replace(definition,anchor,'outcome.result::jsonb#>>''{assignment,legalOwner}'' IS DISTINCT FROM (CASE permit.purpose WHEN ''RETURN_CUSTOMER_RMA'' THEN ''CUSTOMER'' ELSE ''ISP'' END)');
    anchor:='source_line_id=permit.issue_line_id AND document_id=permit.operation_id';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'deployment source line entry changed'; END IF;
    definition:=replace(definition,anchor,'source_line_id=coalesce(permit.issue_line_id,execution.rma_handover_id) AND document_id=permit.operation_id');
    anchor:='incoming.legal_owner<>''ISP''';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'deployment incoming title entry changed'; END IF;
    definition:=replace(definition,anchor,'incoming.legal_owner<>(CASE permit.purpose WHEN ''RETURN_CUSTOMER_RMA'' THEN ''CUSTOMER'' ELSE ''ISP'' END)');
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_deployment_document(uuid,uuid)'::regprocedure);
    anchor:='1,permit.asset_id,permit.issue_line_id,';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'deployment document source entry changed'; END IF;
    definition:=replace(definition,anchor,'1,permit.asset_id,coalesce(permit.issue_line_id,execution.rma_handover_id),');
    anchor:='''TECHNICIAN''::varchar,''SERVICEABLE''::varchar,''ISP''::varchar';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'deployment document title entry changed'; END IF;
    definition:=replace(definition,anchor,'''TECHNICIAN''::varchar,''SERVICEABLE''::varchar,(CASE permit.purpose WHEN ''RETURN_CUSTOMER_RMA'' THEN ''CUSTOMER'' ELSE ''ISP'' END)::varchar');
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_asset_expected_owner(uuid,uuid)'::regprocedure);
    anchor:='RETURN ''ISP'';';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'initial asset owner entry changed'; END IF;
    definition:=replace(definition,anchor,$patch$
    IF EXISTS(SELECT FROM inventory_deployment_authorization permit JOIN inventory_deployment_execution execution
        ON execution.tenant_id=permit.tenant_id AND execution.authorization_id=permit.id
        WHERE permit.tenant_id=scope AND permit.operation_id=$2 AND permit.purpose='RETURN_CUSTOMER_RMA' AND execution.rma_handover_id IS NOT NULL) THEN
        PERFORM warehouse_assert_rma_execution(scope,(SELECT id FROM inventory_deployment_authorization WHERE tenant_id=scope AND operation_id=$2));
        RETURN 'CUSTOMER';
    END IF;
    RETURN 'ISP';$patch$);
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_current_asset_title(uuid,uuid)'::regprocedure);
    anchor:='SELECT * INTO handover FROM inventory_asset_handover WHERE tenant_id=scope AND inventory_asset_handover.assignment_id=$2;';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'current title initial owner entry changed'; END IF;
    definition:=replace(definition,anchor,$patch$
    IF assignment.purpose='RETURN_CUSTOMER_RMA' THEN
        PERFORM warehouse_assert_rma_execution(scope,(SELECT id FROM inventory_deployment_authorization WHERE tenant_id=scope AND operation_id=$2));
        owner:='CUSTOMER';
    END IF;
    $patch$||anchor);
    EXECUTE definition;
END $$;
