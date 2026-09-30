CREATE TABLE inventory_count_result (
    id uuid PRIMARY KEY, tenant_id uuid NOT NULL, round_revision bigint NOT NULL, source_revision bigint NOT NULL CHECK(source_revision>=0),
    operation_id uuid NOT NULL, approval_id uuid, created_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,operation_id),
    FOREIGN KEY(tenant_id,id,round_revision) REFERENCES inventory_count_round(tenant_id,document_id,document_revision),
    FOREIGN KEY(tenant_id,operation_id) REFERENCES inventory_operation(tenant_id,id),
    FOREIGN KEY(tenant_id,approval_id) REFERENCES inventory_approval(tenant_id,id)
);
ALTER TABLE inventory_count_result ADD FOREIGN KEY(tenant_id) REFERENCES tenant(id);
ALTER TABLE inventory_count_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE inventory_count_result FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inventory_count_result USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE TRIGGER warehouse_count_result_immutable BEFORE UPDATE OR DELETE ON inventory_count_result FOR EACH ROW EXECUTE FUNCTION warehouse_append_only();
DO $$ BEGIN IF EXISTS(SELECT FROM pg_roles WHERE rolname='warehouse_app') THEN GRANT SELECT,INSERT,UPDATE,DELETE ON inventory_count_result TO warehouse_app; END IF; END $$;

DO $$ DECLARE definition text; original text;
BEGIN
    definition:=pg_get_functiondef('warehouse_approval_posting_guard()'::regprocedure);
    original:='approval.source_document_revision+1=NEW.document_revision';
    IF strpos(definition,original)=0 THEN RAISE EXCEPTION 'approval revision guard missing'; END IF;
    definition:=replace(definition,original,'approval.source_document_revision+CASE WHEN approval.business_action=''COUNT_VARIANCE'' THEN 2 ELSE 1 END=NEW.document_revision');
    original:='(approval.business_action=''RECEIPT'' AND NEW.kind=''RECEIVE'')';
    IF strpos(definition,original)=0 THEN RAISE EXCEPTION 'approval kind guard missing'; END IF;
    definition:=replace(definition,original,original || ' OR (approval.business_action=''COUNT_VARIANCE'' AND NEW.kind=''COUNT_VARIANCE'' AND EXISTS(SELECT FROM inventory_count_scope WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id))');
    EXECUTE definition;
    definition:=pg_get_functiondef('warehouse_approval_terminal_guard()'::regprocedure);
    original:='movement.document_revision=approval.source_document_revision+1';
    IF strpos(definition,original)=0 THEN RAISE EXCEPTION 'approval terminal revision missing'; END IF;
    EXECUTE replace(definition,original,'movement.document_revision=approval.source_document_revision+CASE WHEN approval.business_action=''COUNT_VARIANCE'' THEN 2 ELSE 1 END');
END $$;

CREATE FUNCTION warehouse_assert_count_result(scope uuid, count_id uuid) RETURNS void LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE document inventory_document; result inventory_count_result; operation inventory_operation; approval inventory_approval;
    observation inventory_cycle_count; entry inventory_count_entry; source inventory_document_line; movement inventory_movement;
    incoming inventory_movement_leg; outgoing inventory_movement_leg; quantity bigint; expected_legs integer:=0; row_count integer:=0;
