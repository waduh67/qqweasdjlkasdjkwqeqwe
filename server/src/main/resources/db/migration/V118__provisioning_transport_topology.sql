CREATE TABLE provisioning_managed_node (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    name varchar(120) NOT NULL,
    role varchar(30) NOT NULL,
    reference_kind varchar(10),
    reference_id uuid,
    administrative_status varchar(20) NOT NULL,
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_provisioning_managed_node_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT uq_provisioning_managed_node_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_provisioning_managed_node_role CHECK (
        role IN ('OLT', 'ACCESS_SWITCH', 'AGGREGATION_SWITCH', 'BRAS')
    ),
    CONSTRAINT ck_provisioning_managed_node_reference CHECK (
        (role = 'OLT' AND reference_kind = 'OLT' AND reference_id IS NOT NULL)
        OR (role = 'BRAS' AND reference_kind = 'NAS' AND reference_id IS NOT NULL)
        OR (role IN ('ACCESS_SWITCH', 'AGGREGATION_SWITCH') AND reference_kind IS NULL AND reference_id IS NULL)
    ),
    CONSTRAINT ck_provisioning_managed_node_status CHECK (
        administrative_status IN ('ENABLED', 'DISABLED', 'EXCLUDED')
    )
);

CREATE TABLE provisioning_managed_interface (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    node_id uuid NOT NULL,
    name varchar(120) NOT NULL,
    role varchar(20) NOT NULL,
    reference_kind varchar(10),
    reference_id uuid,
    administrative_status varchar(20) NOT NULL,
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_interface_node FOREIGN KEY (node_id, tenant_id)
        REFERENCES provisioning_managed_node (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_provisioning_managed_interface_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT uq_provisioning_managed_interface_name UNIQUE (tenant_id, node_id, name),
    CONSTRAINT ck_provisioning_managed_interface_role CHECK (
        role IN ('ACCESS', 'TRUNK', 'UPLINK', 'MANAGEMENT')
    ),
    CONSTRAINT ck_provisioning_managed_interface_reference CHECK (
        (reference_kind IS NULL AND reference_id IS NULL)
        OR (reference_kind IN ('PON', 'ONU') AND reference_id IS NOT NULL)
    ),
    CONSTRAINT ck_provisioning_managed_interface_status CHECK (
        administrative_status IN ('ENABLED', 'DISABLED', 'EXCLUDED')
    )
);

CREATE TABLE provisioning_transport_link (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    interface_a_id uuid NOT NULL,
    interface_z_id uuid NOT NULL,
    administrative_status varchar(20) NOT NULL,
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_link_interface_a FOREIGN KEY (interface_a_id, tenant_id)
        REFERENCES provisioning_managed_interface (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_provisioning_link_interface_z FOREIGN KEY (interface_z_id, tenant_id)
        REFERENCES provisioning_managed_interface (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT uq_provisioning_transport_link_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_provisioning_transport_link_endpoints CHECK (interface_a_id <> interface_z_id),
    CONSTRAINT ck_provisioning_transport_link_status CHECK (
        administrative_status IN ('ENABLED', 'DISABLED', 'EXCLUDED')
    )
);

CREATE UNIQUE INDEX uq_provisioning_transport_link_endpoints
    ON provisioning_transport_link (
        tenant_id,
        LEAST(interface_a_id, interface_z_id),
        GREATEST(interface_a_id, interface_z_id)
    );

CREATE INDEX idx_provisioning_managed_interface_node
    ON provisioning_managed_interface (tenant_id, node_id);
CREATE INDEX idx_provisioning_transport_link_a
    ON provisioning_transport_link (tenant_id, interface_a_id);
CREATE INDEX idx_provisioning_transport_link_z
    ON provisioning_transport_link (tenant_id, interface_z_id);

DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'provisioning_managed_node',
        'provisioning_managed_interface',
        'provisioning_transport_link'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    END LOOP;
END $$;
