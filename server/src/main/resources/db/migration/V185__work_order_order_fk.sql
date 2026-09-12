-- ============================================================
-- workorder — `work_order.order_id` jadi FK sungguhan ke `order_record`
--
-- V163 menambahkan kolomnya sebagai uuid polos tanpa FK, dan satu-satunya cara
-- mengisinya adalah operator MENGETIK UUID di form work order. Sejak P5.4 kolom
-- ini diisi otomatis saat pesanan diterima dan menjadi tautan yang dipakai saga
-- fulfillment untuk mendorong pesanan ke FULFILLED. Tautan yang salah ketik
-- berarti approval sebuah WO menuntaskan pesanan ORANG LAIN — dan itu terlihat
-- pelanggan sebagai "pemasangan selesai" padahal teknisi belum datang.
--
-- FK-nya KOMPOSIT `(tenant_id, order_id)`, bukan `order_id` saja — pola yang sama
-- dengan `fk_order_record_lead` (V177). RLS TIDAK menjaga integritas referensial:
-- tanpa `tenant_id` ikut di FK, sebuah WO bisa menunjuk pesanan milik tenant lain
-- dan constraint-nya tetap senang.
--
-- Pemeriksaan FK di Postgres SENGAJA mengabaikan RLS ("referential integrity
-- checks always bypass row security"), jadi FORCE RLS di `order_record` tak
-- membuat constraint ini mustahil dipenuhi.
--
-- Kolomnya tetap NULLABLE: mayoritas WO (perbaikan, migrasi, preventif, kerja
-- infrastruktur) memang tidak lahir dari pesanan.
-- ============================================================

-- Baris warisan boleh menunjuk pesanan yang tak ada (kolomnya tak pernah divalidasi
-- siapa pun). Tautan rusak itu harus dilepas dulu, kalau tidak ALTER di bawah gagal
-- justru di database yang sudah berisi data — dan migrasi berhenti di produksi.
--
-- KEDUA tabel dimatikan RLS-nya selama backfill. Flyway jalan tanpa GUC
-- `app.tenant_id`: kalau `order_record` tetap ter-FORCE RLS, subquery EXISTS di
-- bawah membaca NOL baris untuk SEMUA WO, dan UPDATE ini akan menghapus SELURUH
-- tautan pesanan yang sah — kerusakan senyap yang jauh lebih buruk daripada
-- migrasi yang gagal. Pola sama V29/V39/V44/V52/V75/V83/V177.
ALTER TABLE work_order   DISABLE ROW LEVEL SECURITY;
ALTER TABLE order_record DISABLE ROW LEVEL SECURITY;

UPDATE work_order w
SET order_id = NULL
WHERE w.order_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM order_record o
      WHERE o.tenant_id = w.tenant_id AND o.id = w.order_id
  );

ALTER TABLE order_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_order   ENABLE ROW LEVEL SECURITY;

ALTER TABLE work_order
    ADD CONSTRAINT fk_work_order_order
    FOREIGN KEY (tenant_id, order_id) REFERENCES order_record (tenant_id, id);
