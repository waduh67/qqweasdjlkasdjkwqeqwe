ALTER TABLE hotspot_site
    ADD COLUMN IF NOT EXISTS nas_id uuid,
    ADD COLUMN IF NOT EXISTS portal_id varchar(22),
    ADD COLUMN IF NOT EXISTS name varchar(120),
    ADD COLUMN IF NOT EXISTS location varchar(300),
    ADD COLUMN IF NOT EXISTS portal_mode varchar(20),
    ADD COLUMN IF NOT EXISTS branding_display_name varchar(100),
    ADD COLUMN IF NOT EXISTS branding_logo_url varchar(500),
    ADD COLUMN IF NOT EXISTS default_plan_id uuid;

ALTER TABLE hotspot_site
    DROP CONSTRAINT IF EXISTS ck_hotspot_site_portal_mode,
    DROP CONSTRAINT IF EXISTS ck_hotspot_site_portal_id,
    ADD CONSTRAINT ck_hotspot_site_portal_mode CHECK (portal_mode IS NULL OR portal_mode IN ('OFF', 'NAS_OWNED', 'NETOPS_HOSTED')),
    ADD CONSTRAINT ck_hotspot_site_portal_id CHECK (portal_id IS NULL OR portal_id ~ '^[A-Za-z0-9_-]{22}$');

CREATE UNIQUE INDEX IF NOT EXISTS uq_hotspot_site_tenant_nas
    ON hotspot_site (tenant_id, nas_id) WHERE nas_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_hotspot_site_portal_id
    ON hotspot_site (portal_id) WHERE portal_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_hotspot_site_tenant_portal_id
    ON hotspot_site (tenant_id, portal_id);

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

ALTER TABLE hotspot_voucher_batch
    DROP CONSTRAINT IF EXISTS ck_hotspot_voucher_batch_duration,
    DROP CONSTRAINT IF EXISTS ck_hotspot_voucher_batch_status,
    ADD CONSTRAINT ck_hotspot_voucher_batch_duration CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    ADD CONSTRAINT ck_hotspot_voucher_batch_status CHECK (status IN ('OPEN', 'CLOSED'));

ALTER TABLE hotspot_voucher
    DROP CONSTRAINT IF EXISTS ck_hotspot_voucher_duration,
    DROP CONSTRAINT IF EXISTS ck_hotspot_voucher_status,
    DROP CONSTRAINT IF EXISTS ck_hotspot_voucher_lifecycle,
    DROP CONSTRAINT IF EXISTS ck_hotspot_voucher_active_lifecycle,
    DROP CONSTRAINT IF EXISTS ck_hotspot_voucher_revocation,
    ADD CONSTRAINT ck_hotspot_voucher_duration CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    ADD CONSTRAINT ck_hotspot_voucher_status CHECK (status IN ('AVAILABLE', 'ACTIVE', 'EXPIRED', 'REVOKED')),
    ADD CONSTRAINT ck_hotspot_voucher_lifecycle CHECK (
        status <> 'ACTIVE' OR (activated_at IS NOT NULL AND expires_at IS NOT NULL AND device_id IS NOT NULL)
    ),
    ADD CONSTRAINT ck_hotspot_voucher_revocation CHECK (
        status <> 'REVOKED' OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL AND revocation_reason IS NOT NULL)
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_hotspot_voucher_tenant_username
    ON hotspot_voucher (tenant_id, username) WHERE username IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_hotspot_voucher_tenant_username
    ON hotspot_voucher (tenant_id, username);
CREATE INDEX IF NOT EXISTS ix_hotspot_voucher_tenant_expires_at
    ON hotspot_voucher (tenant_id, expires_at);

-- sC* en
