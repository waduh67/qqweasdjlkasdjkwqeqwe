-- ============================================================
-- helpdesk — kategori tiket baru: GANTI_PAKET
--
-- Ajuan naik/turun paket dari portal pelanggan tak butuh domain baru: yang dibutuhkan
-- pelanggan adalah nomor ajuan yang bisa diikuti, dan yang dibutuhkan operator adalah
-- antrean yang sama dengan pekerjaan lain — penanggung jawab, tenggat SLA, utas balasan,
-- dan jalur eskalasi ke work order bila perlu kunjungan. Semua itu SUDAH ada di tiket.
--
-- Membuat entitas "permintaan ganti paket" tersendiri berarti menyalin ulang seluruh
-- perkakas itu, lalu punya dua kotak masuk yang harus dijaga operator. Jadi ajuan paket
-- masuk sebagai tiket dengan kategorinya sendiri — cukup untuk disaring & dilaporkan
-- terpisah, tanpa memecah kotak masuk.
-- ============================================================

ALTER TABLE helpdesk_ticket DROP CONSTRAINT ck_ticket_category;
ALTER TABLE helpdesk_ticket
    ADD CONSTRAINT ck_ticket_category CHECK (
        category IN ('KONEKSI_PUTUS', 'KONEKSI_LAMBAT', 'PERANGKAT', 'TAGIHAN', 'LAINNYA', 'GANTI_PAKET')
    );
