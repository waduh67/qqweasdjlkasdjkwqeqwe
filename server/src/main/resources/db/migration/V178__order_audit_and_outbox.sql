-- ============================================================
-- order — menghidupkan `order_audit` dan `order_outbox`
--
-- Kedua tabel lahir di V141 dan SAMPAI SEKARANG TIDAK PERNAH DITULIS satu baris
-- pun: `OrderApplicationService` menumpuk event di `mutableListOf` dalam proses
-- dan `publishedEvents()` tak punya pemanggil. Akibatnya pesanan tidak punya
-- riwayat sama sekali — pertanyaan "kapan pesanan ini diterima, dan oleh siapa?"
-- tidak bisa dijawab, dan restart aplikasi menghapus jejak yang sempat ada.
--
-- Migrasi ini menyiapkan kedua tabel untuk dipakai sungguhan:
--   order_audit  → sumber tunggal timeline (`GET /api/orders/{id}/timeline`)
--   order_outbox → pengiriman event lintas-module yang durabel, memakai pola
--                  lease + FOR UPDATE SKIP LOCKED yang SUDAH ada di
--                  `fulfillment_outbox` (V153). Tidak ada pola baru di sini.
--
-- Kedua tabel masih kosong di semua instalasi, jadi kolom NOT NULL dan constraint
-- UNIQUE bisa dipasang langsung tanpa backfill dan tanpa mematikan RLS.
-- ============================================================

-- ------------------------------------------------------------
-- order_audit: dari-state → ke-state, siapa, kapan, kenapa
--
-- V141 hanya menyimpan `payload` JSON mentah. Timeline yang bisa difilter dan
-- dibaca manusia butuh kolomnya terurai, kalau tidak setiap pembacaan harus
-- mem-parse JSON di aplikasi dan tak ada index yang bisa menolong.
-- ------------------------------------------------------------
ALTER TABLE order_audit ADD COLUMN IF NOT EXISTS from_status varchar(24);
ALTER TABLE order_audit ADD COLUMN IF NOT EXISTS to_status   varchar(24);
ALTER TABLE order_audit ADD COLUMN IF NOT EXISTS reason      varchar(500);

-- Satu transisi = satu baris. Kalau jalur idempotency mengulang efek yang sama
-- (replay operation key, atau worker outbox yang kehilangan lease lalu diambil
-- worker lain), timeline TIDAK BOLEH menampilkan kejadian yang sama dua kali.
ALTER TABLE order_audit
    ADD CONSTRAINT uq_order_audit_revision UNIQUE (tenant_id, order_id, revision, event_type);

-- Timeline satu pesanan dibaca berurutan naik; index existing (tenant, waktu DESC)
-- tak menolong karena ia tak memuat order_id.
CREATE INDEX IF NOT EXISTS ix_order_audit_order_seq ON order_audit (tenant_id, order_id, revision);

-- ------------------------------------------------------------
-- order_outbox: lease supaya dua instance tak mengirim event yang sama
--
-- Kolomnya sengaja dinamai persis seperti `fulfillment_outbox` (V153) agar query
-- klaimnya bisa disalin apa adanya dan hanya ada SATU pola outbox di repo ini.
-- ------------------------------------------------------------
ALTER TABLE order_outbox ADD COLUMN IF NOT EXISTS claimed_by  varchar(120);
ALTER TABLE order_outbox ADD COLUMN IF NOT EXISTS lease_until timestamptz;
ALTER TABLE order_outbox ADD COLUMN IF NOT EXISTS attempts    integer NOT NULL DEFAULT 0;
-- Revisi agregat saat event lahir; jadi kunci dedup dan sekaligus urutan kirim.
ALTER TABLE order_outbox ADD COLUMN IF NOT EXISTS revision    bigint NOT NULL DEFAULT 0;

-- Menulis outbox dua kali untuk revisi yang sama berarti konsumen hilir menerima
-- transisi ganda. ON CONFLICT DO NOTHING di sisi aplikasi bersandar pada ini.
ALTER TABLE order_outbox
    ADD CONSTRAINT uq_order_outbox_revision UNIQUE (tenant_id, aggregate_id, event_type, revision);

-- Antrean pengiriman: hanya yang belum terkirim DAN lease-nya sudah lewat.
-- Parsial supaya index tak ikut menyimpan seluruh riwayat event yang sudah beres.
DROP INDEX IF EXISTS ix_order_outbox_pending;
CREATE INDEX IF NOT EXISTS ix_order_outbox_claimable ON order_outbox (tenant_id, created_at, id)
    WHERE published_at IS NULL;
