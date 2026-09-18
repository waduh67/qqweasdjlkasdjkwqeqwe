CREATE FUNCTION warehouse_replenishment_snapshot_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE live_rule inventory_replenishment_rule%ROWTYPE; expected_snapshot jsonb;
    available numeric; inbound numeric; target numeric; minimum numeric; multiple numeric; property text;
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    SELECT * INTO STRICT live_rule FROM inventory_replenishment_rule WHERE tenant_id=NEW.tenant_id AND id=NEW.rule_id FOR SHARE;
    IF TG_OP='INSERT' OR NEW.rule_snapshot IS DISTINCT FROM OLD.rule_snapshot OR
        (OLD.accepted_at IS NULL AND NEW.accepted_at IS NOT NULL) THEN
        expected_snapshot:=jsonb_build_object('id',live_rule.id,'revision',live_rule.revision,'skuId',live_rule.sku_id,
            'locationId',live_rule.location_id,'baseUnit',live_rule.base_unit,'minimumBase',live_rule.minimum_base::text,
            'maximumBase',live_rule.maximum_base::text,'targetBase',coalesce(live_rule.target_base,live_rule.maximum_base)::text,
            'packageMultipleBase',live_rule.package_multiple_base::text,'leadTimeDays',live_rule.lead_time_days,'active',live_rule.active);
        IF NEW.rule_snapshot IS DISTINCT FROM expected_snapshot OR NEW.rule_revision<>live_rule.revision THEN
            RAISE EXCEPTION 'replenishment snapshot differs from rule' USING ERRCODE='23514';
        END IF;
    END IF;
    FOREACH property IN ARRAY ARRAY['availableBase','reservedBase','confirmedInboundBase'] LOOP
        IF jsonb_typeof(NEW.position_snapshot->property) IS DISTINCT FROM 'string' OR
            (NEW.position_snapshot->>property) !~ '^[0-9]+$' THEN
            RAISE EXCEPTION 'exact replenishment position required' USING ERRCODE='23514';
        END IF;
    END LOOP;
    available:=(NEW.position_snapshot->>'availableBase')::numeric;
    inbound:=(NEW.position_snapshot->>'confirmedInboundBase')::numeric;
    target:=(NEW.rule_snapshot->>'targetBase')::numeric;
    minimum:=(NEW.rule_snapshot->>'minimumBase')::numeric;
    multiple:=(NEW.rule_snapshot->>'packageMultipleBase')::numeric;
    IF available+inbound>=minimum OR multiple<=0 OR
        NEW.quantity_base IS DISTINCT FROM ceil((target-available-inbound)/multiple)*multiple THEN
        RAISE EXCEPTION 'replenishment quantity differs from exact deficit' USING ERRCODE='23514';
    END IF;
    IF NEW.receiving_line_id IS NOT NULL THEN
        IF NOT EXISTS(SELECT FROM inventory_document_line line JOIN inventory_document document
            ON document.tenant_id=line.tenant_id AND document.id=line.document_id
            WHERE line.tenant_id=NEW.tenant_id AND line.id=NEW.receiving_line_id AND line.document_id=NEW.source_document_id
            AND document.kind='RECEIPT' AND document.revision>=NEW.receiving_revision AND document.state<>'DRAFT'
            AND line.sku_id=live_rule.sku_id AND line.base_unit=live_rule.base_unit AND line.legal_owner='ISP'
            AND line.quantity_base=NEW.quantity_base) OR EXISTS(SELECT FROM inventory_movement_leg leg
                WHERE leg.tenant_id=NEW.tenant_id AND leg.document_line_id=NEW.receiving_line_id AND leg.direction='IN'
                AND leg.status='AVAILABLE' AND leg.location_id<>live_rule.location_id) THEN
            RAISE EXCEPTION 'receiving source or destination differs from request' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_replenishment_snapshot_binding BEFORE INSERT OR UPDATE ON inventory_replenishment_request
    FOR EACH ROW EXECUTE FUNCTION warehouse_replenishment_snapshot_binding();

CREATE FUNCTION warehouse_replenishment_receiving_destination() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(NEW.tenant_id);
    IF NEW.direction='IN' AND NEW.status='AVAILABLE' AND EXISTS(
        SELECT FROM inventory_replenishment_request request JOIN inventory_replenishment_rule rule
        ON rule.tenant_id=request.tenant_id AND rule.id=request.rule_id
        WHERE request.tenant_id=NEW.tenant_id AND request.receiving_line_id=NEW.document_line_id AND rule.location_id<>NEW.location_id) THEN
        RAISE EXCEPTION 'bound receipt must enter requested location' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER warehouse_replenishment_receiving_destination BEFORE INSERT ON inventory_movement_leg
    FOR EACH ROW EXECUTE FUNCTION warehouse_replenishment_receiving_destination();
