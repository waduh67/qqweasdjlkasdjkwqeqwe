-- ============================================================
-- Konfigurasi pembayaran MANUAL per-tenant (transfer / QRIS).
--
-- Saat gateway otomatis nonaktif, tenant "cuma bisa manual". Sebelumnya tak ada instruksi
-- bayar apa pun yang bisa ditunjukkan ke pelanggan — kolom-kolom ini mengisinya: dua metode
-- independen, tiap toggle aktif membuka fieldnya.
--
--   Transfer : saklar + nama bank, nomor rekening, atas nama.
--   QRIS     : saklar + gambar QRIS (byte di object storage MinIO/S3, DB simpan key + tipe).
--
-- Semua NON-RAHASIA (bukan kredensial), jadi TIDAK dienkripsi — mengikuti pola kolom
-- payment_method (V51). Byte gambar QRIS TIDAK masuk DB; hanya storage key + content type.
-- ============================================================

ALTER TABLE tenant_payment_gateway
    ADD COLUMN manual_transfer_enabled boolean NOT NULL DEFAULT false,
    ADD COLUMN transfer_bank_name      varchar(120),
    ADD COLUMN transfer_account_number varchar(60),
    ADD COLUMN transfer_account_holder varchar(160),
    ADD COLUMN manual_qris_enabled     boolean NOT NULL DEFAULT false,
    ADD COLUMN qris_storage_key        varchar(255),
    ADD COLUMN qris_content_type       varchar(100);
