-- ============================================================
-- Harga langganan default GLOBAL (level platform).
--
-- Sebelumnya biaya bulanan hanya di-set per-tenant. Kolom ini menjadi harga
-- bawaan yang dipakai saat tenant baru di-onboard bila super-admin tak mengisi
-- harga khusus. Tetap flat (tanpa tingkatan paket) — tenant boleh di-override
-- ke harga khusus lewat langganannya masing-masing.
-- ============================================================

ALTER TABLE platform_setting
    ADD COLUMN default_monthly_fee numeric(14, 2) NOT NULL DEFAULT 0;
