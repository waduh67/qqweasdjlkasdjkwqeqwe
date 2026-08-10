-- ============================================================
-- Email jadi kanal notifikasi kelas satu (sejajar WhatsApp)
--
-- Sebelum ini kanal notifikasi ke pelanggan hanya WhatsApp lewat gateway milik tenant
-- sendiri; ISP yang belum punya gateway WA tak punya cara memberi tahu pelanggannya
-- soal tagihan/jadwal teknisi. SMTP platform sudah ada (dipakai pemulihan password
-- portal), tinggal dijadikan kanal siaran.
--
-- Sekaligus membuang dua kanal yang tak pernah punya pengirim: SMS dan TELEGRAM. Layar
-- broadcast insiden membiarkan operator memilih keduanya, lalu pesannya tetap dikirim
-- lewat WhatsApp dan riwayatnya dicatat sebagai SMS — operator membaca sesuatu yang tak
-- pernah terjadi. Baris lama diluruskan ke kanal yang sebenarnya dipakai (WHATSAPP).
-- ============================================================

-- 1. Saklar kanal email per tenant, bebas dari gateway_enabled (WhatsApp): ISP boleh
--    memakai email saja, WA saja, atau keduanya. Mati secara bawaan — menyalakan kanal
--    kirim ke pelanggan harus selalu keputusan sadar operator, bukan efek samping migrasi.
ALTER TABLE notification_settings
    ADD COLUMN email_enabled boolean NOT NULL DEFAULT false;

-- 2. Kolom tujuan penerima kini menampung nomor WhatsApp ATAU alamat email, jadi namanya
--    tak lagi 'phone' dan panjangnya mengikuti batas alamat email (RFC 5321: 254).
ALTER TABLE notification_broadcast_recipient
    RENAME COLUMN phone TO destination;
ALTER TABLE notification_broadcast_recipient
    ALTER COLUMN destination TYPE varchar(254);

-- 3. Riwayat lama berkanal SMS/TELEGRAM sebenarnya terkirim lewat WhatsApp — dijujurkan
--    dulu, baru batasannya dipersempit (urutan ini wajib: constraint baru akan ditolak
--    bila masih ada baris berkanal lama).
UPDATE notification_broadcast
SET channel = 'WHATSAPP'
WHERE channel IN ('SMS', 'TELEGRAM');

ALTER TABLE notification_broadcast
    DROP CONSTRAINT ck_broadcast_channel;
ALTER TABLE notification_broadcast
    ADD CONSTRAINT ck_broadcast_channel CHECK (channel IN ('WHATSAPP', 'EMAIL'));
