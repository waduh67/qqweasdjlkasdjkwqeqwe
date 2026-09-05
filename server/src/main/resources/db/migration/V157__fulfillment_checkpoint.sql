CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS fulfillment_checkpoint (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    namespace varchar(120) NOT NULL,
    operation_key varchar(240) NOT NULL,
    canonical_hash char(64) NOT NULL,
    source varchar(24) NOT NULL,
    target_id uuid NOT NULL,
    state varchar(32) NOT NULL,
    last_effect varchar(32),
    attempts integer NOT NULL DEFAULT 0,
    outcome varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    checkpoint_updated_at timestamptz NOT NULL,
    UNIQUE (tenant_id, namespace, operation_key),
    CHECK (state IN ('READY', 'DISPATCHED', 'APPLYING', 'APPLIED', 'FAILED_RETRYABLE', 'REQUIRES_RECONCILIATION', 'MANUAL_RESOLVED', 'FAILED_PERMANENT')),
    CHECK (canonical_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX IF NOT EXISTS fulfillment_checkpoint_pending_idx ON fulfillment_checkpoint (tenant_id, state, checkpoint_updated_at);
ALTER TABLE fulfillment_checkpoint ENABLE ROW LEVEL SECURITY;
ALTER TABLE fulfillment_checkpoint FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fulfillment_checkpoint_tenant_isolation ON fulfillment_checkpoint;
CREATE POLICY fulfillment_checkpoint_tenant_isolation ON fulfillment_checkpoint
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE fulfillment_checkpoint ADD COLUMN IF NOT EXISTS checkpoint_updated_at timestamptz;
UPDATE fulfillment_checkpoint SET checkpoint_updated_at = COALESCE(checkpoint_updated_at, updated_at) WHERE checkpoint_updated_at IS NULL;
ALTER TABLE fulfillment_checkpoint ALTER COLUMN checkpoint_updated_at SET NOT NULL;

ALTER TABLE migration_fulfillment_inbox ADD COLUMN IF NOT EXISTS canonical_hash char(64);
UPDATE migration_fulfillment_inbox
SET canonical_hash = encode(digest(operation_key || ':' || subscription_id::text, 'sha256'), 'hex')
WHERE canonical_hash IS NULL;
ALTER TABLE migration_fulfillment_inbox ALTER COLUMN canonical_hash SET NOT NULL;
ALTER TABLE migration_fulfillment_inbox
    ADD COLUMN IF NOT EXISTS attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_error varchar(1000),
    ADD COLUMN IF NOT EXISTS approved_at timestamptz,
    ADD COLUMN IF NOT EXISTS approved_by uuid,
    ADD COLUMN IF NOT EXISTS applied_fulfillment_id uuid;

ALTER TABLE customer_import_outbox ADD COLUMN IF NOT EXISTS updated_at timestamptz;
UPDATE customer_import_outbox SET updated_at = COALESCE(updated_at, created_at) WHERE updated_at IS NULL;
ALTER TABLE customer_import_outbox ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE customer_import_outbox ALTER COLUMN updated_at SET NOT NULL;

CREATE TABLE IF NOT EXISTS fulfillment_outbox (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    fulfillment_id uuid NOT NULL REFERENCES fulfillment_checkpoint(id),
    sequence bigint NOT NULL,
    event_type varchar(120) NOT NULL,
    payload_hash char(64) NOT NULL,
    payload text NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, fulfillment_id, sequence)
);
ALTER TABLE fulfillment_outbox ADD COLUMN IF NOT EXISTS payload text;
UPDATE fulfillment_outbox SET payload = '' WHERE payload IS NULL;
ALTER TABLE fulfillment_outbox ALTER COLUMN payload SET NOT NULL;
ALTER TABLE fulfillment_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE fulfillment_outbox FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fulfillment_outbox_tenant_isolation ON fulfillment_outbox;
CREATE POLICY fulfillment_outbox_tenant_isolation ON fulfillment_outbox
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
