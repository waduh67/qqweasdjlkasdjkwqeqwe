ALTER TABLE fulfillment_checkpoint
    ADD COLUMN IF NOT EXISTS subscription_id uuid,
    ADD COLUMN IF NOT EXISTS work_order_id uuid,
    ADD COLUMN IF NOT EXISTS work_order_kind varchar(32),
    ADD COLUMN IF NOT EXISTS required_effects varchar(240) NOT NULL DEFAULT '';

ALTER TABLE fulfillment_outbox
    ADD COLUMN IF NOT EXISTS claimed_by varchar(120),
    ADD COLUMN IF NOT EXISTS lease_until timestamptz,
    ADD COLUMN IF NOT EXISTS attempts integer NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS fulfillment_outbox_claim_idx
    ON fulfillment_outbox (tenant_id, published_at, lease_until, created_at);

CREATE TABLE IF NOT EXISTS fulfillment_effect_progress (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    fulfillment_id uuid NOT NULL REFERENCES fulfillment_checkpoint(id),
    effect_type varchar(32) NOT NULL,
    status varchar(16) NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    started_at timestamptz,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, fulfillment_id, effect_type),
    CHECK (status IN ('STARTED', 'COMPLETED'))
);

ALTER TABLE fulfillment_effect_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE fulfillment_effect_progress FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS fulfillment_effect_progress_tenant_isolation ON fulfillment_effect_progress;
CREATE POLICY fulfillment_effect_progress_tenant_isolation ON fulfillment_effect_progress
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
