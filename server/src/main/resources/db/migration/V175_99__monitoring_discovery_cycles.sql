ALTER TABLE discovered_onu DROP CONSTRAINT uq_discovered_onu_serial;
CREATE UNIQUE INDEX uq_discovered_onu_unresolved_serial ON discovered_onu(tenant_id,serial_number)
    WHERE state IN ('DISCOVERED','IGNORED');
CREATE FUNCTION monitoring_resolved_discovery_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM warehouse_assert_deferred_scope(OLD.tenant_id);
    IF OLD.state='PROVISIONED' AND (TG_OP='DELETE' OR NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'resolved discovery history is immutable' USING ERRCODE='23514';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER monitoring_resolved_discovery_guard BEFORE UPDATE OR DELETE ON discovered_onu
    FOR EACH ROW EXECUTE FUNCTION monitoring_resolved_discovery_guard();
