DO $$ DECLARE state_constraint record; definition text;
BEGIN
    SELECT conname,pg_get_expr(conbin,conrelid) expression INTO STRICT state_constraint FROM pg_constraint
        WHERE conrelid='inventory_document'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%PICKED%';
    EXECUTE format('ALTER TABLE inventory_document DROP CONSTRAINT %I',state_constraint.conname);
    EXECUTE format('ALTER TABLE inventory_document ADD CONSTRAINT %I CHECK ((%s) OR (kind=''ISSUE'' AND state=''UNPICKED''))',
        state_constraint.conname,state_constraint.expression);
    definition := pg_get_functiondef('warehouse_document_guard()'::regprocedure);
    IF position('(''PICKED'',''DISPATCHED'')' IN definition)=0 THEN
        RAISE EXCEPTION 'expected issue lifecycle definition missing';
    END IF;
    EXECUTE replace(definition,'(''PICKED'',''DISPATCHED'')','(''PICKED'',''DISPATCHED''),(''PICKED'',''UNPICKED'')');
END $$;

CREATE INDEX inventory_issue_reservation_idx ON inventory_issue_line(tenant_id,reservation_id,issue_id);
CREATE VIEW inventory_live_issue_reservation WITH (security_invoker=true) AS
    SELECT binding.tenant_id,binding.reservation_id,binding.issue_id
    FROM inventory_issue_line binding JOIN inventory_document issue ON issue.tenant_id=binding.tenant_id AND issue.id=binding.issue_id
    WHERE issue.state='PICKED' AND NOT EXISTS (
        SELECT FROM inventory_issue_unpick unpick WHERE unpick.tenant_id=issue.tenant_id AND unpick.id=issue.id);
DO $$ BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN
        GRANT SELECT ON inventory_live_issue_reservation TO warehouse_app;
    END IF;
END $$;

