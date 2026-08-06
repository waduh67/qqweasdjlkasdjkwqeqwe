-- ============================================================
-- Sub-account Pivot per-tenant (tenant-scoped + RLS). Menggantikan kredensial gateway BYO:
-- tenant tak lagi memasang akun sendiri — platform membuatkan sub-account di akun master saat
-- onboarding (default NON_KYC), dan seluruh charge pelanggan tenant dibuat ON-BEHALF-OF
-- sub_merchant_uuid ini (header `x-submerchant-id`).
--
-- NON_KYC : transaksi atas nama platform FTTH; dana masuk balance master; payout ke rekening
--           tenant dilakukan platform (manual/terjadwal).
-- KYC     : tenant verifikasi sendiri; transaksi & saldo atas nama tenant; tenant tarik sendiri.
--
-- Semua kolom NON-rahasia (uuid sub-account, status, rekening payout bukan kredensial).
-- Rekening payout + inquiry_id (hasil validasi POST /v1/inquiry-account) dipakai POST /v1/payouts.
-- ============================================================

CREATE TABLE tenant_pivot_account (
    id                     uuid PRIMARY KEY,
    tenant_id              uuid        NOT NULL REFERENCES tenant (id),
    -- UUID sub-account di Pivot (x-submerchant-id). NULL = belum diprovisikan.
    sub_merchant_uuid      varchar(128),
    account_type           varchar(20) NOT NULL DEFAULT 'NON_KYC',
    sub_account_status     varchar(30) NOT NULL DEFAULT 'NOT_PROVISIONED',
    kyc_status             varchar(30) NOT NULL DEFAULT 'NOT_REQUIRED',
    -- Transaction descriptor (nama singkat yang muncul di mutasi pelanggan).
    short_name             varchar(120),
    -- Rekening payout tenant + hasil validasi inquiry (nama + inquiryId).
    payout_channel_code    varchar(40),
    payout_account_number  varchar(60),
    payout_account_name    varchar(160),
    payout_inquiry_id      varchar(128),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_pivot_account_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_tpa_type CHECK (account_type IN ('NON_KYC', 'KYC')),
    CONSTRAINT ck_tpa_status CHECK (sub_account_status IN ('NOT_PROVISIONED', 'CREATED', 'ACTIVE', 'DEACTIVATED', 'REJECTED')),
    CONSTRAINT ck_tpa_kyc CHECK (kyc_status IN ('NOT_REQUIRED', 'WAITING_FOR_DOCUMENT', 'IN_REVIEW', 'APPROVED', 'REJECTED'))
);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain — lihat V50).
-- ------------------------------------------------------------
ALTER TABLE tenant_pivot_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_pivot_account FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_pivot_account
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
