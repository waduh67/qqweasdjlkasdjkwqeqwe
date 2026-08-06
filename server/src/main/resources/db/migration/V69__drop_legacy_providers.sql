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
-- Normalisasi SEMUA baris ke 'PIVOT'|'MANUAL' sebelum CHECK baru — tahan case/spasi & idempotent
-- (aman diulang). Apa pun selain PIVOT (case-insensitive) turun ke MANUAL sebagai fallback aman,
-- termasuk penyedia lama (XENDIT/PAYWUZ/MIDTRANS) & nilai tak terduga dari percobaan migrasi gagal.
--
-- RLS DIMATIKAN sementara saat normalisasi: Flyway jalan sebagai role NOBYPASSRLS & GUC
-- app.tenant_id tak di-set, jadi UPDATE ter-RLS (FORCE) MEMFILTER semua baris → 0 baris ternormalkan,
-- padahal ADD CONSTRAINT di bawah memindai SEMUA baris fisik (lolos RLS) → CHECK gagal oleh baris
-- lama. FORCE ROW LEVEL SECURITY bertahan melewati ENABLE (pola sama V29/V39/V44/V46).
ALTER TABLE tenant_payment_gateway DISABLE ROW LEVEL SECURITY;

UPDATE tenant_payment_gateway
SET provider = CASE WHEN upper(btrim(provider)) = 'PIVOT' THEN 'PIVOT' ELSE 'MANUAL' END
WHERE provider IS DISTINCT FROM 'PIVOT' AND provider IS DISTINCT FROM 'MANUAL';

ALTER TABLE tenant_payment_gateway ENABLE ROW LEVEL SECURITY;

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
