ALTER TABLE customer_import_batch
    ADD COLUMN IF NOT EXISTS import_type varchar(40) NOT NULL DEFAULT 'CUSTOMERS_CSV',
    ADD COLUMN IF NOT EXISTS commit_operation_key varchar(240),
    ADD COLUMN IF NOT EXISTS commit_hash char(64);
ALTER TABLE customer_import_staging_row
    ADD COLUMN IF NOT EXISTS credential_handle_id uuid;
UPDATE customer_import_batch
SET retention_until = created_at + interval '90 days'
WHERE retention_until IS NULL;

DROP INDEX IF EXISTS customer_import_batch_file_identity_uq;
CREATE UNIQUE INDEX customer_import_batch_file_identity_uq
    ON customer_import_batch (tenant_id, import_type, schema_version, sha256);
CREATE UNIQUE INDEX customer_import_batch_commit_identity_uq
    ON customer_import_batch (tenant_id, commit_operation_key)
    WHERE commit_operation_key IS NOT NULL;
CREATE UNIQUE INDEX customer_import_batch_retry_identity_uq
    ON customer_import_batch (tenant_id, import_type, schema_version, mode, operation_key);

CREATE TABLE customer_import_retention_audit (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    outcome varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT customer_import_retention_audit_batch_fk FOREIGN KEY (batch_id) REFERENCES customer_import_batch(id)
);
ALTER TABLE customer_import_retention_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_import_retention_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY customer_import_retention_audit_tenant_policy ON customer_import_retention_audit
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
