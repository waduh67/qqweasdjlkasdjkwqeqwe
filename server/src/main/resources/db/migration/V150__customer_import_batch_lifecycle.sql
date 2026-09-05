ALTER TABLE customer_import_batch
    ADD COLUMN IF NOT EXISTS schema_version integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS result jsonb,
    ADD COLUMN IF NOT EXISTS error_code varchar(40),
    ADD COLUMN IF NOT EXISTS retention_until timestamptz,
    ADD COLUMN IF NOT EXISTS report_object_key varchar(500);

ALTER TABLE customer_import_batch DROP CONSTRAINT IF EXISTS customer_import_batch_identity_uq;
CREATE UNIQUE INDEX customer_import_batch_file_identity_uq
    ON customer_import_batch (tenant_id, sha256);
CREATE INDEX customer_import_batch_state_idx
    ON customer_import_batch (tenant_id, state, created_at);
CREATE INDEX customer_import_error_batch_row_idx
    ON customer_import_error (tenant_id, batch_id, row_number);

ALTER TABLE customer_import_outbox
    ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_error_code varchar(40);
