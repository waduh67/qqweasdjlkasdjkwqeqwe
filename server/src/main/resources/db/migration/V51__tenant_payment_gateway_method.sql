-- ============================================================
-- Metode pembayaran per-tenant untuk Paywuz.
--
-- Paywuz mewajibkan KODE METODE saat membuat transaksi (mis. meta-method QRIS/VA), berbeda
-- dari halaman hosted Xendit/Pivot yang membiarkan pelanggan memilih. Sebelumnya kodenya satu
-- setelan global (ftth.billing.paywuz.payment-method) untuk semua tenant — kolom ini membuatnya
-- per-tenant: tiap tenant memilih metode dari daftar metode proyek Paywuz-nya sendiri.
--
-- Bukan rahasia (hanya kode metode), jadi TIDAK dienkripsi. NULL = jatuh ke default global.
-- ============================================================

ALTER TABLE tenant_payment_gateway
    ADD COLUMN payment_method varchar(64);
