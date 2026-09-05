ALTER TABLE provisioning_service_intent
    ADD COLUMN access_olt_id uuid,
    ADD COLUMN access_pon_port_id uuid,
    ADD COLUMN access_onu_id uuid,
    ADD CONSTRAINT ck_provisioning_intent_access_binding CHECK (
        (access_olt_id IS NULL AND access_pon_port_id IS NULL AND access_onu_id IS NULL)
        OR (access_olt_id IS NOT NULL AND access_pon_port_id IS NOT NULL AND access_onu_id IS NOT NULL)
    );

CREATE INDEX ix_provisioning_intent_access_binding
    ON provisioning_service_intent (tenant_id, access_olt_id, access_pon_port_id, access_onu_id);

CREATE FUNCTION prevent_service_access_binding_retarget() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.access_olt_id IS NOT NULL AND (
        NEW.access_olt_id IS DISTINCT FROM OLD.access_olt_id
        OR NEW.access_pon_port_id IS DISTINCT FROM OLD.access_pon_port_id
        OR NEW.access_onu_id IS DISTINCT FROM OLD.access_onu_id
    ) THEN
        RAISE EXCEPTION 'SERVICE_ACCESS_BINDING_IMMUTABLE';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_service_access_binding_immutable
BEFORE UPDATE ON provisioning_service_intent
FOR EACH ROW EXECUTE FUNCTION prevent_service_access_binding_retarget();
