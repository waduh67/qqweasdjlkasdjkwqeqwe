ALTER TABLE inventory_issue_snapshot ADD UNIQUE (tenant_id,id,receiver_id);
ALTER TABLE inventory_issue_line ADD UNIQUE (tenant_id,issue_id,id);

CREATE TABLE inventory_material_receipt (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), issue_id uuid NOT NULL,
    issue_revision bigint NOT NULL CHECK (issue_revision>=3), receiver_id uuid NOT NULL,
    receiver_name text NOT NULL CHECK (length(btrim(receiver_name))>0),
    evidence_reference text NOT NULL CHECK (length(btrim(evidence_reference)) BETWEEN 1 AND 500),
    recorded_at timestamptz NOT NULL, posting_id uuid NOT NULL,
    snapshot text NOT NULL CHECK (jsonb_typeof(snapshot::jsonb)='object'),
    created_at timestamptz NOT NULL DEFAULT now(), created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (tenant_id,id), UNIQUE (tenant_id,id,issue_id), UNIQUE (tenant_id,issue_id,issue_revision), UNIQUE (tenant_id,posting_id),
    FOREIGN KEY (tenant_id,issue_id,receiver_id) REFERENCES inventory_issue_snapshot(tenant_id,id,receiver_id),
    FOREIGN KEY (tenant_id,id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY (tenant_id,posting_id) REFERENCES inventory_movement(tenant_id,id)
);
CREATE INDEX inventory_material_receipt_receiver_idx ON inventory_material_receipt(tenant_id,receiver_id,recorded_at,id);

