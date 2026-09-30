DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_deployment_result(uuid,uuid)'::regprocedure);
    IF strpos(definition,'binding->>''purpose'' IS DISTINCT FROM ''INSTALL'' OR permit.purpose<>''INSTALL''')=0
        OR strpos(definition,'OR assignment.ended_at IS NOT NULL')=0
        OR strpos(definition,'IF NOT EXISTS(SELECT FROM inventory_balance_projection')=0 THEN
        RAISE EXCEPTION 'deployment lifecycle clauses missing';
    END IF;
    definition:=replace(definition,'binding->>''purpose'' IS DISTINCT FROM ''INSTALL'' OR permit.purpose<>''INSTALL''',
        'binding->>''purpose'' IS DISTINCT FROM permit.purpose OR permit.purpose NOT IN (''INSTALL'',''REPLACE'') OR (binding->>''previousAssignmentId'')::uuid IS DISTINCT FROM permit.previous_assignment_id OR (binding->>''previousAssignmentRevision'')::bigint IS DISTINCT FROM permit.expected_assignment_revision');
    definition:=replace(definition,'OR assignment.ended_at IS NOT NULL',
        'OR (assignment.ended_at IS NOT NULL AND NOT EXISTS(SELECT FROM inventory_asset_removal WHERE tenant_id=scope AND assignment_id=assignment.id AND removed_at=assignment.ended_at))');
    definition:=replace(definition,'AND retired_at IS NULL','AND retired_at IS NOT DISTINCT FROM assignment.ended_at');
    definition:=replace(definition,'IF NOT EXISTS(SELECT FROM inventory_balance_projection',
        'IF assignment.ended_at IS NULL AND (NOT EXISTS(SELECT FROM inventory_balance_projection');
    definition:=replace(definition,'AND condition=''SERVICEABLE'') THEN', 'AND condition=''SERVICEABLE'')) THEN');
    EXECUTE definition;
    definition:=pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure);
    EXECUTE replace(definition,'permit.purpose=''INSTALL'' AND EXISTS', 'permit.purpose IN (''INSTALL'',''REPLACE'') AND EXISTS');
END $$;

