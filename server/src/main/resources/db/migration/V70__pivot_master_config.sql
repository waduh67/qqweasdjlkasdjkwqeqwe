-- ============================================================
-- Setelan MASTER Pivot milik platform (singleton global, PLATFORM-level — tanpa RLS, pola
-- `platform_setting`). Menyimpan kredensial akun master yang menampung SEMUA transaksi
-- (tagihan pelanggan tenant + langganan SaaS tenant), fee platform per transaksi, dan
-- rekening payout platform.
--
-- Kredensial (merchant_id/merchant_secret/callback_api_key) TERENKRIPSI — batas enkripsi di
-- adapter persistence, DB tak pernah melihat rahasia asli (pola tenant_payment_gateway lama).
--
-- Fee platform (platform_fee_minor + platform_fee_type) dipotong dari hasil tenant lewat
-- Split Routing Pivot saat charge pelanggan (di LUAR fee Pivot sendiri).
-- ============================================================

CREATE TABLE pivot_master_config (
    id                     uuid PRIMARY KEY,
    enabled                boolean     NOT NULL DEFAULT false,
    -- Ciphertext. merchant_id = X-MERCHANT-ID; merchant_secret = X-MERCHANT-SECRET;
    -- callback_api_key = verifikasi header X-API-Key seluruh webhook Pivot.
    merchant_id            varchar(1024),
    merchant_secret        varchar(1024),
    callback_api_key       varchar(1024),
    -- Lingkungan: true → api-stg.pivot-payment.com, false → api.pivot-payment.com.
    sandbox                boolean     NOT NULL DEFAULT false,
    -- Fee platform per transaksi (minor unit IDR, mis. 1000). 0 = tanpa split-routing.
    platform_fee_minor     bigint      NOT NULL DEFAULT 0,
    platform_fee_type      varchar(20) NOT NULL DEFAULT 'FIXED',
    -- Rekening payout platform (non-rahasia) — tujuan withdrawal saldo master.
    payout_channel_code    varchar(40),
    payout_account_number  varchar(60),
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_pivot_master_fee_type CHECK (platform_fee_type IN ('FIXED', 'PERCENTAGE')),
    CONSTRAINT ck_pivot_master_fee_nonneg CHECK (platform_fee_minor >= 0)
);
