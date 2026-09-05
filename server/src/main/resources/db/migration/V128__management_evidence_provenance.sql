ALTER TABLE provisioning_management_safety_evidence
    ADD COLUMN complete boolean NOT NULL DEFAULT false,
    ADD COLUMN source_type varchar(30),
    ADD COLUMN topology_source_id uuid,
    ADD COLUMN device_observation_source_id uuid;

ALTER TABLE provisioning_management_safety_evidence
    ADD CONSTRAINT ck_provisioning_management_evidence_source CHECK (
        (complete = false AND source_type IS NULL AND topology_source_id IS NULL AND device_observation_source_id IS NULL)
        OR (complete = true AND source_type = 'TOPOLOGY_OBSERVATION' AND topology_source_id IS NOT NULL
            AND device_observation_source_id IS NULL)
        OR (complete = true AND source_type = 'DEVICE_OBSERVATION' AND device_observation_source_id IS NOT NULL
            AND topology_source_id IS NULL)
    ),
    ADD CONSTRAINT fk_provisioning_management_topology_source FOREIGN KEY (topology_source_id, tenant_id)
        REFERENCES provisioning_managed_node (id, tenant_id),
    ADD CONSTRAINT fk_provisioning_management_observation_source FOREIGN KEY (device_observation_source_id, tenant_id)
        REFERENCES provisioning_device_observation (id, tenant_id);

ALTER TABLE provisioning_management_safety_evidence
    DROP COLUMN mutation_interface_roles,
    DROP COLUMN mutation_ip_addresses,
    DROP COLUMN mutation_vrfs,
    DROP COLUMN mutation_collector_paths,
    DROP COLUMN mutation_required_oob_routes,
    DROP COLUMN mutation_changed_oob_routes;
