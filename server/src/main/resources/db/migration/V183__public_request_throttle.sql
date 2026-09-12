-- ============================================================
-- Rem laju untuk permintaan PUBLIK (tanpa JWT) yang dibagi antar-instance
--
-- KENAPA tabel, padahal `AttemptThrottle` yang sudah ada menghitung di memori:
-- rem di memori itu sengaja dipilih untuk endpoint yang menerima RAHASIA (login,
-- kode pemulihan) — di sana penyerang tak boleh mengendalikan beban tulis kita,
-- dan batas yang terkali jumlah instance masih menyisakan pertahanan lain
-- (password tetap harus benar). Pintu pemesanan publik berbeda sifatnya: TIDAK
-- ADA rahasia yang harus ditebak. Satu permintaan yang lolos langsung menjadi
-- satu baris di antrean pesanan operator. Kalau hitungannya per-instance, dua
-- replika di belakang Caddy membuat batas efektifnya dua kali lipat, dan satu
-- skrip cukup menyemprot sampai antrean operator penuh sampah — kerusakan yang
-- LANGSUNG TERLIHAT pengguna dan tak bisa dibatalkan massal.
--
-- Biayanya jujur: satu UPSERT per permintaan publik. Itu tetap jauh lebih murah
-- daripada pekerjaan yang dijaganya (buat lead + pesanan + nomor + audit +
-- outbox dalam satu transaksi), dan penulisannya dibatasi justru oleh rem ini
-- sendiri — baris yang sama dinaikkan berulang, bukan baris baru per permintaan.
--
-- SENGAJA TANPA `tenant_id` dan TANPA RLS. Rem harus bekerja SEBELUM tenant
-- diketahui: permintaan dengan slug yang tidak dikenal (penyerang menebak-nebak
-- slug tenant) tak punya tenant sama sekali, dan kalau tabel ini ter-RLS maka
-- pencatatannya akan DIAM-DIAM menyentuh nol baris — remnya seolah menyala tapi
-- tak pernah menahan apa pun. Tabel ini tak memuat data pelanggan: hanya lingkup,
-- subjek (alamat IP / id tenant), dan cacah.
-- ============================================================

CREATE TABLE IF NOT EXISTS public_request_throttle (
    scope        varchar(40)  NOT NULL,
    -- Alamat IP atau id tenant. Dipotong pemanggil agar kiriman sampah yang panjang
    -- tak bisa dipakai menggelembungkan tabel lewat kunci raksasa.
    subject      varchar(160) NOT NULL,
    -- Awal jendela tetap, sudah dibulatkan ke bawah oleh pemanggil. Ikut jadi kunci
    -- supaya pergantian jendela adalah baris BARU, bukan UPDATE yang harus membaca
    -- lalu menulis (dua langkah itu balapan: dua instance bisa sama-sama mengira
    -- jendelanya baru dan sama-sama mereset cacah ke 1).
    window_start timestamptz  NOT NULL,
    hits         integer      NOT NULL DEFAULT 0 CHECK (hits >= 0),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, subject, window_start)
);

-- Penyapuan berkala menghapus jendela yang sudah lewat; tanpa index ini sapuan itu
-- men-scan seluruh tabel setiap kali dan justru menjadi beban yang mau dihindari.
CREATE INDEX IF NOT EXISTS ix_public_request_throttle_window
    ON public_request_throttle (window_start);
