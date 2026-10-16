ALTER TABLE subscription ADD COLUMN billing_managed boolean NOT NULL DEFAULT true;
ALTER TABLE subscription ADD COLUMN billing_starts_on date;
ALTER TABLE subscription ADD CONSTRAINT subscription_billing_start_check CHECK (billing_managed OR billing_starts_on IS NULL);
CREATE UNIQUE INDEX customer_migration_nas_tenant_uq ON nas(tenant_id,id);

CREATE TABLE customer_migration (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    subscription_id uuid NOT NULL,
    operation_key varchar(240) NOT NULL,
    request_hash varchar(64) NOT NULL,
    source_key varchar(240) NOT NULL,
    source varchar(16) NOT NULL CHECK (source IN ('MANUAL','CSV','MIKROTIK')),
    username varchar(200),
    secret_ciphertext text,
    auth_type varchar(16) NOT NULL CHECK (auth_type IN ('PPPOE','HOTSPOT','DHCP','STATIC')),
    nas_id uuid,
    framed_ip varchar(45),
    access_id uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE(tenant_id,operation_key),
    UNIQUE(tenant_id,source_key),
    UNIQUE(tenant_id,customer_id),
    FOREIGN KEY(tenant_id,customer_id) REFERENCES customer(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id,subscription_id) REFERENCES subscription(tenant_id,id) ON DELETE CASCADE,
    FOREIGN KEY(tenant_id,nas_id) REFERENCES nas(tenant_id,id),
    FOREIGN KEY(tenant_id,access_id) REFERENCES subscriber_access(tenant_id,id)
);
ALTER TABLE customer_migration ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_migration FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_migration
    USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid)
    WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::uuid);
