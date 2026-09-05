CREATE TABLE customer_import_credential (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    ciphertext text NOT NULL,
    state varchar(16) NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX customer_import_credential_state_idx
    ON customer_import_credential (tenant_id, state, created_at);
ALTER TABLE customer_import_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_import_credential FORCE ROW LEVEL SECURITY;
CREATE POLICY customer_import_credential_tenant_policy ON customer_import_credential
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
