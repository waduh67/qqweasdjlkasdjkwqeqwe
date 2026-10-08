ALTER TABLE role ADD COLUMN default_key varchar(40);
CREATE UNIQUE INDEX role_tenant_default_key_uq ON role (tenant_id, default_key) WHERE default_key IS NOT NULL;

ALTER TABLE role DISABLE ROW LEVEL SECURITY;
UPDATE role SET default_key = CASE name
    WHEN 'Tenant Admin' THEN 'TENANT_OWNER_LEGACY'
    WHEN 'Super Admin' THEN 'PLATFORM_ADMIN'
    WHEN 'Teknisi' THEN 'TECHNICIAN_LEGACY' END
WHERE system_role AND name IN ('Tenant Admin', 'Super Admin', 'Teknisi');
ALTER TABLE role ENABLE ROW LEVEL SECURITY;

CREATE TABLE iam_tenant_owner (
    tenant_id uuid PRIMARY KEY REFERENCES tenant(id) ON DELETE CASCADE,
    user_id uuid NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    assigned_by uuid,
    FOREIGN KEY (tenant_id, user_id) REFERENCES app_user(tenant_id, id) ON DELETE CASCADE
);
ALTER TABLE iam_tenant_owner ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam_tenant_owner FORCE ROW LEVEL SECURITY;
CREATE POLICY iam_tenant_owner_tenant_policy ON iam_tenant_owner
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
