CREATE TABLE customer_import_batch (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    operation_key varchar(240) NOT NULL,
    sha256 char(64) NOT NULL,
    mode varchar(32) NOT NULL,
    state varchar(24) NOT NULL,
    object_key varchar(500) NOT NULL,
    row_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    committed_at timestamptz,
    legal_hold boolean NOT NULL DEFAULT false,
    CONSTRAINT customer_import_batch_operation_uq UNIQUE (tenant_id, operation_key),
    CONSTRAINT customer_import_batch_identity_uq UNIQUE (tenant_id, sha256, mode)
);
CREATE INDEX customer_import_batch_retention_idx ON customer_import_batch (tenant_id, created_at, legal_hold);
ALTER TABLE customer_import_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_import_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY customer_import_batch_tenant_policy ON customer_import_batch
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE customer_import_staging_row (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    row_number integer NOT NULL,
    payload jsonb NOT NULL,
    business_key_hash char(64),
    CONSTRAINT customer_import_staging_row_uq UNIQUE (batch_id, row_number),
    CONSTRAINT customer_import_staging_row_batch_fk FOREIGN KEY (batch_id) REFERENCES customer_import_batch(id) ON DELETE CASCADE
);
CREATE INDEX customer_import_staging_tenant_idx ON customer_import_staging_row (tenant_id, batch_id);
ALTER TABLE customer_import_staging_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_import_staging_row FORCE ROW LEVEL SECURITY;
CREATE POLICY customer_import_staging_tenant_policy ON customer_import_staging_row
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE customer_import_error (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    row_number integer NOT NULL,
    column_name varchar(100),
    code varchar(40) NOT NULL,
    message varchar(500) NOT NULL,
    CONSTRAINT customer_import_error_batch_fk FOREIGN KEY (batch_id) REFERENCES customer_import_batch(id) ON DELETE CASCADE
);
ALTER TABLE customer_import_error ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_import_error FORCE ROW LEVEL SECURITY;
CREATE POLICY customer_import_error_tenant_policy ON customer_import_error
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE customer_import_outbox (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    operation_key varchar(240) NOT NULL,
    event_type varchar(80) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    CONSTRAINT customer_import_outbox_operation_uq UNIQUE (tenant_id, operation_key, event_type),
    CONSTRAINT customer_import_outbox_batch_fk FOREIGN KEY (batch_id) REFERENCES customer_import_batch(id)
);
ALTER TABLE customer_import_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_import_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY customer_import_outbox_tenant_policy ON customer_import_outbox
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