CREATE FUNCTION warehouse_assert_live_issue_binding(target_tenant uuid,target_issue uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE issue_state text; issue_revision bigint; frozen jsonb; line_count bigint;
BEGIN
    PERFORM warehouse_assert_deferred_scope(target_tenant);
    SELECT document.state,document.revision,snapshot.snapshot::jsonb INTO issue_state,issue_revision,frozen
        FROM inventory_document document JOIN inventory_issue_snapshot snapshot ON snapshot.tenant_id=document.tenant_id AND snapshot.id=document.id
        WHERE document.tenant_id=target_tenant AND document.id=target_issue FOR NO KEY UPDATE OF document;
    IF NOT FOUND THEN RETURN; END IF;
    IF issue_state='PICKED' AND NOT EXISTS (SELECT FROM inventory_issue_unpick WHERE tenant_id=target_tenant AND id=target_issue) THEN
        SELECT count(*) INTO line_count FROM inventory_document_line WHERE tenant_id=target_tenant AND document_id=target_issue;
        IF issue_revision IS DISTINCT FROM (frozen->>'revision')::bigint OR line_count=0 OR
            line_count<>(SELECT count(*) FROM inventory_issue_line WHERE tenant_id=target_tenant AND issue_id=target_issue) OR
            line_count IS DISTINCT FROM jsonb_array_length(frozen->'lines') THEN
            RAISE EXCEPTION 'warehouse_live_issue_binding_ck: incomplete live issue' USING ERRCODE='23514';
        END IF;
        PERFORM reservation.id FROM inventory_reservation reservation JOIN inventory_issue_line binding
            ON binding.tenant_id=reservation.tenant_id AND binding.reservation_id=reservation.id
            WHERE binding.tenant_id=target_tenant AND binding.issue_id=target_issue ORDER BY reservation.id FOR UPDATE OF reservation;
        IF EXISTS (SELECT FROM inventory_issue_line binding
            JOIN inventory_document_line line ON line.tenant_id=binding.tenant_id AND line.id=binding.id
            LEFT JOIN inventory_reservation reservation ON reservation.tenant_id=binding.tenant_id AND reservation.id=binding.reservation_id
            WHERE binding.tenant_id=target_tenant AND binding.issue_id=target_issue AND
                (reservation.state IS DISTINCT FROM 'OPEN' OR reservation.revision IS DISTINCT FROM binding.reservation_revision OR
                 reservation.reserved_picked_base IS DISTINCT FROM binding.quantity_base OR reservation.stock_identity_id IS DISTINCT FROM binding.stock_identity_id OR
                 reservation.document_line_id IS DISTINCT FROM line.source_line_id OR reservation.sku_id IS DISTINCT FROM line.sku_id OR
                 reservation.base_unit IS DISTINCT FROM line.base_unit OR line.quantity_base IS DISTINCT FROM binding.quantity_base OR
                 NOT EXISTS (SELECT FROM jsonb_array_elements(frozen->'lines') entry WHERE (entry->>'id')::uuid=binding.id
                     AND (entry->>'reservationId')::uuid=binding.reservation_id AND (entry->>'reservationRevision')::bigint=binding.reservation_revision
                     AND (entry->>'quantityBase')::bigint=binding.quantity_base AND (entry->'dimension'->>'stockIdentityId')::uuid=binding.stock_identity_id) OR
                 EXISTS (SELECT FROM inventory_live_issue_reservation other WHERE other.tenant_id=binding.tenant_id
                     AND other.reservation_id=binding.reservation_id AND other.issue_id<>binding.issue_id))) THEN
            RAISE EXCEPTION 'warehouse_live_issue_binding_ck: live issue requires exact OPEN picked reservation revision and quantity' USING ERRCODE='23514';
        END IF;
    ELSIF issue_state='UNPICKED' THEN
        IF NOT EXISTS (SELECT FROM inventory_issue_unpick unpick JOIN inventory_operation operation
            ON operation.tenant_id=unpick.tenant_id AND operation.id=unpick.operation_id
            WHERE unpick.tenant_id=target_tenant AND unpick.id=target_issue AND operation.document_id=target_issue
                AND operation.document_revision=issue_revision AND operation.business_action='UNPICK') THEN
            RAISE EXCEPTION 'warehouse_live_issue_binding_ck: unpick requires matching immutable operation' USING ERRCODE='23514';
        END IF;
    END IF;
END $$;

CREATE FUNCTION warehouse_live_issue_binding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_issue uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME='inventory_reservation' THEN
        FOR target_issue IN SELECT DISTINCT issue_id FROM inventory_issue_line WHERE tenant_id=NEW.tenant_id AND reservation_id=NEW.id ORDER BY issue_id LOOP
            PERFORM warehouse_assert_live_issue_binding(NEW.tenant_id,target_issue);
        END LOOP;
    ELSIF TG_TABLE_NAME='inventory_issue_line' THEN
        PERFORM warehouse_assert_live_issue_binding(NEW.tenant_id,NEW.issue_id);
    ELSE
        IF TG_TABLE_NAME='inventory_issue_unpick' AND NOT EXISTS (
            SELECT FROM inventory_document WHERE tenant_id=NEW.tenant_id AND id=NEW.id AND state='UNPICKED') THEN
            RAISE EXCEPTION 'warehouse_live_issue_binding_ck: unpick must transition document state in the same transaction' USING ERRCODE='23514';
        END IF;
        PERFORM warehouse_assert_live_issue_binding(NEW.tenant_id,NEW.id);
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_issue_reservation_live AFTER UPDATE ON inventory_reservation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_live_issue_binding_guard();
CREATE CONSTRAINT TRIGGER warehouse_issue_document_live AFTER UPDATE ON inventory_document
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_live_issue_binding_guard();
CREATE CONSTRAINT TRIGGER warehouse_issue_snapshot_live AFTER INSERT ON inventory_issue_snapshot
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_live_issue_binding_guard();
CREATE CONSTRAINT TRIGGER warehouse_issue_line_live AFTER INSERT ON inventory_issue_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_live_issue_binding_guard();
CREATE CONSTRAINT TRIGGER warehouse_issue_unpick_live AFTER INSERT ON inventory_issue_unpick
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_live_issue_binding_guard();
