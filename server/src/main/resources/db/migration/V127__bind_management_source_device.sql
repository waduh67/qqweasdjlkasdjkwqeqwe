ALTER TABLE provisioning_device_observation
    ADD CONSTRAINT uq_provisioning_observation_exact_source UNIQUE (id, tenant_id, device_kind, device_id);

ALTER TABLE provisioning_management_safety_evidence
    DROP CONSTRAINT fk_provisioning_management_observation_source,
    ADD CONSTRAINT fk_provisioning_management_observation_source FOREIGN KEY (
        device_observation_source_id, tenant_id, device_kind, device_id
    ) REFERENCES provisioning_device_observation (
        id, tenant_id, device_kind, device_id
    );

CREATE FUNCTION provisioning_management_source_matches(
    source_type_value varchar,
    tenant_value uuid,
    device_kind_value varchar,
    device_id_value uuid,
    topology_source_value uuid,
    observation_source_value uuid
) RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT CASE source_type_value
        WHEN 'TOPOLOGY_OBSERVATION' THEN EXISTS (
            SELECT 1 FROM provisioning_managed_node node
            WHERE node.tenant_id = tenant_value
              AND node.id = topology_source_value
              AND (
                  (device_kind_value = 'OLT' AND node.role = 'OLT' AND node.reference_kind = 'OLT' AND node.reference_id = device_id_value)
                  OR (device_kind_value = 'BRAS' AND node.role = 'BRAS' AND node.reference_kind = 'NAS' AND node.reference_id = device_id_value)
                  OR (device_kind_value = 'SWITCH' AND node.role IN ('ACCESS_SWITCH', 'AGGREGATION_SWITCH') AND node.id = device_id_value)
              )
        )
        WHEN 'DEVICE_OBSERVATION' THEN EXISTS (
            SELECT 1 FROM provisioning_device_observation observation
            WHERE observation.tenant_id = tenant_value
              AND observation.id = observation_source_value
              AND observation.device_kind = device_kind_value
              AND observation.device_id = device_id_value
        )
        ELSE false
    END
$$;

DO $$
DECLARE current_tenant uuid;
BEGIN
    FOR current_tenant IN SELECT id FROM tenant LOOP
        PERFORM set_config('app.tenant_id', current_tenant::text, true);
        UPDATE provisioning_management_safety_evidence evidence
        SET complete = false,
            source_type = NULL,
            topology_source_id = NULL,
            device_observation_source_id = NULL
        WHERE evidence.tenant_id = current_tenant
          AND evidence.complete
          AND NOT provisioning_management_source_matches(
              evidence.source_type,
              evidence.tenant_id,
              evidence.device_kind,
              evidence.device_id,
              evidence.topology_source_id,
              evidence.device_observation_source_id
          );
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;

CREATE FUNCTION provisioning_enforce_management_source_identity()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.complete AND NOT provisioning_management_source_matches(
        NEW.source_type,
        NEW.tenant_id,
        NEW.device_kind,
        NEW.device_id,
        NEW.topology_source_id,
        NEW.device_observation_source_id
    ) THEN
        RAISE EXCEPTION 'MANAGEMENT_EVIDENCE_SOURCE_DEVICE_MISMATCH';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_provisioning_management_source_identity
BEFORE INSERT OR UPDATE ON provisioning_management_safety_evidence
FOR EACH ROW EXECUTE FUNCTION provisioning_enforce_management_source_identity();
