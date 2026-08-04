-- ============================================================
-- Langganan tenant ke aplikasi (SaaS) — flat fee bulanan per tenant.
--
-- PLATFORM-level (tanpa RLS): dikelola super-admin lintas-tenant. Satu baris per
-- tenant. `monthly_fee` di-set super-admin (flat, tanpa tingkatan paket).
--
-- status:
--   ACTIVE    langganan berjalan normal.
--   PAST_DUE  ada tagihan lewat jatuh tempo (belum lewat grace).
--   SUSPENDED lewat grace → tenant di-suspend (Tenant.suspend()).
--   CANCELLED dihentikan super-admin.
-- billing_day/grace_days null = ikut default global (platform_setting).
-- ============================================================

CREATE TABLE tenant_subscription (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid           NOT NULL REFERENCES tenant (id),
    monthly_fee           numeric(14, 2) NOT NULL,
    status                varchar(20)    NOT NULL DEFAULT 'ACTIVE',
    billing_day           int,
    grace_days            int,
    current_period_start  date,
    current_period_end    date,
    next_invoice_at       date,
    activated_at          timestamptz,
    created_at            timestamptz    NOT NULL DEFAULT now(),
    updated_at            timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_subscription_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_tenant_subscription_status
        CHECK (status IN ('ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED'))
);

CREATE INDEX ix_tenant_subscription_next_invoice
    ON tenant_subscription (next_invoice_at)
    WHERE status IN ('ACTIVE', 'PAST_DUE');
