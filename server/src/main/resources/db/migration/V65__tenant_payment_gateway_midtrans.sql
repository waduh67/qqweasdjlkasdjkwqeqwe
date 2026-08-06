-- ============================================================
-- Midtrans jadi penyedia payment gateway yang bisa DIPILIH tenant (BYO/Snap).
--
-- Adapter MidtransPaymentGateway sudah ada di module billing (semula hanya dipakai
-- platformbilling untuk menagih langganan SaaS). Yang kurang: tenant belum bisa memilih
-- 'MIDTRANS' untuk menagih pelanggannya sendiri, karena CHECK constraint provider hanya
-- mengizinkan XENDIT/PAYWUZ/PIVOT/MANUAL.
--
-- Cukup memperluas CHECK — tak ada perubahan data: semua baris lama tetap sah, jadi tak
-- perlu toggling RLS (DDL constraint, bukan DML).
-- ============================================================

ALTER TABLE tenant_payment_gateway DROP CONSTRAINT ck_tpg_provider;
ALTER TABLE tenant_payment_gateway
    ADD CONSTRAINT ck_tpg_provider CHECK (provider IN ('XENDIT', 'PAYWUZ', 'PIVOT', 'MANUAL', 'MIDTRANS'));
