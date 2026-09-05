CREATE TABLE provisioning_management_safety_evidence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    device_kind varchar(20) NOT NULL,
    device_id uuid NOT NULL,
    protected_vlan_ranges text NOT NULL,
    protected_ip_prefixes text NOT NULL,
    protected_vrfs text NOT NULL,
    protected_interface_roles text NOT NULL,
    protected_collector_paths text NOT NULL,
    protected_oob_routes text NOT NULL,
    mutation_interface_roles text NOT NULL,
    mutation_ip_addresses text NOT NULL,
    mutation_vrfs text NOT NULL,
    mutation_collector_paths text NOT NULL,
    mutation_required_oob_routes text NOT NULL,
    mutation_changed_oob_routes text NOT NULL,
    available_oob_routes text NOT NULL,
    observed_at timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_provisioning_management_safety_device UNIQUE (tenant_id, device_kind, device_id),
    CONSTRAINT ck_provisioning_management_safety_device_kind CHECK (device_kind IN ('OLT', 'SWITCH', 'ROUTER', 'BRAS')),
    CONSTRAINT ck_provisioning_management_safety_validity CHECK (valid_until > observed_at)
);

ALTER TABLE provisioning_management_safety_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE provisioning_management_safety_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provisioning_management_safety_evidence
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