CREATE OR REPLACE FUNCTION warehouse_assert_current_asset_title(scope uuid, assignment_id uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE assignment inventory_asset_assignment; handover inventory_asset_handover; transfer inventory_asset_title_transfer;
    owner text:='ISP'; revision bigint:=0; title_revision bigint:=0; asset inventory_serialized_asset;
    base_asset_revision bigint; initial jsonb; removal inventory_asset_removal; origin inventory_asset_removal_origin;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=scope AND id=$2;
    IF NOT FOUND OR assignment.warehouse_admission<>'VERIFIED' THEN
        RAISE EXCEPTION 'TITLE_ACTIVE_ASSIGNMENT_REQUIRED' USING ERRCODE='23514'; END IF;
    IF assignment.ended_at IS NOT NULL THEN
        SELECT * INTO removal FROM inventory_asset_removal WHERE tenant_id=scope AND inventory_asset_removal.assignment_id=$2;
        IF removal.id IS NULL THEN RAISE EXCEPTION 'TITLE_REMOVAL_REQUIRED' USING ERRCODE='23514'; END IF;
        SELECT * INTO origin FROM inventory_asset_removal_origin WHERE tenant_id=scope AND removal_id=removal.id;
        PERFORM warehouse_assert_asset_removal(scope,removal.id);
        assignment:=jsonb_populate_record(NULL::inventory_asset_assignment,origin.assignment_snapshot);
    END IF;
    SELECT history.snapshot INTO initial FROM inventory_asset_assignment_history history
        WHERE history.tenant_id=scope AND history.assignment_id=$2 AND history.revision=0;
    SELECT expected_asset_revision+1 INTO base_asset_revision FROM inventory_deployment_authorization permit
        JOIN inventory_deployment_result result ON result.tenant_id=permit.tenant_id AND result.authorization_id=permit.id
        WHERE result.tenant_id=scope AND result.assignment_id=assignment.id;
    IF initial IS NULL OR base_asset_revision IS NULL THEN RAISE EXCEPTION 'TITLE_DEPLOYMENT_REQUIRED' USING ERRCODE='23514'; END IF;
    SELECT * INTO handover FROM inventory_asset_handover WHERE tenant_id=scope AND inventory_asset_handover.assignment_id=$2;
    IF FOUND THEN
        PERFORM warehouse_assert_asset_handover(scope,handover.id);
        revision:=1;
        IF handover.ownership_mode='SALE' THEN owner:='CUSTOMER'; title_revision:=1; END IF;
    END IF;
    FOR transfer IN SELECT * FROM inventory_asset_title_transfer WHERE tenant_id=scope AND inventory_asset_title_transfer.assignment_id=$2
        ORDER BY inventory_asset_title_transfer.title_revision LOOP
        PERFORM warehouse_assert_title_request(scope,transfer.request_id);
        PERFORM warehouse_assert_title_transfer(scope,transfer.id);
        IF transfer.source_owner<>owner OR transfer.source_assignment_revision<>revision OR transfer.source_title_revision<>title_revision
            OR transfer.source_asset_revision<>base_asset_revision+title_revision THEN
            RAISE EXCEPTION 'TITLE_TRANSFER_CHAIN_GAP' USING ERRCODE='23514'; END IF;
        owner:=transfer.target_owner; revision:=transfer.assignment_revision; title_revision:=transfer.title_revision;
    END LOOP;
    IF removal.id IS NULL THEN
        SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=scope AND id=assignment.asset_id;
    ELSE
        asset:=jsonb_populate_record(NULL::inventory_serialized_asset,origin.asset_snapshot);
    END IF;
    IF assignment.legal_owner<>owner OR assignment.revision<>revision OR asset.legal_owner<>owner
        OR asset.revision<>base_asset_revision+title_revision OR asset.status<>'CUSTOMER_INSTALLED'
        OR asset.condition<>'SERVICEABLE' OR asset.custody_owner_kind<>'CUSTOMER' OR asset.custody_owner_id<>assignment.customer_id
        OR to_jsonb(assignment)-ARRAY['revision','legal_owner'] IS DISTINCT FROM
            to_jsonb(jsonb_populate_record(NULL::inventory_asset_assignment,initial))-ARRAY['revision','legal_owner'] THEN
        RAISE EXCEPTION 'TITLE_CURRENT_OWNER_POSITION_BINDING' USING ERRCODE='23514'; END IF;
    IF removal.id IS NULL AND ((SELECT count(*) FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=asset.id AND quantity_base>0)<>1
        OR NOT EXISTS(SELECT FROM inventory_balance_projection WHERE tenant_id=scope AND stock_identity_id=asset.id
            AND quantity_base=1 AND base_unit='EA' AND legal_owner=owner AND status='CUSTOMER_INSTALLED' AND condition='SERVICEABLE'
            AND location_id=asset.location_id AND custody_owner_kind='CUSTOMER' AND custody_owner_id=assignment.customer_id)) THEN
        RAISE EXCEPTION 'TITLE_CURRENT_OWNER_POSITION_BINDING' USING ERRCODE='23514'; END IF;
END $$;

CREATE FUNCTION warehouse_capture_asset_removal() RETURNS trigger LANGUAGE plpgsql SET TimeZone='UTC' AS $$
DECLARE assignment inventory_asset_assignment; asset inventory_serialized_asset; signature jsonb; permit inventory_deployment_authorization;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=NEW.tenant_id AND id=NEW.assignment_id FOR UPDATE;
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id FOR UPDATE;
    PERFORM warehouse_assert_current_asset_title(NEW.tenant_id,NEW.assignment_id);
    SELECT to_jsonb(signed) INTO signature FROM wo_signature signed JOIN evidence_object_registry registry
        ON registry.tenant_id=signed.tenant_id AND registry.revision_id=signed.id
        WHERE signed.tenant_id=NEW.tenant_id AND signed.id=NEW.evidence_id AND signed.work_order_id=NEW.work_order_id
        AND signed.sha256=NEW.evidence_digest AND signed.revision_state='COMMITTED' AND signed.purge_state='ACTIVE'
        AND registry.state='COMMITTED' AND registry.purge_state='ACTIVE' AND registry.expected_sha256=signed.sha256
        AND registry.object_key=signed.storage_key FOR SHARE OF signed,registry;
    IF NEW.created_xid<>pg_current_xact_id() OR assignment.id IS NULL OR asset.id IS NULL OR signature IS NULL
        OR assignment.ended_at IS NOT NULL OR assignment.asset_id<>asset.id OR assignment.customer_id<>NEW.customer_id
        OR assignment.revision<>NEW.source_assignment_revision OR asset.revision<>NEW.source_asset_revision
        OR asset.status<>'CUSTOMER_INSTALLED' OR asset.condition<>'SERVICEABLE' OR asset.legal_owner<>NEW.legal_owner
        OR asset.location_id<>NEW.source_location_id OR asset.custody_owner_id<>NEW.customer_id
        OR NEW.removed_at<assignment.started_at OR NEW.removed_at<statement_timestamp()-interval '30 seconds'
        OR NEW.removed_at>clock_timestamp() OR NOT EXISTS(SELECT FROM work_order wo WHERE wo.tenant_id=NEW.tenant_id AND wo.id=NEW.work_order_id
            AND wo.customer_id=NEW.customer_id AND wo.warehouse_revision=NEW.work_order_revision AND wo.status IN ('ASSIGNED','IN_PROGRESS')
            AND wo.type=CASE WHEN NEW.authorization_id IS NULL THEN 'DISMANTLE' ELSE 'MIGRATION' END
            AND EXISTS(SELECT FROM work_order_assignee WHERE tenant_id=wo.tenant_id AND work_order_id=wo.id AND technician_id=NEW.actor_id)) THEN
        RAISE EXCEPTION 'ASSET_REMOVAL_CURRENT_SOURCE' USING ERRCODE='23514'; END IF;
    IF NEW.authorization_id IS NOT NULL THEN
        SELECT * INTO permit FROM inventory_deployment_authorization WHERE tenant_id=NEW.tenant_id AND id=NEW.authorization_id;
        IF permit.id IS NULL OR NOT permit.consumed OR permit.purpose<>'REPLACE' OR permit.previous_assignment_id<>assignment.id
            OR permit.expected_assignment_revision<>assignment.revision OR permit.asset_id<>NEW.replacement_asset_id
            OR permit.operation_id<>NEW.replacement_assignment_id OR permit.work_order_id<>NEW.work_order_id
            OR permit.customer_id<>NEW.customer_id OR permit.actor_id<>NEW.actor_id THEN
            RAISE EXCEPTION 'ASSET_REMOVAL_REPLACEMENT_AUTHORIZATION' USING ERRCODE='23514'; END IF;
    END IF;
    INSERT INTO inventory_asset_removal_origin VALUES(NEW.tenant_id,NEW.id,to_jsonb(assignment),to_jsonb(asset),
        warehouse_asset_origin_snapshot(NEW.tenant_id,NEW.asset_id),signature);
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_removal_capture AFTER INSERT ON inventory_asset_removal FOR EACH ROW EXECUTE FUNCTION warehouse_capture_asset_removal();
