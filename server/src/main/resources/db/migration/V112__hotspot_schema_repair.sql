-- Repairs installations where an earlier applied hotspot migration omitted later voucher fields.
-- Keep every addition nullable: existing historical rows may not carry enough data to
-- safely infer their immutable voucher or site attributes.
ALTER TABLE hotspot_site
    ADD COLUMN IF NOT EXISTS nas_id uuid,
    ADD COLUMN IF NOT EXISTS portal_id varchar(22),
    ADD COLUMN IF NOT EXISTS name varchar(120),
    ADD COLUMN IF NOT EXISTS location varchar(300),
    ADD COLUMN IF NOT EXISTS portal_mode varchar(20),
    ADD COLUMN IF NOT EXISTS branding_display_name varchar(100),
    ADD COLUMN IF NOT EXISTS branding_logo_url varchar(500),
    ADD COLUMN IF NOT EXISTS default_plan_id uuid;

ALTER TABLE hotspot_voucher_batch
    ADD COLUMN IF NOT EXISTS site_id uuid,
    ADD COLUMN IF NOT EXISTS plan_id uuid,
    ADD COLUMN IF NOT EXISTS duration_seconds bigint;

ALTER TABLE hotspot_voucher
    ADD COLUMN IF NOT EXISTS batch_id uuid REFERENCES hotspot_voucher_batch (id),
    ADD COLUMN IF NOT EXISTS username varchar(64),
    ADD COLUMN IF NOT EXISTS password_ciphertext varchar(512),
    ADD COLUMN IF NOT EXISTS site_id uuid,
    ADD COLUMN IF NOT EXISTS plan_id uuid,
    ADD COLUMN IF NOT EXISTS duration_seconds bigint,
    ADD COLUMN IF NOT EXISTS activated_at timestamptz,
    ADD COLUMN IF NOT EXISTS expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS device_id varchar(255),
    ADD COLUMN IF NOT EXISTS revoked_at timestamptz,
    ADD COLUMN IF NOT EXISTS revoked_by uuid,
    ADD COLUMN IF NOT EXISTS revocation_reason varchar(500);

-- Backfill only deterministic expiry values. Rows without both source values stay
-- unchanged rather than receiving a fabricated lifetime.
UPDATE hotspot_voucher
SET expires_at = activated_at + (duration_seconds * INTERVAL '1 second')
WHERE status = 'ACTIVE'
  AND expires_at IS NULL
  AND activated_at IS NOT NULL
  AND duration_seconds IS NOT NULL
  AND duration_seconds > 0;

CREATE INDEX IF NOT EXISTS ix_hotspot_site_tenant_portal_id
    ON hotspot_site (tenant_id, portal_id);
CREATE INDEX IF NOT EXISTS ix_hotspot_voucher_tenant_username
    ON hotspot_voucher (tenant_id, username);
CREATE INDEX IF NOT EXISTS ix_hotspot_voucher_tenant_expires_at
    ON hotspot_voucher (tenant_id, expires_at);
