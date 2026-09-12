-- ============================================================
-- order — penanda portal `WAITING_CUSTOMER` / `REQUIRES_ATTENTION`
--
-- `PortalOrderStatus` punya delapan nilai, tapi dua di antaranya TIDAK PERNAH
-- DIPRODUKSI SIAPA PUN: `toPortalView()` memetakan status agregat ke enam nilai
-- sisanya dan berhenti di situ. Akibatnya pelanggan yang pesanannya macet karena
-- menunggu jawaban dia sendiri (mis. konfirmasi titik pemasangan) tetap melihat
-- "sedang ditinjau" — lalu menelepon, dan operator menjelaskan hal yang mestinya
-- bisa dibaca sendiri di portal.
--
-- CATATAN PENTING UNTUK PEMILIK PRODUK: migrasi ini hanya menyiapkan JALURNYA
-- (kolom, audit, transisi, tampilan). ATURAN BISNIS kapan penanda ini dipasang
-- SENGAJA belum diputuskan dan belum ada otomasi yang memasangnya — untuk
-- sekarang hanya operator yang bisa memasang/melepas lewat
-- `POST /api/orders/{id}/attention`. Mengarang pemicunya sendiri berisiko lebih
-- besar daripada membiarkannya manual: penanda yang salah pasang membuat
-- pelanggan mengira BOLA ADA DI TANGANNYA padahal tidak, dan pesanan itu
-- berhenti bergerak sampai ada yang menelepon.
--
-- Penanda dibuat ORTOGONAL terhadap `status`, bukan status baru. Kalau ia jadi
-- status agregat, seluruh mesin transisi (termasuk jalur fulfillment) harus tahu
-- cara kembali dari sana, dan setiap penambahan penanda baru melipatgandakan
-- jumlah pasangan transisi yang harus dijaga.
-- ============================================================

ALTER TABLE order_record ADD COLUMN IF NOT EXISTS portal_flag        varchar(24);
ALTER TABLE order_record ADD COLUMN IF NOT EXISTS portal_flag_reason varchar(300);

-- Nilai di luar dua ini berarti portal menerima status yang tak bisa ia tampilkan
-- dan halaman lacak pelanggan akan kosong tanpa error apa pun.
ALTER TABLE order_record
    ADD CONSTRAINT ck_order_record_portal_flag
    CHECK (portal_flag IS NULL OR portal_flag IN ('WAITING_CUSTOMER', 'REQUIRES_ATTENTION'));

-- Alasan hanya masuk akal bila ada penandanya. Alasan yatim = teks yang tak
-- pernah tampil di mana pun tapi tetap terbawa setiap ekspor data.
ALTER TABLE order_record
    ADD CONSTRAINT ck_order_record_portal_flag_reason
    CHECK (portal_flag IS NOT NULL OR portal_flag_reason IS NULL);

-- Antrean "pesanan yang macet" — yang paling perlu disentuh operator tiap pagi.
CREATE INDEX IF NOT EXISTS ix_order_record_portal_flag
    ON order_record (tenant_id, portal_flag)
    WHERE portal_flag IS NOT NULL;
