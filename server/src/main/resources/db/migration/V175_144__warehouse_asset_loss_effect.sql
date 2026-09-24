CREATE TABLE inventory_asset_loss_effect (
    tenant_id uuid NOT NULL, request_id uuid NOT NULL, approval_id uuid NOT NULL, posting_operation_id uuid NOT NULL,
    assignment_id uuid NOT NULL, closed_at timestamptz NOT NULL,
    source_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,request_id), UNIQUE(tenant_id,approval_id), UNIQUE(tenant_id,posting_operation_id), UNIQUE(tenant_id,assignment_id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_asset_loss_request(tenant_id,id),
    FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,posting_operation_id) REFERENCES inventory_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE customer_asset_loss (
    tenant_id uuid NOT NULL, request_id uuid NOT NULL, operation_id uuid NOT NULL, assignment_id uuid NOT NULL,
    episode_id uuid NOT NULL, onu_id uuid, response text NOT NULL, retired_at timestamptz NOT NULL,
    PRIMARY KEY(tenant_id,request_id), UNIQUE(tenant_id,assignment_id), UNIQUE(tenant_id,episode_id), UNIQUE(tenant_id,onu_id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_asset_loss_effect(tenant_id,request_id),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY(tenant_id,assignment_id) REFERENCES inventory_asset_assignment(tenant_id,id),
    FOREIGN KEY(tenant_id,episode_id) REFERENCES customer_asset_installation(tenant_id,id),
    FOREIGN KEY(tenant_id,onu_id) REFERENCES onu(tenant_id,id)
);
CREATE TABLE inventory_asset_loss_permit_retirement (
    tenant_id uuid NOT NULL, authorization_id uuid NOT NULL, request_id uuid NOT NULL,
    permit_snapshot jsonb NOT NULL, asset_snapshot jsonb NOT NULL, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    PRIMARY KEY(tenant_id,authorization_id),
    FOREIGN KEY(tenant_id,authorization_id) REFERENCES inventory_deployment_authorization(tenant_id,id),
    FOREIGN KEY(tenant_id,request_id) REFERENCES inventory_asset_loss_effect(tenant_id,request_id)
);
ALTER TABLE customer_onu_episode_event ADD COLUMN loss_request_id uuid,
    ADD CONSTRAINT customer_onu_loss_event_kind CHECK(loss_request_id IS NULL OR (kind='RETIRED' AND removal_id IS NULL)),
    ADD CONSTRAINT customer_onu_loss_event_source FOREIGN KEY(tenant_id,loss_request_id)
        REFERENCES customer_asset_loss(tenant_id,request_id) DEFERRABLE INITIALLY DEFERRED;
DO $migration$ DECLARE table_name text; item record;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_loss_effect','customer_asset_loss','inventory_asset_loss_permit_retirement'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)
            WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
        IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
            EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I TO warehouse_app',table_name);
        END IF;
    END LOOP;
    -- Both witnessed removal and independently approved loss reference a real operation.
    -- The deferred source guard below still requires exactly one of those two owners.
    FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='fulfillment_asset_outbox'::regclass
        AND confrelid='inventory_asset_removal'::regclass LOOP
        EXECUTE format('ALTER TABLE fulfillment_asset_outbox DROP CONSTRAINT %I',item.conname);
    END LOOP;
    ALTER TABLE fulfillment_asset_outbox ADD CONSTRAINT fulfillment_asset_source_operation
        FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id);
END $migration$;

