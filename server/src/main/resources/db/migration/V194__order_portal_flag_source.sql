-- ============================================================
-- order — SIAPA yang memasang penanda portal: operator atau sistem
--
-- V184 membangun jalannya dan SENGAJA mengosongkan pemicunya. Sekarang pemicunya
-- ada tiga (kunjungan gagal karena pelanggan, operator menandai pelanggan tak
-- bisa dihubungi, dan saga fulfillment yang mendarat di REQUIRES_RECONCILIATION),
-- dan begitu sistem ikut memasang penanda, muncul pertanyaan yang sebelumnya tak
-- pernah ada: BOLEH TIDAK sistem melepas penanda yang dipasang manusia?
--
-- TIDAK. Operator yang menulis "menunggu konfirmasi titik pemasangan" tahu
-- sesuatu yang tidak diketahui sistem — ia baru saja menelepon pelanggannya.
-- Kalau saga yang pulih diam-diam melepas penanda itu, pesanan kembali tampak
-- normal padahal masih benar-benar menunggu jawaban pelanggan, dan operator tak
-- akan pernah tahu penandanya hilang. Karena itu:
--
--   - SYSTEM boleh menimpa/melepas penanda SYSTEM.
--   - SYSTEM tidak pernah menyentuh penanda OPERATOR.
--   - OPERATOR boleh menimpa/melepas apa pun (ia yang bertanggung jawab).
--
-- Tanpa kolom ini aturan di atas mustahil ditegakkan: `portal_flag` sendirian
-- tidak memuat asal-usulnya, dan menebaknya dari `order_audit` berarti membaca
-- riwayat di jalur panas setiap kali saga gagal.
-- ============================================================

ALTER TABLE order_record ADD COLUMN IF NOT EXISTS portal_flag_source varchar(16);

-- Baris lama yang sudah bertanda PASTI dipasang operator: sampai migrasi ini
-- tidak ada satu pun otomasi yang bisa memasangnya (lihat catatan V184).
--
-- Flyway jalan sebagai role NOBYPASSRLS dan `order_record` ter-FORCE RLS,
-- sedangkan GUC app.tenant_id tak di-set saat migrasi. Tanpa mematikan RLS
-- sementara, UPDATE di bawah menyentuh NOL baris — lalu CHECK di bawahnya (DDL
-- TIDAK tunduk RLS, ia melihat semua baris) gagal justru di database yang sudah
-- berisi pesanan bertanda. Pola sama V29/V39/V44/V52/V75/V83/V177.
ALTER TABLE order_record DISABLE ROW LEVEL SECURITY;
UPDATE order_record SET portal_flag_source = 'OPERATOR'
WHERE portal_flag IS NOT NULL AND portal_flag_source IS NULL;
ALTER TABLE order_record ENABLE ROW LEVEL SECURITY;

ALTER TABLE order_record
    ADD CONSTRAINT ck_order_record_portal_flag_source
    CHECK (portal_flag_source IS NULL OR portal_flag_source IN ('OPERATOR', 'SYSTEM'));

-- Asal tanpa penanda = sisa yang tak pernah terbaca; penanda tanpa asal =
-- aturan "sistem tak boleh melepas penanda manusia" kehilangan dasarnya dan
-- jatuh ke tebakan.
ALTER TABLE order_record
    ADD CONSTRAINT ck_order_record_portal_flag_source_pair
    CHECK ((portal_flag IS NULL) = (portal_flag_source IS NULL));
