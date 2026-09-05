DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['evidence_object_registry', 'wo_evidence', 'wo_signature'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS purge_state varchar(20) NOT NULL DEFAULT ''ACTIVE''', table_name);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS purge_claim_id uuid', table_name);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS purge_claimed_at timestamptz', table_name);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS row_version bigint NOT NULL DEFAULT 0', table_name);
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT IF EXISTS ck_%s_purge_state', table_name, table_name);
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT ck_%s_purge_state CHECK (purge_state IN (''ACTIVE'', ''CLAIMED'', ''DELETED'', ''RECONCILE''))', table_name, table_name);
        EXECUTE format('CREATE INDEX IF NOT EXISTS ix_%s_purge_state ON %I (tenant_id, purge_state, purge_claimed_at)', table_name, table_name);
    END LOOP;
END $$;
