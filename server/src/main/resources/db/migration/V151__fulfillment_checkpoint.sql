CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE fulfillment_checkpoint (
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
CREATE INDEX fulfillment_checkpoint_pending_idx ON fulfillment_checkpoint (tenant_id, state, checkpoint_updated_at);
ALTER TABLE fulfillment_checkpoint ENABLE ROW LEVEL SECURITY;
ALTER TABLE fulfillment_checkpoint FORCE ROW LEVEL SECURITY;
CREATE POLICY fulfillment_checkpoint_tenant_isolation ON fulfillment_checkpoint
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE migration_fulfillment_inbox
    ADD COLUMN IF NOT EXISTS canonical_hash char(64);
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

CREATE TABLE fulfillment_outbox (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    fulfillment_id uuid NOT NULL REFERENCES fulfillment_checkpoint(id),
    sequence bigint NOT NULL,
    event_type varchar(120) NOT NULL,
    payload_hash char(64) NOT NULL,
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, fulfillment_id, sequence)
);
ALTER TABLE fulfillment_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE fulfillment_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY fulfillment_outbox_tenant_isolation ON fulfillment_outbox
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
