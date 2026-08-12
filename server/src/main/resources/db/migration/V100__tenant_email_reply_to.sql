-- ============================================================
-- Alamat pengirim email dikunci ke platform; kolom tenant turun pangkat jadi Reply-To.
--
-- V97 memberi tenant kolom `from_address` yang dipakai APA ADANYA sebagai header
-- `From`, dengan peringatan SPF/DKIM di UI sebagai satu-satunya penjaga. Peringatan
-- itu ditulis untuk relay SMTP generik. Relay yang sekarang dipakai (Brevo) menolak
-- pengirim yang belum terverifikasi di sisi penyedia — jadi begitu satu tenant
-- mengisi kolom ini, emailnya bukan "berisiko masuk spam" melainkan GAGAL BERANGKAT.
--
-- Kolomnya di-RENAME, bukan dibuang: alamat yang sudah diisi tenant memang alamat
-- yang mereka ingin dihubungi pelanggan, dan resolver sejak V97 sudah memasangnya
-- sebagai `Reply-To` juga. Menghapusnya berarti diam-diam mengalihkan balasan
-- pelanggan ke kotak masuk platform — kerugian yang tak diminta siapa pun.
--
-- Yang tetap milik tenant: nama pengirim, alamat balasan, logo, warna, footer,
-- tanda tangan, subjek. Yang tidak: alamat pengirim, sama seperti sambungan SMTP.
-- ============================================================

ALTER TABLE tenant_email_setting RENAME COLUMN from_address TO reply_to_address;

COMMENT ON COLUMN tenant_email_setting.reply_to_address IS
    'Alamat Reply-To tenant (null = tanpa Reply-To). Header From SELALU alamat platform: '
        'relay hanya menerima pengirim yang sudah terverifikasi di sisi penyedia.';
