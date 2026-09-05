CREATE TABLE provisioning_vlan_allocation_scope (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    allocation_id uuid NOT NULL,
    pool_id uuid NOT NULL,
    mode varchar(20) NOT NULL,
    pop_id uuid,
    olt_id uuid,
    area_id uuid,
    service_class_id uuid,
    intent_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_provisioning_allocation_scope_allocation FOREIGN KEY (allocation_id, tenant_id)
        REFERENCES provisioning_vlan_allocation (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_provisioning_allocation_scope_pool FOREIGN KEY (pool_id, tenant_id)
        REFERENCES provisioning_vlan_pool (id, tenant_id),
    CONSTRAINT uq_provisioning_allocation_scope_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT uq_provisioning_allocation_scope_allocation UNIQUE (tenant_id, allocation_id),
    CONSTRAINT ck_provisioning_allocation_scope_mode CHECK (mode IN ('SHARED', 'DEDICATED')),
    CONSTRAINT ck_provisioning_allocation_scope_shape CHECK (
        (mode = 'SHARED'
            AND pop_id IS NOT NULL
            AND olt_id IS NOT NULL
            AND area_id IS NOT NULL
            AND service_class_id IS NOT NULL
            AND intent_id IS NULL)
        OR (mode = 'DEDICATED'
            AND pop_id IS NULL
            AND olt_id IS NULL
            AND area_id IS NULL
            AND service_class_id IS NULL
            AND intent_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_provisioning_shared_allocation_scope
    ON provisioning_vlan_allocation_scope (tenant_id, pop_id, olt_id, area_id, service_class_id)
    WHERE mode = 'SHARED';

CREATE UNIQUE INDEX uq_provisioning_dedicated_allocation_intent
    ON provisioning_vlan_allocation_scope (tenant_id, intent_id)
    WHERE mode = 'DEDICATED';

CREATE INDEX idx_provisioning_allocation_scope_pool
    ON provisioning_vlan_allocation_scope (tenant_id, pool_id);

ALTER TABLE provisioning_vlan_allocation_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE provisioning_vlan_allocation_scope FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provisioning_vlan_allocation_scope
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
