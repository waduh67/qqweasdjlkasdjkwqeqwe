-- ============================================================
-- Payment gateway per-tenant: tiap tenant memilih penyedia + mode + kredensialnya
-- sendiri, menggantikan satu gateway global (`ftth.billing.default-provider`).
--
-- Dua mode:
--   BYO       tenant memasang akun gateway-nya sendiri (Xendit dulu; Paywuz/Pivot menyusul).
--   PLATFORM  tenant memakai akun MASTER platform via sub-account xenPlatform (Xendit);
--             platform menampung & menyelesaikan pembayaran lalu memotong komisi.
--
-- Kredensial (api_key/secret_key/webhook_token) disimpan TERENKRIPSI — batas enkripsi
-- ada di adapter persistence, DB tak pernah melihat rahasia asli (sama seperti token WA
-- notifikasi & secret CoA BRAS). Kredensial MASTER platform TIDAK di sini melainkan di
-- config/env, agar charge/callback tak perlu membaca lintas-RLS.
--
-- Satu baris config per tenant. Default aman: MANUAL, BYO, MATI — perilaku lama (webhook
-- MANUAL bersecret global) tetap berlaku sampai tenant mengonfigurasi gateway dengan sadar.
-- ============================================================

CREATE TABLE tenant_payment_gateway (
    id             uuid PRIMARY KEY,
    tenant_id      uuid        NOT NULL REFERENCES tenant (id),
    provider       varchar(20) NOT NULL DEFAULT 'MANUAL',
    mode           varchar(20) NOT NULL DEFAULT 'BYO',
    enabled        boolean     NOT NULL DEFAULT false,
    -- Ciphertext. api_key = penyedia key-pair (Paywuz pk_.../Pivot); secret_key = Xendit BYO
    -- secret key; webhook_token = token verifikasi callback per-tenant (PLATFORM = token sub-account).
    api_key        varchar(1024),
    secret_key     varchar(1024),
    webhook_token  varchar(1024),
    -- PLATFORM: user_id sub-account Xendit (dipasang di header for-user-id saat charge).
    sub_account_id varchar(128),
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    -- Satu baris config per tenant.
    CONSTRAINT uq_tenant_payment_gateway_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_tpg_provider CHECK (provider IN ('XENDIT', 'PAYWUZ', 'PIVOT', 'MANUAL')),
    CONSTRAINT ck_tpg_mode CHECK (mode IN ('BYO', 'PLATFORM'))
);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE tenant_payment_gateway ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_payment_gateway FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_payment_gateway
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
