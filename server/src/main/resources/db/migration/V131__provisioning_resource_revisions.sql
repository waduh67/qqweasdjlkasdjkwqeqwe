CREATE TABLE provisioning_resource_revision (
    tenant_id uuid NOT NULL REFERENCES tenant (id),
    resource_type varchar(40) NOT NULL,
    resource_id uuid NOT NULL,
    revision integer NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, resource_type, resource_id),
    CONSTRAINT ck_provisioning_resource_revision CHECK (revision > 0)
);

ALTER TABLE provisioning_resource_revision ENABLE ROW LEVEL SECURITY;
ALTER TABLE provisioning_resource_revision FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provisioning_resource_revision
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
