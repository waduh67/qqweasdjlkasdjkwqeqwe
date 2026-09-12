-- Keadaan baru aset berserial: AWAITING_RECEIPT.
--
-- Sampai sekarang `inventory_serialized_asset.status` hanya mengenal keadaan barang yang
-- SUDAH ada di tangan gudang. Akibatnya restock berserial tidak punya tempat berdiri sama
-- sekali: nomor seri yang dijanjikan pemasok baru boleh didaftarkan setelah barangnya
-- datang, padahal justru pendaftaran itulah yang memeriksa duplikat SN/MAC sebelum satu
-- kiriman diterima. Yang terjadi di lapangan: petugas mendaftarkan unitnya sebagai
-- AVAILABLE selagi persetujuan restock masih menggantung — dan sejak detik itu gudang
-- mengaku punya barang yang belum pernah masuk rak.
--
-- AWAITING_RECEIPT SENGAJA BUKAN status stok: ia satu-satunya keadaan aset yang TIDAK PERNAH
-- punya baris di `inventory_balance_projection`. Itu yang membuat "saldo" dan "daftar aset"
-- tetap sepakat sepanjang masa tunggu approval — keduanya sama-sama bilang nol.
--
-- CHECK-nya dibuat ulang, bukan ditambal, karena Postgres tidak punya ALTER CONSTRAINT untuk
-- CHECK. DDL tidak tunduk RLS jadi tidak perlu mematikan RLS seperti V174.
ALTER TABLE inventory_serialized_asset DROP CONSTRAINT inventory_asset_status_ck;

ALTER TABLE inventory_serialized_asset
    ADD CONSTRAINT inventory_asset_status_ck CHECK (
        status IN (
            'AWAITING_RECEIPT',
            'AVAILABLE', 'RESERVED', 'ISSUED', 'IN_TRANSIT',
            'CONSUMED', 'RETURNED', 'QUARANTINE', 'LOST', 'DISPOSED'
        )
    );
