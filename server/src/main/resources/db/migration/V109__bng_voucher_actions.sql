ALTER TABLE bng_action ADD COLUMN credential_ciphertext varchar(512);
ALTER TABLE bng_action ADD COLUMN external_id varchar(128);
CREATE INDEX ix_bng_action_voucher_external ON bng_action (tenant_id, external_id, requested_at DESC)
    WHERE external_id IS NOT NULL;