CREATE FUNCTION warehouse_asset_loss_source_matches(scope uuid,target uuid) RETURNS boolean LANGUAGE plpgsql AS $function$
DECLARE request inventory_asset_loss_request;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO request FROM inventory_asset_loss_request WHERE tenant_id=scope AND id=target;
    IF NOT FOUND THEN RETURN false; END IF;
    RETURN request.source_snapshot=warehouse_asset_loss_current_source(scope,request.assignment_id,request.handover_id,
        (request.snapshot::jsonb#>>'{input,evidenceId}')::uuid,(request.snapshot::jsonb#>>'{input,destinationLocationId}')::uuid);
EXCEPTION WHEN check_violation THEN RETURN false;
END $function$;

CREATE FUNCTION warehouse_capture_asset_loss_effect() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE request inventory_asset_loss_request; document inventory_document; approval inventory_approval; source jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT request FROM inventory_asset_loss_request WHERE tenant_id=NEW.tenant_id AND id=NEW.request_id;
    SELECT * INTO document FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=request.id;
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=NEW.tenant_id AND id=NEW.approval_id;
    source:=warehouse_asset_loss_current_source(NEW.tenant_id,request.assignment_id,request.handover_id,
        (request.snapshot::jsonb#>>'{input,evidenceId}')::uuid,(request.snapshot::jsonb#>>'{input,destinationLocationId}')::uuid);
    IF NEW.created_xid<>pg_current_xact_id() OR source IS DISTINCT FROM request.source_snapshot
        OR NEW.assignment_id<>request.assignment_id OR document.state<>'DRAFT' OR document.revision<>0
        OR document.kind<>'ASSET_LOSS' OR approval.id IS NULL OR approval.status<>'APPROVED'
        OR approval.source_document_id<>request.id OR approval.source_document_revision<>0 OR approval.business_action<>'LOSS'
        OR approval.source_snapshot::jsonb->'assetLoss' IS DISTINCT FROM request.snapshot::jsonb
        OR NEW.closed_at<(source#>>'{assignment,started_at}')::timestamptz
        OR NEW.closed_at<statement_timestamp()-interval '30 seconds' OR NEW.closed_at>clock_timestamp() THEN
        RAISE EXCEPTION 'ASSET_LOSS_LIVE_INDEPENDENT_APPROVAL_REQUIRED' USING ERRCODE='23514'; END IF;
    NEW.source_snapshot:=source;
    RETURN NEW;
END $function$;
CREATE TRIGGER warehouse_asset_loss_effect_capture BEFORE INSERT ON inventory_asset_loss_effect
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_asset_loss_effect();

CREATE FUNCTION warehouse_capture_loss_retired_permits() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE request inventory_asset_loss_request;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT request FROM inventory_asset_loss_request WHERE tenant_id=NEW.tenant_id AND id=NEW.request_id;
    INSERT INTO inventory_asset_loss_permit_retirement(tenant_id,authorization_id,request_id,permit_snapshot,asset_snapshot)
        SELECT permit.tenant_id,permit.id,NEW.request_id,to_jsonb(permit),to_jsonb(asset)
        FROM inventory_deployment_authorization permit JOIN inventory_serialized_asset asset ON asset.tenant_id=permit.tenant_id AND asset.id=permit.asset_id
        WHERE permit.tenant_id=NEW.tenant_id AND NOT permit.consumed AND (permit.asset_id=request.asset_id OR permit.previous_assignment_id=request.assignment_id)
        AND NOT EXISTS(SELECT FROM inventory_deployment_retirement WHERE tenant_id=permit.tenant_id AND authorization_id=permit.id)
        FOR UPDATE OF permit,asset;
    RETURN NEW;
END $function$;
CREATE TRIGGER warehouse_asset_loss_retire_permits AFTER INSERT ON inventory_asset_loss_effect
    FOR EACH ROW EXECUTE FUNCTION warehouse_capture_loss_retired_permits();

CREATE FUNCTION warehouse_assert_asset_loss_effect(scope uuid,target uuid) RETURNS void LANGUAGE plpgsql SET TimeZone='UTC' AS $function$
DECLARE effect inventory_asset_loss_effect; request inventory_asset_loss_request; approval inventory_approval;
    posting inventory_operation; movement inventory_movement; outgoing inventory_movement_leg; incoming inventory_movement_leg;
    assignment inventory_asset_assignment; source_assignment inventory_asset_assignment;
    retired customer_asset_loss; installation customer_asset_installation; episode onu; outbox fulfillment_asset_outbox;
    body jsonb; input jsonb; dimension jsonb; view jsonb; expected_revision bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO effect FROM inventory_asset_loss_effect WHERE tenant_id=scope AND request_id=target;
    SELECT * INTO request FROM inventory_asset_loss_request WHERE tenant_id=scope AND id=target;
    SELECT * INTO approval FROM inventory_approval WHERE tenant_id=scope AND id=effect.approval_id;
    SELECT * INTO posting FROM inventory_operation WHERE tenant_id=scope AND id=effect.posting_operation_id;
    SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=posting.id;
    SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='OUT';
    SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND direction='IN';
    SELECT * INTO assignment FROM inventory_asset_assignment WHERE tenant_id=scope AND id=effect.assignment_id;
    SELECT * INTO retired FROM customer_asset_loss WHERE tenant_id=scope AND request_id=target;
    SELECT * INTO installation FROM customer_asset_installation WHERE tenant_id=scope AND assignment_id=effect.assignment_id;
    SELECT * INTO episode FROM onu WHERE tenant_id=scope AND id=installation.onu_id;
    SELECT * INTO outbox FROM fulfillment_asset_outbox WHERE tenant_id=scope AND operation_id=posting.id;
    body:=request.snapshot::jsonb; input:=body->'input'; dimension:=body#>'{position,dimension}'; view:=retired.response::jsonb;
    source_assignment:=jsonb_populate_record(NULL::inventory_asset_assignment,effect.source_snapshot->'assignment');
    expected_revision:=coalesce((effect.source_snapshot#>>'{episode,episodeRevision}')::bigint,0)+1;
    IF effect.request_id IS NULL OR request.id IS NULL OR approval.id IS NULL OR posting.id IS NULL
        OR effect.source_snapshot IS DISTINCT FROM request.source_snapshot OR effect.assignment_id<>request.assignment_id
        OR approval.status<>'APPROVED' OR approval.business_action<>'LOSS' OR approval.source_document_id<>request.id
        OR approval.source_document_revision<>0 OR approval.source_snapshot::jsonb->'assetLoss' IS DISTINCT FROM body
        OR posting.namespace<>'warehouse.approval.effect' OR posting.operation_key<>approval.id::text
        OR posting.resource_id<>request.id OR posting.document_id<>request.id OR posting.document_revision<>1
        OR posting.business_action<>'LOSS' OR posting.original_status<>200 OR posting.payload_hash<>approval.source_snapshot_hash
        OR posting.original_body IS DISTINCT FROM approval.terminal_body OR posting.created_at<>effect.closed_at
        OR posting.cutover_epoch IS DISTINCT FROM (body->>'cutoverEpoch')::bigint
        OR posting.resource_scope<>'approval:'||approval.id OR approval.requester_id<>request.actor_id
        OR NOT EXISTS(SELECT FROM inventory_command_identity WHERE tenant_id=scope AND id=posting.id AND canonical_payload::jsonb=approval.source_snapshot::jsonb)
        OR NOT EXISTS(SELECT FROM inventory_approval_effect WHERE tenant_id=scope AND approval_id=approval.id
            AND posting_operation_id=posting.id AND source_document_id=request.id AND original_body=posting.original_body)
        OR EXISTS(SELECT FROM inventory_approval_decision WHERE tenant_id=scope AND approval_id=approval.id
            AND (approver_id=request.actor_id OR delegated_from=request.actor_id))
        OR movement.id IS NULL OR movement.state<>'APPLIED' OR movement.kind<>'LOSS' OR movement.compensates_movement_id IS NOT NULL
        OR movement.document_id<>request.id OR movement.document_revision<>1
        OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND operation_id=posting.id)<>1
        OR (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id)<>2
        OR outgoing.id IS NULL OR incoming.id IS NULL
        OR (outgoing.sku_id,outgoing.stock_identity_id,outgoing.lot_id,outgoing.quantity_base,outgoing.base_unit,
            outgoing.document_line_id,outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,outgoing.condition,outgoing.legal_owner,outgoing.status)
            IS DISTINCT FROM ((dimension->>'skuId')::uuid,request.asset_id,NULL::uuid,1::bigint,'EA'::text,request.id,
                (dimension->>'locationId')::uuid,(dimension->>'custodianId')::uuid,'CUSTOMER'::text,'SERVICEABLE'::text,'ISP'::text,'CUSTOMER_INSTALLED'::text)
        OR (incoming.sku_id,incoming.stock_identity_id,incoming.lot_id,incoming.quantity_base,incoming.base_unit,
            incoming.document_line_id,incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,incoming.condition,incoming.legal_owner,incoming.status)
            IS DISTINCT FROM (outgoing.sku_id,request.asset_id,NULL::uuid,1::bigint,'EA'::text,request.id,
                (input->>'destinationLocationId')::uuid,(input->>'destinationLocationId')::uuid,'LOST'::text,'SERVICEABLE'::text,'ISP'::text,'LOST'::text)
        OR (SELECT count(*) FROM inventory_outbox WHERE tenant_id=scope AND operation_id=posting.id)<>1
        OR NOT EXISTS(SELECT FROM inventory_outbox WHERE tenant_id=scope AND operation_id=posting.id AND event_kind='DISPOSED'
            AND document_id=request.id AND document_revision=1 AND payload=posting.original_body)
        OR EXISTS(SELECT FROM inventory_customer_material_fact WHERE tenant_id=scope AND posting_id=movement.id)
        OR assignment.id IS NULL OR assignment.ended_at IS DISTINCT FROM effect.closed_at
        OR assignment.revision<>source_assignment.revision+1 OR source_assignment.ended_at IS NOT NULL
        OR to_jsonb(assignment)-ARRAY['ended_at','revision'] IS DISTINCT FROM to_jsonb(source_assignment)-ARRAY['ended_at','revision']
        OR EXISTS(SELECT FROM inventory_asset_removal WHERE tenant_id=scope AND assignment_id=assignment.id)
        OR retired.request_id IS NULL OR retired.operation_id<>posting.id OR retired.assignment_id<>assignment.id
        OR retired.episode_id IS DISTINCT FROM installation.id OR retired.onu_id IS DISTINCT FROM installation.onu_id
        OR retired.retired_at<>effect.closed_at OR (view->>'retiredAt')::timestamptz IS DISTINCT FROM effect.closed_at
        OR (view->>'episodeRevision')::bigint IS DISTINCT FROM expected_revision
        OR view-ARRAY['retiredAt','episodeRevision'] IS DISTINCT FROM installation.response::jsonb-ARRAY['retiredAt','episodeRevision']
        OR installation.onu_id IS NOT NULL AND (episode.id IS NULL OR episode.retired_at IS DISTINCT FROM effect.closed_at
            OR episode.episode_revision<>expected_revision OR episode.status<>'DISMANTLED' OR episode.odp_id IS NOT NULL OR episode.odp_port_number IS NOT NULL
            OR NOT EXISTS(SELECT FROM customer_onu_episode_event WHERE tenant_id=scope AND onu_id=episode.id
                AND loss_request_id=request.id AND revision=expected_revision AND kind='RETIRED' AND removal_id IS NULL AND retired_at=effect.closed_at))
        OR outbox.operation_id IS NULL OR outbox.customer_id<>assignment.customer_id OR outbox.work_order_id<>assignment.work_order_id
        OR outbox.old_onu_id IS DISTINCT FROM installation.onu_id OR outbox.new_onu_id IS NOT NULL
        OR NOT EXISTS(SELECT FROM fulfillment_asset_delivery WHERE tenant_id=scope AND operation_id=posting.id)
        OR NOT EXISTS(SELECT FROM inventory_asset_recovery_obligation WHERE tenant_id=scope AND assignment_id=assignment.id
            AND handover_id=request.handover_id AND asset_id=request.asset_id) THEN
        RAISE EXCEPTION 'ASSET_LOSS_APPROVED_LEDGER_AND_EPISODE_BINDING' USING ERRCODE='23514'; END IF;
    PERFORM warehouse_assert_disposition_position(scope,request.asset_id);
END $function$;
DO $migration$ DECLARE definition text; anchor text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_asset_loss_request(uuid,uuid)'::regprocedure);
    anchor:='document.source_document_id IS DISTINCT FROM request.handover_id';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'asset loss handover document binding changed'; END IF;
    definition:=replace(definition,anchor,'document.source_document_id IS DISTINCT FROM (SELECT operation_id FROM inventory_asset_acceptance WHERE tenant_id=scope AND handover_id=request.handover_id)');
    anchor:='OR document.state<>''DRAFT'' OR document.revision<>0 OR document.approval_disposition IS NOT NULL';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'asset loss draft state changed'; END IF;
    definition:=replace(definition,anchor,$patch$OR NOT (
        document.state='DRAFT' AND document.revision=0 AND document.approval_disposition IS NULL OR
        document.state='DRAFT' AND document.revision=1 AND document.approval_disposition='REWORK_REQUIRED' AND EXISTS(
            SELECT FROM inventory_approval WHERE tenant_id=scope AND source_document_id=target AND source_document_revision=0 AND status='REWORK_REQUIRED') OR
        document.state='POSTED' AND document.revision=1 AND document.approval_disposition IS NULL)$patch$);
    anchor:=$patch$OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=target)
        OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=target)$patch$;
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'asset loss draft effect guard changed'; END IF;
    definition:=replace(definition,anchor,'');
    anchor:='END $function$';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'asset loss draft terminator changed'; END IF;
    EXECUTE replace(definition,anchor,$patch$
    IF document.state='DRAFT' THEN
        IF EXISTS(SELECT FROM inventory_asset_loss_effect WHERE tenant_id=scope AND request_id=target)
            OR EXISTS(SELECT FROM inventory_operation WHERE tenant_id=scope AND document_id=target)
            OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=target) THEN
            RAISE EXCEPTION 'ASSET_LOSS_DRAFT_HAS_NO_EFFECT' USING ERRCODE='23514'; END IF;
    ELSE
        PERFORM warehouse_assert_asset_loss_effect(scope,target);
    END IF;
END $function$$patch$);

    definition:=pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    anchor:='CASE OLD.kind';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'asset loss document lifecycle changed'; END IF;
    EXECUTE replace(definition,anchor,'CASE OLD.kind WHEN ''ASSET_LOSS'' THEN (OLD.state,NEW.state)=(''DRAFT'',''POSTED'')');
    definition:=pg_get_functiondef('warehouse_approval_posting_guard()'::regprocedure);
    anchor:='(approval.business_action=''RECEIPT'' AND NEW.kind=''RECEIVE'')';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'asset loss approval movement entry changed'; END IF;
    EXECUTE replace(definition,anchor,anchor||$patch$ OR
        (approval.business_action='LOSS' AND NEW.kind='LOSS' AND EXISTS(
            SELECT FROM inventory_asset_loss_effect WHERE tenant_id=NEW.tenant_id AND request_id=NEW.document_id
                AND approval_id=approval.id AND posting_operation_id=NEW.operation_id AND created_xid=pg_current_xact_id()))$patch$);

    definition:=pg_get_functiondef('warehouse_assert_current_asset_title(uuid,uuid)'::regprocedure);
    anchor:='origin inventory_asset_removal_origin;';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'current asset title declarations changed'; END IF;
    definition:=replace(definition,anchor,anchor||' loss inventory_asset_loss_effect;');
    anchor:='IF assignment.ended_at IS NOT NULL THEN';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'current title closure entry changed'; END IF;
    definition:=replace(definition,anchor,$patch$
    IF assignment.ended_at IS NOT NULL THEN
        SELECT * INTO loss FROM inventory_asset_loss_effect WHERE tenant_id=scope AND inventory_asset_loss_effect.assignment_id=$2;
        IF loss.request_id IS NOT NULL THEN
            PERFORM warehouse_assert_asset_loss_effect(scope,loss.request_id);
            assignment:=jsonb_populate_record(NULL::inventory_asset_assignment,loss.source_snapshot->'assignment');
        END IF;
    END IF;
    IF assignment.ended_at IS NOT NULL THEN$patch$);
    anchor:='IF removal.id IS NULL THEN';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'current title asset position entry changed'; END IF;
    definition:=replace(definition,anchor,$patch$IF loss.request_id IS NOT NULL THEN
        asset:=jsonb_populate_record(NULL::inventory_serialized_asset,loss.source_snapshot->'asset');
    ELSIF removal.id IS NULL THEN$patch$);
    anchor:='IF removal.id IS NULL AND (';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'current title live balance entry changed'; END IF;
    EXECUTE replace(definition,anchor,'IF loss.request_id IS NULL AND removal.id IS NULL AND (');

    definition:=pg_get_functiondef('warehouse_assert_deployment_result(uuid,uuid)'::regprocedure);
    anchor:='OR (assignment.ended_at IS NOT NULL AND NOT EXISTS(SELECT FROM inventory_asset_removal WHERE tenant_id=scope AND assignment_id=assignment.id AND removed_at=assignment.ended_at))';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'deployment closure history entry changed'; END IF;
    EXECUTE replace(definition,anchor,$patch$OR (assignment.ended_at IS NOT NULL
        AND NOT EXISTS(SELECT FROM inventory_asset_removal WHERE tenant_id=scope AND assignment_id=assignment.id AND removed_at=assignment.ended_at)
        AND NOT EXISTS(SELECT FROM inventory_asset_loss_effect WHERE tenant_id=scope AND assignment_id=assignment.id AND closed_at=assignment.ended_at))$patch$);

    definition:=pg_get_functiondef('warehouse_assert_deployment_authorization(uuid,uuid)'::regprocedure);
    anchor:='IF physical.status IN (''DISPOSED'',''LOST'') THEN';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'authorization retired asset entry changed'; END IF;
    definition:=replace(definition,anchor,$patch$
    IF EXISTS(SELECT FROM inventory_asset_loss_permit_retirement WHERE tenant_id=scope AND authorization_id=permit.id) THEN
        IF permit.consumed THEN RAISE EXCEPTION 'LOST_LOAN_AUTHORIZATION_RETIRED' USING ERRCODE='23514'; END IF;
        SELECT (jsonb_populate_record(NULL::inventory_serialized_asset,retirement.asset_snapshot)).* INTO physical
            FROM inventory_asset_loss_permit_retirement retirement WHERE tenant_id=scope AND authorization_id=permit.id;
    END IF;
    IF permit.consumed AND EXISTS(SELECT FROM inventory_asset_loss_effect WHERE tenant_id=scope AND assignment_id=permit.operation_id) THEN
        PERFORM warehouse_assert_asset_loss_effect(scope,(SELECT request_id FROM inventory_asset_loss_effect WHERE tenant_id=scope AND assignment_id=permit.operation_id));
        SELECT (jsonb_populate_record(NULL::inventory_serialized_asset,effect.source_snapshot->'asset')).* INTO physical
            FROM inventory_asset_loss_effect effect WHERE tenant_id=scope AND assignment_id=permit.operation_id;
    END IF;
    IF physical.status IN ('DISPOSED','LOST') THEN$patch$);
    anchor:='IF NOT permit.consumed AND permit.purpose IN (''REPLACE'',''REMOVE'')';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'authorization closed previous assignment entry changed'; END IF;
    EXECUTE replace(definition,anchor,anchor||' AND NOT EXISTS(SELECT FROM inventory_asset_loss_permit_retirement WHERE tenant_id=scope AND authorization_id=permit.id)');

    definition:=pg_get_functiondef('warehouse_record_onu_episode_event()'::regprocedure);
    anchor:='DECLARE opening boolean; removal uuid;';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'ONU event declarations changed'; END IF;
    definition:=replace(definition,anchor,anchor||' loss uuid;');
    anchor:='SELECT id INTO removal FROM inventory_asset_removal WHERE tenant_id=NEW.tenant_id AND assignment_id=NEW.assignment_id;';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'ONU event loss source entry changed'; END IF;
    definition:=replace(definition,anchor,anchor||$patch$
        SELECT request_id INTO loss FROM inventory_asset_loss_effect WHERE tenant_id=NEW.tenant_id
            AND assignment_id=NEW.assignment_id AND closed_at=NEW.retired_at;$patch$);
    definition:=replace(definition,'topology_revision,retired_at,removal_id)','topology_revision,retired_at,removal_id,loss_request_id)');
    definition:=replace(definition,'NEW.topology_revision,NEW.retired_at,removal);','NEW.topology_revision,NEW.retired_at,removal,loss);');
    EXECUTE definition;

    definition:=pg_get_functiondef('warehouse_assert_onu_episode_revision(uuid,uuid)'::regprocedure);
    anchor:='IF installation.id IS NOT NULL AND episode.retired_at IS NOT NULL THEN';
    IF position(anchor IN definition)=0 THEN RAISE EXCEPTION 'ONU retirement validation entry changed'; END IF;
    -- All event sequence/identity/topology validation above still applies. Loss closure
    -- has its own source instead of a fabricated physical-removal retirement record.
    EXECUTE replace(definition,anchor,$patch$IF installation.id IS NOT NULL AND episode.retired_at IS NOT NULL
        AND previous_event.loss_request_id IS NOT NULL THEN
        IF retirement.removal_id IS NOT NULL OR previous_event.removal_id IS NOT NULL OR NOT EXISTS(
            SELECT FROM customer_asset_loss lost JOIN inventory_asset_loss_effect effect ON effect.tenant_id=lost.tenant_id AND effect.request_id=lost.request_id
            WHERE lost.tenant_id=scope AND lost.request_id=previous_event.loss_request_id AND lost.onu_id=episode.id
                AND lost.assignment_id=episode.assignment_id AND lost.episode_id=installation.id AND lost.retired_at=episode.retired_at
                AND effect.assignment_id=episode.assignment_id AND effect.closed_at=episode.retired_at
                AND (lost.response::jsonb->>'episodeRevision')::bigint=episode.episode_revision) THEN
            RAISE EXCEPTION 'ONU_APPROVED_LOSS_RETIREMENT_BINDING' USING ERRCODE='23514'; END IF;
    ELSIF installation.id IS NOT NULL AND episode.retired_at IS NOT NULL THEN$patch$);
END $migration$;
CREATE FUNCTION warehouse_asset_loss_permit_capture_guard() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE effect inventory_asset_loss_effect; request inventory_asset_loss_request; permit inventory_deployment_authorization; asset inventory_serialized_asset;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO effect FROM inventory_asset_loss_effect WHERE tenant_id=NEW.tenant_id AND request_id=NEW.request_id;
    SELECT * INTO request FROM inventory_asset_loss_request WHERE tenant_id=NEW.tenant_id AND id=NEW.request_id;
    SELECT * INTO permit FROM inventory_deployment_authorization WHERE tenant_id=NEW.tenant_id AND id=NEW.authorization_id;
    SELECT * INTO asset FROM inventory_serialized_asset WHERE tenant_id=NEW.tenant_id AND id=permit.asset_id;
    IF NEW.created_xid<>pg_current_xact_id() OR effect.created_xid IS DISTINCT FROM pg_current_xact_id()
        OR permit.id IS NULL OR permit.consumed OR (permit.asset_id<>request.asset_id AND permit.previous_assignment_id IS DISTINCT FROM request.assignment_id)
        OR NEW.permit_snapshot IS DISTINCT FROM to_jsonb(permit) OR NEW.asset_snapshot IS DISTINCT FROM to_jsonb(asset) THEN
        RAISE EXCEPTION 'ASSET_LOSS_RETIREMENT_REQUIRES_LIVE_PERMIT' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END $function$;
CREATE TRIGGER warehouse_asset_loss_permit_capture BEFORE INSERT ON inventory_asset_loss_permit_retirement
    FOR EACH ROW EXECUTE FUNCTION warehouse_asset_loss_permit_capture_guard();

CREATE OR REPLACE FUNCTION warehouse_asset_loss_request_final_guard() RETURNS trigger LANGUAGE plpgsql AS $function$
DECLARE value jsonb; scope uuid; target uuid; document uuid; operation uuid; approval uuid; identity uuid; assignment uuid; movement uuid; evidence uuid;
BEGIN
    FOR value IN SELECT item FROM jsonb_array_elements(CASE TG_OP WHEN 'INSERT' THEN jsonb_build_array(to_jsonb(NEW))
        WHEN 'DELETE' THEN jsonb_build_array(to_jsonb(OLD)) ELSE jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW)) END) item LOOP
        scope:=(value->>'tenant_id')::uuid;
        PERFORM warehouse_assert_deferred_scope(scope);
        document:=CASE WHEN TG_TABLE_NAME IN ('inventory_asset_loss_request','inventory_document') THEN (value->>'id')::uuid
            ELSE (value->>'document_id')::uuid END;
        operation:=CASE WHEN TG_TABLE_NAME IN ('inventory_operation','inventory_command_identity') THEN (value->>'id')::uuid
            ELSE coalesce((value->>'operation_id')::uuid,(value->>'posting_operation_id')::uuid) END;
        approval:=CASE WHEN TG_TABLE_NAME='inventory_approval' THEN (value->>'id')::uuid ELSE (value->>'approval_id')::uuid END;
        identity:=CASE WHEN TG_TABLE_NAME IN ('inventory_serialized_asset','inventory_segment') THEN (value->>'id')::uuid
            ELSE coalesce((value->>'stock_identity_id')::uuid,(value->>'asset_id')::uuid) END;
        assignment:=CASE WHEN TG_TABLE_NAME='inventory_asset_assignment' THEN (value->>'id')::uuid ELSE (value->>'assignment_id')::uuid END;
        movement:=CASE WHEN TG_TABLE_NAME='inventory_movement' THEN (value->>'id')::uuid ELSE (value->>'movement_id')::uuid END;
        evidence:=CASE WHEN TG_TABLE_NAME='wo_signature' THEN (value->>'id')::uuid
            WHEN TG_TABLE_NAME='evidence_object_registry' THEN (value->>'revision_id')::uuid END;
        FOR target IN SELECT request.id FROM inventory_asset_loss_request request
            LEFT JOIN inventory_asset_loss_effect effect ON effect.tenant_id=request.tenant_id AND effect.request_id=request.id
            WHERE request.tenant_id=scope AND (request.id=document OR request.id=(value->>'request_id')::uuid
                OR request.id=(value->>'loss_request_id')::uuid OR request.asset_id=identity OR request.assignment_id=assignment
                OR effect.posting_operation_id=operation OR effect.approval_id=approval
                OR (request.snapshot::jsonb#>>'{input,evidenceId}')::uuid=evidence
                OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND id=movement AND operation_id=effect.posting_operation_id)
                OR EXISTS(SELECT FROM inventory_asset_loss_permit_retirement WHERE tenant_id=scope AND request_id=request.id
                    AND authorization_id=CASE WHEN TG_TABLE_NAME='inventory_deployment_authorization' THEN (value->>'id')::uuid ELSE (value->>'authorization_id')::uuid END)) LOOP
            PERFORM warehouse_assert_asset_loss_request(scope,target);
            IF EXISTS(SELECT FROM inventory_asset_loss_effect WHERE tenant_id=scope AND request_id=target) THEN
                IF EXISTS(SELECT FROM inventory_asset_loss_permit_retirement retirement LEFT JOIN inventory_deployment_authorization permit
                    ON permit.tenant_id=retirement.tenant_id AND permit.id=retirement.authorization_id
                    WHERE retirement.tenant_id=scope AND retirement.request_id=target
                        AND (permit.id IS NULL OR permit.consumed OR retirement.permit_snapshot IS DISTINCT FROM to_jsonb(permit))) THEN
                    RAISE EXCEPTION 'ASSET_LOSS_RETIRED_PERMIT_IS_IMMUTABLE' USING ERRCODE='23514'; END IF;
                IF NOT EXISTS(SELECT FROM inventory_asset_loss_request request JOIN wo_signature signed
                    ON signed.tenant_id=request.tenant_id AND signed.id=(request.snapshot::jsonb#>>'{input,evidenceId}')::uuid
                    JOIN evidence_object_registry registry ON registry.tenant_id=signed.tenant_id AND registry.revision_id=signed.id
                    WHERE request.tenant_id=scope AND request.id=target
                        AND signed.work_order_id=(request.snapshot::jsonb#>>'{ownership,workOrderId}')::uuid
                        AND signed.sha256=request.snapshot::jsonb#>>'{evidence,digest}'
                        AND signed.storage_key=request.snapshot::jsonb#>>'{evidence,reference}'
                        AND registry.expected_sha256=signed.sha256 AND registry.object_key=signed.storage_key) THEN
                    RAISE EXCEPTION 'ASSET_LOSS_EVIDENCE_HISTORY_REQUIRED' USING ERRCODE='23514'; END IF;
            END IF;
        END LOOP;
        IF TG_TABLE_NAME='inventory_document' AND value->>'kind'='ASSET_LOSS' AND value->>'state'='POSTED'
            AND NOT EXISTS(SELECT FROM inventory_asset_loss_request WHERE tenant_id=scope AND id=document) THEN
            RAISE EXCEPTION 'ASSET_LOSS_REQUEST_REQUIRED' USING ERRCODE='23514'; END IF;
        IF TG_TABLE_NAME='fulfillment_asset_outbox' AND NOT EXISTS(SELECT FROM inventory_asset_removal WHERE tenant_id=scope AND id=operation)
            AND NOT EXISTS(SELECT FROM inventory_asset_loss_effect WHERE tenant_id=scope AND posting_operation_id=operation) THEN
            RAISE EXCEPTION 'ASSET_PROVISIONING_PHYSICAL_SOURCE_REQUIRED' USING ERRCODE='23514'; END IF;
    END LOOP;
    RETURN NULL;
END $function$;
DO $migration$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_asset_loss_effect','customer_asset_loss','inventory_asset_loss_permit_retirement',
        'inventory_asset_assignment','inventory_asset_assignment_history','inventory_asset_handover','inventory_asset_recovery_obligation',
        'inventory_serialized_asset','inventory_segment','inventory_balance_projection','inventory_command_identity','inventory_movement_leg',
        'inventory_approval','inventory_approval_decision','inventory_approval_effect','inventory_outbox','inventory_customer_material_fact',
        'customer_asset_installation','onu','customer_onu_episode_event','fulfillment_asset_outbox','fulfillment_asset_delivery',
        'inventory_deployment_authorization','wo_signature','evidence_object_registry'] LOOP
        EXECUTE format('CREATE CONSTRAINT TRIGGER warehouse_asset_loss_request_final AFTER INSERT OR UPDATE OR DELETE ON %I
            DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_asset_loss_request_final_guard()',table_name);
    END LOOP;
END $migration$;
