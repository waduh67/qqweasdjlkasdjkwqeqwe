-- ============================================================
-- Modul vpn — PKI mandiri: aplikasi menjadi CA-nya sendiri
--
-- Aplikasi kini menerbitkan CA + sertifikat server otomatis saat hub dibuat
-- (BouncyCastle), menghapus langkah easy-rsa manual bagi operator. Kolom baru:
--   ca_key      kunci privat CA  — RAHASIA, terenkripsi (menandatangani cert server)
--   server_cert sertifikat server (publik, EKU serverAuth)
--   server_key  kunci privat server — RAHASIA, terenkripsi
-- ca_cert (publik) sudah ada dari V26. Semua text (PEM/ciphertext panjang), nullable
-- karena baris lama (bila ada) belum ber-PKI dan bisa diterbitkan ulang.
-- ============================================================

ALTER TABLE vpn_server
    ADD COLUMN ca_key      text,
    ADD COLUMN server_cert text,
    ADD COLUMN server_key  text;
