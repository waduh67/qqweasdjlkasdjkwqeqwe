CREATE TABLE inventory_material_handover (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), work_order_id uuid NOT NULL,
    work_order_revision bigint NOT NULL CHECK (work_order_revision>=0), source_identity_id uuid NOT NULL,
    sender_id uuid NOT NULL, receiver_id uuid NOT NULL, dispatcher_id uuid NOT NULL, target_location_id uuid NOT NULL,
    quantity_base bigint NOT NULL CHECK (quantity_base>0), base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')),
    operation_key text NOT NULL, payload_hash varchar(64) NOT NULL, body text NOT NULL, cutover_epoch bigint NOT NULL,
    recorded_at timestamptz NOT NULL, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,operation_key),
    CHECK (sender_id<>receiver_id AND sender_id<>dispatcher_id AND receiver_id<>dispatcher_id),
    FOREIGN KEY (tenant_id,work_order_id) REFERENCES work_order(tenant_id,id),
    FOREIGN KEY (tenant_id,source_identity_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY (tenant_id,sender_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY (tenant_id,receiver_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY (tenant_id,dispatcher_id) REFERENCES app_user(tenant_id,id),
    FOREIGN KEY (tenant_id,target_location_id) REFERENCES inventory_location(tenant_id,id)
);
ALTER TABLE inventory_material_handover ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_material_handover FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_material_handover
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON inventory_material_handover
    FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
CREATE INDEX inventory_material_handover_parties_idx ON inventory_material_handover(tenant_id,work_order_id,sender_id,receiver_id);
ALTER TABLE inventory_material_residual ADD COLUMN authorization_id uuid;
ALTER TABLE inventory_material_residual ADD FOREIGN KEY (tenant_id,authorization_id) REFERENCES inventory_material_handover(tenant_id,id);
ALTER TABLE inventory_material_residual ADD CHECK ((purpose='HANDOVER')=(authorization_id IS NOT NULL));
CREATE UNIQUE INDEX inventory_material_handover_dispatch_uq ON inventory_material_residual(tenant_id,authorization_id) WHERE authorization_id IS NOT NULL;

CREATE FUNCTION warehouse_material_handover_insert_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE payload jsonb;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    payload:=NEW.body::jsonb;
    IF NEW.created_xid<>pg_current_xact_id() OR
        NOT EXISTS (SELECT FROM work_order work JOIN work_order_assignee assignee ON assignee.tenant_id=work.tenant_id AND assignee.work_order_id=work.id
            WHERE work.tenant_id=NEW.tenant_id AND work.id=NEW.work_order_id AND work.warehouse_revision=NEW.work_order_revision
                AND work.status NOT IN ('DONE','CANCELLED') AND assignee.technician_id=NEW.receiver_id) OR
        NOT EXISTS (SELECT FROM inventory_location WHERE tenant_id=NEW.tenant_id AND id=NEW.target_location_id
            AND state='ACTIVE' AND kind='TECHNICIAN' AND custodian_id=NEW.receiver_id) OR
        NOT EXISTS (SELECT FROM inventory_balance_projection WHERE tenant_id=NEW.tenant_id AND stock_identity_id=NEW.source_identity_id
            AND custody_owner_kind='TECHNICIAN' AND custody_owner_id=NEW.sender_id AND status='ISSUED'
            AND condition='SERVICEABLE' AND quantity_base>=NEW.quantity_base AND base_unit=NEW.base_unit) OR
        payload->>'authorizationId' IS DISTINCT FROM NEW.id::text OR payload->>'workOrderId' IS DISTINCT FROM NEW.work_order_id::text OR
        payload->>'senderId' IS DISTINCT FROM NEW.sender_id::text OR payload->>'receiverId' IS DISTINCT FROM NEW.receiver_id::text OR
        payload->>'dispatcherId' IS DISTINCT FROM NEW.dispatcher_id::text OR
        payload->'request'->>'stockIdentityId' IS DISTINCT FROM NEW.source_identity_id::text OR
        payload->'request'->>'quantityBase' IS DISTINCT FROM NEW.quantity_base::text OR payload->'request'->>'baseUnit' IS DISTINCT FROM NEW.base_unit OR
        payload->'request'->>'targetLocationId' IS DISTINCT FROM NEW.target_location_id::text OR
        payload->'request'->>'workOrderRevision' IS DISTINCT FROM NEW.work_order_revision::text THEN
        RAISE EXCEPTION 'handover requires bound dispatcher authorization and named active receiver' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_material_handover_insert BEFORE INSERT ON inventory_material_handover
    FOR EACH ROW EXECUTE FUNCTION warehouse_material_handover_insert_guard();

DO $$ DECLARE definition text;
BEGIN
    definition:=pg_get_functiondef('warehouse_assert_material_residual(uuid,uuid)'::regprocedure);
    IF position('ack.actor_id=residual.sender_id OR document.state<>''RECEIVED_IN_INSPECTION''' IN definition)=0 THEN
        RAISE EXCEPTION 'expected residual acknowledgement binding missing';
    END IF;
    definition:=replace(definition,'    source:=residual.source_dimension::jsonb;',
        '    IF residual.purpose=''HANDOVER'' AND NOT EXISTS (SELECT FROM inventory_material_handover grant_row
        WHERE grant_row.tenant_id=target_tenant AND grant_row.id=residual.authorization_id
        AND grant_row.work_order_id=residual.work_order_id AND grant_row.source_identity_id=residual.source_identity_id
        AND grant_row.sender_id=residual.sender_id AND grant_row.receiver_id=residual.receiver_id
        AND grant_row.quantity_base=residual.quantity_base AND grant_row.base_unit=residual.base_unit
        AND grant_row.target_location_id=residual.target_location_id
        AND (grant_row.body::jsonb->''request'')=(residual.body::jsonb->''request'' || jsonb_build_object(''authorizationId'',NULL))) THEN
        RAISE EXCEPTION ''handover dispatch requires exact dispatcher authorization'' USING ERRCODE=''23514'';
    END IF;
    source:=residual.source_dimension::jsonb;');
    definition:=replace(definition,'ack.actor_id=residual.sender_id OR document.state<>''RECEIVED_IN_INSPECTION''',
        'ack.actor_id=residual.sender_id OR (residual.purpose=''HANDOVER'' AND ack.actor_id<>residual.receiver_id)
            OR document.state<>(CASE WHEN residual.purpose=''RETURN'' THEN ''RECEIVED_IN_INSPECTION'' ELSE ''RECEIVED'' END)');
    definition:=replace(definition,'AND status=''QUARANTINE'' AND condition=''QUARANTINE'' AND custody_owner_kind=''WAREHOUSE''',
        'AND status=(CASE WHEN residual.purpose=''RETURN'' THEN ''QUARANTINE'' ELSE ''ISSUED'' END)
                AND condition=(CASE WHEN residual.purpose=''RETURN'' THEN ''QUARANTINE'' ELSE ''SERVICEABLE'' END)
                AND custody_owner_kind=(CASE WHEN residual.purpose=''RETURN'' THEN ''WAREHOUSE'' ELSE ''TECHNICIAN'' END)');
    definition:=replace(definition,'AND location_id=residual.target_location_id AND custody_owner_id=residual.target_location_id',
        'AND location_id=residual.target_location_id AND custody_owner_id=(CASE WHEN residual.purpose=''RETURN'' THEN residual.target_location_id ELSE residual.receiver_id END)');
    EXECUTE definition;
END $$;

CREATE OR REPLACE FUNCTION warehouse_assert_residual_source(target_tenant uuid,target_work_order uuid,target_receipt uuid,
    target_line uuid,target_usage uuid,target_identity uuid,target_actor uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE root uuid; usage_root uuid; initial_receiver uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT line.accepted_identity_id,receipt.receiver_id INTO root,initial_receiver FROM inventory_material_receipt_line line
        JOIN inventory_material_receipt receipt ON receipt.tenant_id=line.tenant_id AND receipt.id=line.receipt_id
        JOIN inventory_document issue ON issue.tenant_id=receipt.tenant_id AND issue.id=receipt.issue_id
        WHERE line.tenant_id=target_tenant AND line.receipt_id=target_receipt AND line.issue_line_id=target_line
            AND issue.work_order_id=target_work_order AND line.accepted_base>0;
    IF root IS NULL THEN RAISE EXCEPTION 'residual requires acknowledged source' USING ERRCODE='23514'; END IF;
    IF initial_receiver<>target_actor AND NOT EXISTS (
        WITH RECURSIVE ancestry(id,parent_segment_id) AS (
            SELECT id,parent_segment_id FROM inventory_segment WHERE tenant_id=target_tenant AND id=target_identity
            UNION SELECT segment.id,segment.parent_segment_id FROM inventory_segment segment JOIN ancestry ON ancestry.parent_segment_id=segment.id
                WHERE segment.tenant_id=target_tenant)
        SELECT FROM inventory_material_residual residual JOIN inventory_material_residual_ack ack
            ON ack.tenant_id=residual.tenant_id AND ack.residual_id=residual.id
        JOIN ancestry ON ancestry.id=residual.transit_identity_id
        WHERE residual.tenant_id=target_tenant AND residual.receipt_id=target_receipt AND residual.issue_line_id=target_line
            AND residual.purpose='HANDOVER' AND residual.receiver_id=target_actor AND ack.actor_id=target_actor) THEN
        RAISE EXCEPTION 'residual requires acknowledged custodian' USING ERRCODE='23514';
    END IF;
    IF EXISTS (SELECT FROM inventory_material_usage_line WHERE tenant_id=target_tenant AND receipt_id=target_receipt AND issue_line_id=target_line) THEN
        SELECT remainder_identity_id INTO usage_root FROM inventory_material_usage_line WHERE tenant_id=target_tenant
            AND usage_id=target_usage AND receipt_id=target_receipt AND issue_line_id=target_line;
        IF usage_root IS NULL THEN RAISE EXCEPTION 'residual requires exact unused usage remainder' USING ERRCODE='23514'; END IF;
        root:=usage_root;
    ELSIF target_usage IS NOT NULL THEN RAISE EXCEPTION 'residual usage source mismatch' USING ERRCODE='23514'; END IF;
    IF NOT EXISTS (WITH RECURSIVE ancestry(id,parent_segment_id) AS (
        SELECT id,parent_segment_id FROM inventory_segment WHERE tenant_id=target_tenant AND id=target_identity
        UNION SELECT segment.id,segment.parent_segment_id FROM inventory_segment segment JOIN ancestry ON ancestry.parent_segment_id=segment.id
            WHERE segment.tenant_id=target_tenant) SELECT FROM ancestry WHERE id=root) THEN
        RAISE EXCEPTION 'residual identity substitution' USING ERRCODE='23514';
    END IF;
END $$;