BEGIN
    PERFORM warehouse_assert_deferred_scope(scope);
    SELECT * INTO document FROM inventory_document WHERE tenant_id=scope AND id=count_id;
    IF document.kind IS DISTINCT FROM 'COUNT' THEN RETURN; END IF;
    SELECT * INTO result FROM inventory_count_result WHERE tenant_id=scope AND id=count_id;
    IF document.state<>'POSTED' THEN
        IF result.id IS NOT NULL THEN RAISE EXCEPTION 'nonposted count cannot have an outcome' USING ERRCODE='23514'; END IF;
        RETURN;
    END IF;
    SELECT * INTO operation FROM inventory_operation WHERE tenant_id=scope AND id=result.operation_id;
    IF result.id IS NULL OR operation.id IS NULL OR operation.document_id<>count_id OR operation.resource_id<>count_id
        OR operation.document_revision<>document.revision OR operation.original_status<>200
        OR document.revision<>result.source_revision+(CASE WHEN result.approval_id IS NULL THEN 3 ELSE 2 END)
        OR result.round_revision IS DISTINCT FROM (SELECT max(document_revision) FROM inventory_count_round WHERE tenant_id=scope AND document_id=count_id) THEN
        RAISE EXCEPTION 'count outcome binding mismatch' USING ERRCODE='23514';
    END IF;
    IF result.approval_id IS NULL THEN
        IF operation.namespace<>'warehouse.count.submit' OR operation.business_action<>'COUNT_SUBMIT' OR operation.actor_id<>document.actor_id
            OR EXISTS(SELECT FROM inventory_movement WHERE tenant_id=scope AND document_id=count_id) THEN
            RAISE EXCEPTION 'unchanged count must have no stock movement' USING ERRCODE='23514';
        END IF;
    ELSE
        SELECT * INTO approval FROM inventory_approval WHERE tenant_id=scope AND id=result.approval_id;
        SELECT * INTO movement FROM inventory_movement WHERE tenant_id=scope AND operation_id=operation.id;
        IF approval.id IS NULL OR movement.id IS NULL OR approval.status<>'APPROVED' OR approval.business_action<>'COUNT_VARIANCE'
            OR approval.source_document_id<>count_id OR approval.source_document_revision<>result.source_revision
            OR operation.namespace<>'warehouse.approval.effect' OR operation.business_action<>'COUNT_VARIANCE'
            OR operation.operation_key<>approval.id::text OR operation.payload_hash<>approval.source_snapshot_hash
            OR operation.original_body<>approval.terminal_body OR movement.kind<>'COUNT_VARIANCE' OR movement.state<>'APPLIED'
            OR (SELECT count(*) FROM inventory_movement WHERE tenant_id=scope AND document_id=count_id)<>1
            OR NOT EXISTS(SELECT FROM inventory_approval_effect WHERE tenant_id=scope AND approval_id=approval.id AND posting_operation_id=operation.id)
            OR EXISTS(SELECT FROM inventory_approval_decision decision WHERE decision.tenant_id=scope AND decision.approval_id=approval.id AND
                (decision.approver_id=document.actor_id OR decision.delegated_from=document.actor_id OR EXISTS(
                    SELECT FROM inventory_count_entry WHERE tenant_id=scope AND document_id=count_id
                        AND counter_id IN (decision.approver_id,decision.delegated_from)))) THEN
            RAISE EXCEPTION 'count requires exact independent approval effect' USING ERRCODE='23514';
        END IF;
    END IF;
    FOR observation IN SELECT * FROM inventory_cycle_count WHERE tenant_id=scope AND document_id=count_id AND document_revision=result.round_revision LOOP
        row_count:=row_count+1;
        SELECT * INTO entry FROM inventory_count_entry WHERE tenant_id=scope AND document_id=count_id AND balance_id=observation.balance_id;
        SELECT * INTO source FROM inventory_document_line WHERE tenant_id=scope AND id=entry.id;
        IF entry.id IS NULL OR source.id IS NULL OR entry.counter_id<>observation.counter_id THEN
            RAISE EXCEPTION 'count observation assignment mismatch' USING ERRCODE='23514';
        END IF;
        quantity:=abs(observation.observed_quantity_base-observation.prior_quantity_base);
        IF quantity>0 THEN
            expected_legs:=expected_legs+2;
            IF result.approval_id IS NULL OR source.tracking<>'BULK' OR source.base_unit<>'EA' OR source.legal_owner<>'ISP'
                OR source.condition<>'SERVICEABLE' OR source.custodian_kind<>'WAREHOUSE' THEN
                RAISE EXCEPTION 'unsupported count physical discrepancy' USING ERRCODE='23514';
            END IF;
            SELECT * INTO incoming FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=entry.id AND direction='IN';
            SELECT * INTO outgoing FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id AND document_line_id=entry.id AND direction='OUT';
            IF incoming.id IS NULL OR outgoing.id IS NULL OR incoming.quantity_base<>quantity OR outgoing.quantity_base<>quantity
                OR (incoming.stock_identity_id,incoming.sku_id,incoming.lot_id,incoming.base_unit,incoming.location_id,incoming.custody_owner_id,incoming.custody_owner_kind,incoming.legal_owner)
                    IS DISTINCT FROM (source.stock_identity_id,source.sku_id,source.lot_id,source.base_unit,source.location_id,source.custodian_id,source.custodian_kind,source.legal_owner)
                OR (outgoing.stock_identity_id,outgoing.sku_id,outgoing.lot_id,outgoing.base_unit,outgoing.location_id,outgoing.custody_owner_id,outgoing.custody_owner_kind,outgoing.legal_owner)
                    IS DISTINCT FROM (source.stock_identity_id,source.sku_id,source.lot_id,source.base_unit,source.location_id,source.custodian_id,source.custodian_kind,source.legal_owner)
                OR (incoming.status,incoming.condition,outgoing.status,outgoing.condition) IS DISTINCT FROM
                    (CASE WHEN observation.observed_quantity_base<observation.prior_quantity_base THEN 'LOST' ELSE 'AVAILABLE' END,
                     CASE WHEN observation.observed_quantity_base<observation.prior_quantity_base THEN 'QUARANTINE' ELSE 'SERVICEABLE' END,
                     CASE WHEN observation.observed_quantity_base<observation.prior_quantity_base THEN 'AVAILABLE' ELSE 'LOST' END,
                     CASE WHEN observation.observed_quantity_base<observation.prior_quantity_base THEN 'SERVICEABLE' ELSE 'QUARANTINE' END)
                OR (CASE WHEN observation.observed_quantity_base<observation.prior_quantity_base THEN outgoing.revision ELSE incoming.revision END)<>observation.observed_dimension_revision+1 THEN
                RAISE EXCEPTION 'count adjustment legs mismatch' USING ERRCODE='23514';
            END IF;
        END IF;
        IF result.created_xid=pg_current_xact_id() AND NOT EXISTS(SELECT FROM inventory_balance_projection WHERE tenant_id=scope AND id=observation.balance_id
            AND quantity_base=observation.observed_quantity_base AND revision=observation.observed_dimension_revision+CASE WHEN quantity>0 THEN 1 ELSE 0 END) THEN
            RAISE EXCEPTION 'count result must match measured quantity and observed revision' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF row_count=0 OR row_count<>(SELECT count(*) FROM inventory_count_entry WHERE tenant_id=scope AND document_id=count_id)
        OR (result.approval_id IS NOT NULL AND (expected_legs=0 OR expected_legs<>(SELECT count(*) FROM inventory_movement_leg WHERE tenant_id=scope AND movement_id=movement.id))) THEN
        RAISE EXCEPTION 'count requires every observation and exact adjustment cardinality' USING ERRCODE='23514';
    END IF;
END $$;

CREATE FUNCTION warehouse_count_result_guard() RETURNS trigger LANGUAGE plpgsql SECURITY INVOKER AS $$
DECLARE source_id uuid;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF TG_TABLE_NAME IN ('inventory_count_result','inventory_document') THEN source_id:=NEW.id;
    ELSIF TG_TABLE_NAME='inventory_movement_leg' THEN
        SELECT document_id INTO source_id FROM inventory_movement WHERE tenant_id=NEW.tenant_id AND id=NEW.movement_id;
    ELSE source_id:=NEW.document_id; END IF;
    IF source_id IS NOT NULL THEN PERFORM warehouse_assert_count_result(NEW.tenant_id,source_id); END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER warehouse_count_document_final AFTER INSERT OR UPDATE ON inventory_document DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_count_result_guard();
CREATE CONSTRAINT TRIGGER warehouse_count_result_final AFTER INSERT ON inventory_count_result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_count_result_guard();
CREATE CONSTRAINT TRIGGER warehouse_count_movement_final AFTER INSERT ON inventory_movement DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_count_result_guard();
CREATE CONSTRAINT TRIGGER warehouse_count_leg_final AFTER INSERT ON inventory_movement_leg DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION warehouse_count_result_guard();