CREATE TABLE inventory_material_receipt_line (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant(id), receipt_id uuid NOT NULL, issue_id uuid NOT NULL,
    issue_line_id uuid NOT NULL, dispatched_identity_id uuid NOT NULL, source_identity_id uuid NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision>=0), accepted_identity_id uuid, remaining_identity_id uuid,
    accepted_base bigint NOT NULL CHECK (accepted_base>=0), missing_base bigint NOT NULL CHECK (missing_base>=0),
    rejected_base bigint NOT NULL CHECK (rejected_base>=0), prior_accepted_base bigint NOT NULL CHECK (prior_accepted_base>=0),
    remaining_base bigint NOT NULL CHECK (remaining_base>=0), base_unit varchar(2) NOT NULL CHECK (base_unit IN ('EA','MM')), reason text,
    created_at timestamptz NOT NULL DEFAULT now(), UNIQUE (tenant_id,id), UNIQUE (tenant_id,receipt_id,issue_line_id),
    CHECK ((accepted_base=0)=(accepted_identity_id IS NULL)), CHECK ((remaining_base=0)=(remaining_identity_id IS NULL)),
    CHECK (accepted_base::numeric+missing_base::numeric+rejected_base::numeric BETWEEN 1 AND 9223372036854775807),
    CHECK (missing_base::numeric+rejected_base::numeric<=remaining_base),
    CHECK (reason IS NULL OR length(reason)<=1000),
    CHECK ((missing_base=0 AND rejected_base=0) OR (reason IS NOT NULL AND length(btrim(reason))>0)),
    FOREIGN KEY (tenant_id,receipt_id,issue_id) REFERENCES inventory_material_receipt(tenant_id,id,issue_id),
    FOREIGN KEY (tenant_id,issue_id,issue_line_id) REFERENCES inventory_issue_line(tenant_id,issue_id,id),
    FOREIGN KEY (tenant_id,dispatched_identity_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY (tenant_id,source_identity_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY (tenant_id,accepted_identity_id) REFERENCES inventory_segment(tenant_id,id),
    FOREIGN KEY (tenant_id,remaining_identity_id) REFERENCES inventory_segment(tenant_id,id)
);
CREATE INDEX inventory_material_receipt_line_source_idx ON inventory_material_receipt_line(tenant_id,issue_line_id,receipt_id);
CREATE INDEX inventory_material_receipt_line_custody_idx ON inventory_material_receipt_line(tenant_id,accepted_identity_id);

DO $$ DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['inventory_material_receipt','inventory_material_receipt_line'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',table_name);
        EXECUTE format('CREATE TRIGGER warehouse_append_only BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION warehouse_append_only()',table_name);
    END LOOP;
END $$;

CREATE FUNCTION warehouse_material_receipt_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_material_receipt' THEN
        IF NEW.created_xid<>pg_current_xact_id() THEN RAISE EXCEPTION 'receipt transaction mismatch' USING ERRCODE='23514'; END IF;
    ELSE
        IF NOT EXISTS (SELECT FROM inventory_material_receipt WHERE tenant_id=NEW.tenant_id AND id=NEW.receipt_id AND created_xid=pg_current_xact_id()) THEN
            RAISE EXCEPTION 'receipt lines must be sealed in receipt transaction' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_material_receipt_insert BEFORE INSERT ON inventory_material_receipt FOR EACH ROW EXECUTE FUNCTION warehouse_material_receipt_insert_guard();
CREATE TRIGGER warehouse_material_receipt_line_insert BEFORE INSERT ON inventory_material_receipt_line FOR EACH ROW EXECUTE FUNCTION warehouse_material_receipt_insert_guard();

CREATE FUNCTION warehouse_assert_material_receipt(target_tenant uuid,target_receipt uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE receipt inventory_material_receipt%ROWTYPE; item record; transit inventory_movement_leg%ROWTYPE;
    previous_quantity numeric; previous_identity uuid; frozen jsonb; line_json jsonb; expected_legs bigint:=0; split_required boolean;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT * INTO STRICT receipt FROM inventory_material_receipt WHERE tenant_id=target_tenant AND id=target_receipt;
    frozen:=receipt.snapshot::jsonb;
    IF NOT EXISTS (SELECT FROM inventory_operation operation JOIN inventory_movement movement
        ON movement.tenant_id=operation.tenant_id AND movement.operation_id=operation.id
        JOIN inventory_issue_snapshot issue ON issue.tenant_id=operation.tenant_id AND issue.id=operation.document_id
        WHERE operation.tenant_id=target_tenant AND operation.id=receipt.id AND operation.document_id=receipt.issue_id
            AND operation.actor_id=receipt.receiver_id AND operation.document_revision=receipt.issue_revision
            AND operation.business_action='ACKNOWLEDGE' AND operation.namespace='warehouse.material.acknowledge'
            AND operation.original_status=200 AND operation.original_body=receipt.snapshot AND operation.created_at=receipt.recorded_at
            AND movement.id=receipt.posting_id AND movement.kind='TRANSFER' AND movement.state='APPLIED'
            AND movement.document_id=receipt.issue_id AND movement.document_revision=receipt.issue_revision
            AND frozen->'issue'=issue.snapshot::jsonb AND frozen->>'receiptId'=receipt.id::text
            AND frozen->>'issueId'=receipt.issue_id::text AND (frozen->>'revision')::bigint=receipt.issue_revision
            AND frozen->'receiver'->>'id'=receipt.receiver_id::text AND frozen->'receiver'->>'name'=receipt.receiver_name
            AND frozen->>'evidenceReference'=receipt.evidence_reference AND (frozen->>'recordedAt')::timestamptz=receipt.recorded_at
            AND frozen->>'postingId'=receipt.posting_id::text) THEN
        RAISE EXCEPTION 'receipt requires exact immutable operation and receiver snapshot' USING ERRCODE='23514';
    END IF;
    IF (SELECT count(*) FROM inventory_material_receipt_line WHERE tenant_id=target_tenant AND receipt_id=receipt.id) IS DISTINCT FROM jsonb_array_length(frozen->'lines') OR
        NOT EXISTS (SELECT FROM inventory_material_receipt_line WHERE tenant_id=target_tenant AND receipt_id=receipt.id AND accepted_base>0) THEN
        RAISE EXCEPTION 'receipt requires complete nonempty accepted subset' USING ERRCODE='23514';
    END IF;
    FOR item IN SELECT line.*,binding.quantity_base dispatched_base,binding.stock_identity_id dispatched_id,document.base_unit dispatched_unit
        FROM inventory_material_receipt_line line JOIN inventory_issue_line binding ON binding.tenant_id=line.tenant_id AND binding.id=line.issue_line_id
        JOIN inventory_document_line document ON document.tenant_id=binding.tenant_id AND document.id=binding.id
        WHERE line.tenant_id=target_tenant AND line.receipt_id=receipt.id LOOP
        SELECT coalesce(sum(line.accepted_base::numeric),0) INTO previous_quantity FROM inventory_material_receipt_line line
            JOIN inventory_material_receipt prior ON prior.tenant_id=line.tenant_id AND prior.id=line.receipt_id
            WHERE line.tenant_id=target_tenant AND line.issue_line_id=item.issue_line_id AND prior.issue_revision<receipt.issue_revision;
        SELECT line.remaining_identity_id INTO previous_identity FROM inventory_material_receipt_line line
            JOIN inventory_material_receipt prior ON prior.tenant_id=line.tenant_id AND prior.id=line.receipt_id
            WHERE line.tenant_id=target_tenant AND line.issue_line_id=item.issue_line_id AND prior.issue_revision<receipt.issue_revision AND line.accepted_base>0
            ORDER BY prior.issue_revision DESC LIMIT 1;
        IF NOT FOUND THEN previous_identity:=item.dispatched_id; END IF;
        IF item.dispatched_identity_id<>item.dispatched_id OR item.base_unit<>item.dispatched_unit OR item.source_identity_id IS DISTINCT FROM previous_identity OR
            item.prior_accepted_base<>previous_quantity OR item.accepted_base::numeric+previous_quantity+item.remaining_base::numeric<>item.dispatched_base THEN
            RAISE EXCEPTION 'receipt quantity or dispatched lineage mismatch' USING ERRCODE='23514';
        END IF;
        SELECT leg.* INTO STRICT transit FROM inventory_movement_leg leg JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            JOIN inventory_operation operation ON operation.tenant_id=movement.tenant_id AND operation.id=movement.operation_id
            WHERE operation.tenant_id=target_tenant AND operation.document_id=receipt.issue_id AND operation.business_action='DISPATCH'
                AND leg.document_line_id=item.issue_line_id AND leg.direction='IN' AND leg.status='IN_TRANSIT';
        SELECT entry INTO STRICT line_json FROM jsonb_array_elements(frozen->'lines') entry WHERE entry->'selection'->>'issueLineId'=item.issue_line_id::text;
        IF line_json->'selection'->>'stockIdentityId' IS DISTINCT FROM item.dispatched_identity_id::text OR
            line_json->'selection'->>'baseUnit' IS DISTINCT FROM item.base_unit OR
            line_json->'selection'->>'acceptedBase' IS DISTINCT FROM item.accepted_base::text OR
            line_json->'selection'->>'missingBase' IS DISTINCT FROM item.missing_base::text OR
            line_json->'selection'->>'rejectedBase' IS DISTINCT FROM item.rejected_base::text OR
            line_json->'selection'->>'reason' IS DISTINCT FROM item.reason OR
            line_json->'source'->>'stockIdentityId' IS DISTINCT FROM item.source_identity_id::text OR
            line_json->>'sourceRevision' IS DISTINCT FROM item.source_revision::text OR
            line_json->>'priorAcceptedBase' IS DISTINCT FROM item.prior_accepted_base::text OR
            line_json->>'remainingBase' IS DISTINCT FROM item.remaining_base::text OR
            line_json->'accepted'->>'stockIdentityId' IS DISTINCT FROM item.accepted_identity_id::text OR
            line_json->'remainder'->>'stockIdentityId' IS DISTINCT FROM item.remaining_identity_id::text THEN
            RAISE EXCEPTION 'receipt line snapshot mismatch' USING ERRCODE='23514';
        END IF;
        split_required:=item.base_unit='MM' AND item.accepted_base>0 AND item.remaining_base>0;
        IF item.accepted_base>0 THEN
            expected_legs:=expected_legs+2+CASE WHEN split_required THEN 1 ELSE 0 END;
            IF NOT EXISTS (SELECT FROM inventory_movement_leg leg WHERE leg.tenant_id=target_tenant AND leg.movement_id=receipt.posting_id
                AND leg.document_line_id=item.issue_line_id AND leg.direction='OUT' AND leg.status='IN_TRANSIT'
                AND leg.stock_identity_id=item.source_identity_id AND leg.quantity_base=item.accepted_base+CASE WHEN split_required THEN item.remaining_base ELSE 0 END
                AND (leg.sku_id,leg.base_unit,leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,leg.condition,leg.legal_owner)
                    =(transit.sku_id,transit.base_unit,transit.location_id,transit.custody_owner_id,transit.custody_owner_kind,transit.condition,transit.legal_owner)
                AND leg.lot_id IS NOT DISTINCT FROM transit.lot_id) OR
                NOT EXISTS (SELECT FROM inventory_movement_leg leg JOIN inventory_location location ON location.tenant_id=leg.tenant_id AND location.id=leg.location_id
                    WHERE leg.tenant_id=target_tenant AND leg.movement_id=receipt.posting_id AND leg.document_line_id=item.issue_line_id
                        AND leg.stock_identity_id=item.accepted_identity_id AND leg.direction='IN' AND leg.status='ISSUED'
                        AND leg.quantity_base=item.accepted_base AND leg.custody_owner_id=receipt.receiver_id AND leg.custody_owner_kind='TECHNICIAN'
                        AND location.kind='TECHNICIAN' AND location.custodian_id=receipt.receiver_id AND leg.condition='SERVICEABLE' AND leg.legal_owner='ISP'
                        AND leg.sku_id=transit.sku_id AND leg.base_unit=transit.base_unit AND leg.lot_id IS NOT DISTINCT FROM transit.lot_id
                        AND line_json->'accepted'->>'locationId'=leg.location_id::text) THEN
                RAISE EXCEPTION 'accepted quantity requires exact transit debit and named custody credit' USING ERRCODE='23514';
            END IF;
        END IF;
        IF split_required THEN
            IF NOT EXISTS (SELECT FROM inventory_segment parent JOIN inventory_segment accepted ON accepted.tenant_id=parent.tenant_id AND accepted.parent_segment_id=parent.id
                JOIN inventory_segment remainder ON remainder.tenant_id=parent.tenant_id AND remainder.parent_segment_id=parent.id
                WHERE parent.tenant_id=target_tenant AND parent.id=item.source_identity_id AND parent.state='SPLIT' AND parent.revision=item.source_revision+1
                    AND accepted.id=item.accepted_identity_id AND accepted.quantity_base=item.accepted_base
                    AND remainder.id=item.remaining_identity_id AND remainder.quantity_base=item.remaining_base) OR
                NOT EXISTS (SELECT FROM inventory_movement_leg leg WHERE leg.tenant_id=target_tenant AND leg.movement_id=receipt.posting_id
                    AND leg.document_line_id=item.issue_line_id AND leg.stock_identity_id=item.remaining_identity_id
                    AND leg.direction='IN' AND leg.quantity_base=item.remaining_base AND leg.status='IN_TRANSIT'
                    AND (leg.sku_id,leg.base_unit,leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,leg.condition,leg.legal_owner)
                        =(transit.sku_id,transit.base_unit,transit.location_id,transit.custody_owner_id,transit.custody_owner_kind,transit.condition,transit.legal_owner)
                    AND leg.lot_id IS NOT DISTINCT FROM transit.lot_id) THEN
                RAISE EXCEPTION 'partial cable receipt requires conserved split and transit remainder' USING ERRCODE='23514';
            END IF;
        ELSIF (item.accepted_base>0 AND item.accepted_identity_id<>item.source_identity_id) OR
            (item.remaining_base>0 AND item.remaining_identity_id<>item.source_identity_id) THEN
            RAISE EXCEPTION 'whole receipt must retain dispatched identity' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=target_tenant AND movement_id=receipt.posting_id)<>expected_legs THEN
        RAISE EXCEPTION 'receipt posting contains unaccounted legs' USING ERRCODE='23514';
    END IF;
    IF jsonb_array_length(frozen->'totals')<>(SELECT count(*) FROM inventory_issue_line WHERE tenant_id=target_tenant AND issue_id=receipt.issue_id) OR
        EXISTS (SELECT FROM inventory_issue_line binding JOIN inventory_document_line source ON source.tenant_id=binding.tenant_id AND source.id=binding.id
            WHERE binding.tenant_id=target_tenant AND binding.issue_id=receipt.issue_id AND NOT EXISTS (
                SELECT FROM jsonb_array_elements(frozen->'totals') total WHERE total->>'issueLineId'=binding.id::text AND total->>'baseUnit'=source.base_unit
                    AND (total->>'dispatchedBase')::numeric=binding.quantity_base AND (total->>'acceptedBase')::numeric>=0
                    AND (total->>'inTransitBase')::numeric>=0 AND (total->>'acceptedBase')::numeric+(total->>'inTransitBase')::numeric=binding.quantity_base
                    AND (total->>'acceptedBase')::numeric=(SELECT coalesce(sum(line.accepted_base::numeric),0) FROM inventory_material_receipt_line line
                        JOIN inventory_material_receipt prior ON prior.tenant_id=line.tenant_id AND prior.id=line.receipt_id
                        WHERE line.tenant_id=target_tenant AND line.issue_line_id=binding.id AND prior.issue_revision<=receipt.issue_revision))) OR
        frozen->>'state' IS DISTINCT FROM (CASE WHEN EXISTS (SELECT FROM jsonb_array_elements(frozen->'totals') total WHERE (total->>'inTransitBase')::numeric>0)
            THEN 'PART_RECEIVED' ELSE 'RECEIVED' END) THEN
        RAISE EXCEPTION 'receipt quantitative totals mismatch' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_material_receipt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_issue uuid; current_state text; current_revision bigint; receipt_count bigint; accepted numeric; issued numeric;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_document' THEN target_issue:=NEW.id;
    ELSIF TG_TABLE_NAME='inventory_material_receipt' THEN
        target_issue:=NEW.issue_id;
        PERFORM warehouse_assert_material_receipt(NEW.tenant_id,NEW.id);
    ELSE
        target_issue:=NEW.issue_id;
        PERFORM warehouse_assert_material_receipt(NEW.tenant_id,NEW.receipt_id);
    END IF;
    SELECT document.state,document.revision INTO current_state,current_revision FROM inventory_document document
        JOIN inventory_issue_snapshot snapshot ON snapshot.tenant_id=document.tenant_id AND snapshot.id=document.id
        WHERE document.tenant_id=NEW.tenant_id AND document.id=target_issue FOR NO KEY UPDATE OF document;
    IF NOT FOUND OR current_state NOT IN ('DISPATCHED','PART_RECEIVED','RECEIVED') THEN RETURN NEW; END IF;
    SELECT count(*) INTO receipt_count FROM inventory_material_receipt WHERE tenant_id=NEW.tenant_id AND issue_id=target_issue;
    SELECT coalesce(sum(line.accepted_base::numeric),0) INTO accepted FROM inventory_material_receipt_line line WHERE tenant_id=NEW.tenant_id AND issue_id=target_issue;
    SELECT sum(quantity_base::numeric) INTO issued FROM inventory_issue_line WHERE tenant_id=NEW.tenant_id AND issue_id=target_issue;
    IF current_revision<>2+receipt_count OR accepted>issued OR
        current_state IS DISTINCT FROM (CASE WHEN accepted=0 THEN 'DISPATCHED' WHEN accepted=issued THEN 'RECEIVED' ELSE 'PART_RECEIVED' END) OR
        (receipt_count>0 AND (SELECT max(issue_revision) FROM inventory_material_receipt WHERE tenant_id=NEW.tenant_id AND issue_id=target_issue)<>current_revision) THEN
        RAISE EXCEPTION 'issue state requires complete quantitative acknowledgement receipts' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_material_receipt_bound AFTER INSERT ON inventory_material_receipt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_receipt_guard();
CREATE CONSTRAINT TRIGGER warehouse_material_receipt_line_bound AFTER INSERT ON inventory_material_receipt_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_receipt_guard();
CREATE CONSTRAINT TRIGGER warehouse_material_receipt_issue_bound AFTER UPDATE ON inventory_document
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_material_receipt_guard();
