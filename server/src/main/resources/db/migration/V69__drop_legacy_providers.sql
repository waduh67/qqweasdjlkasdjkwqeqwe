-- ============================================================
-- Migrasi penuh payment layer ke Pivot — buang penyedia lama (Xendit/Midtrans/Paywuz)
-- dan model BYOK (bring-your-own-key) per-tenant.
--
-- Model baru: SATU akun MASTER Pivot milik platform menampung semua transaksi; tiap tenant
-- jadi sub-account Pivot (lihat V71 `tenant_pivot_account`). Karena itu:
--   - `tenant_payment_gateway` tak lagi menyimpan kredensial gateway apa pun — cukup penanda
--     penyedia aktif (PIVOT|MANUAL) + konfigurasi pembayaran MANUAL (transfer/QRIS fallback).
--   - `platform_payment_gateway` (kredensial per-penyedia) DIBUANG — diganti singleton
--     `pivot_master_config` (V70).
--   - `platform_setting.active_payment_provider` DIBUANG — hanya ada satu gateway (Pivot).
-- ============================================================

-- ------------------------------------------------------------
-- tenant_payment_gateway — buang kredensial BYO & normalisasi penyedia ke PIVOT|MANUAL.
-- ------------------------------------------------------------
-- Penyedia lama (XENDIT/PAYWUZ/MIDTRANS) turun ke MANUAL (fallback aman) sebelum CHECK baru.
UPDATE tenant_payment_gateway SET provider = 'MANUAL' WHERE provider NOT IN ('PIVOT', 'MANUAL');

ALTER TABLE tenant_payment_gateway DROP CONSTRAINT IF EXISTS ck_tpg_provider;
ALTER TABLE tenant_payment_gateway DROP CONSTRAINT IF EXISTS ck_tpg_mode;

ALTER TABLE tenant_payment_gateway
    DROP COLUMN IF EXISTS mode,
    DROP COLUMN IF EXISTS api_key,
    DROP COLUMN IF EXISTS secret_key,
    DROP COLUMN IF EXISTS webhook_token,
    DROP COLUMN IF EXISTS sub_account_id,
    DROP COLUMN IF EXISTS payment_method;

ALTER TABLE tenant_payment_gateway
    ADD CONSTRAINT ck_tpg_provider CHECK (provider IN ('PIVOT', 'MANUAL'));

-- ------------------------------------------------------------
-- platform_payment_gateway — dibuang (kredensial pindah ke pivot_master_config V70).
-- ------------------------------------------------------------
DROP TABLE IF EXISTS platform_payment_gateway;

-- ------------------------------------------------------------
-- platform_setting — buang pilihan penyedia aktif (hanya Pivot).
-- ------------------------------------------------------------
ALTER TABLE platform_setting DROP CONSTRAINT IF EXISTS ck_platform_setting_provider;
ALTER TABLE platform_setting DROP COLUMN IF EXISTS active_payment_provider;
