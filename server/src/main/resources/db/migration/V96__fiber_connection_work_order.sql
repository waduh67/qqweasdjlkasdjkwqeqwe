-- ============================================================
-- Sambungan serat ingat SIAPA yang melasnya dan UNTUK TIKET APA
--
-- Sampai kini sebuah baris sambungan cuma bisa menjawab "apa tersambung ke
-- apa". Yang paling sering ditanyakan penyelia justru dua hal lain: "sambungan
-- di ODP-12 ini pekerjaan siapa?" dan "work order kemarin itu isinya apa saja?"
-- Keduanya kini terjawab dari barisnya sendiri, bukan dari ingatan orang atau
-- dari mengurut jejak audit satu per satu.
--
-- Keduanya sengaja TANPA foreign key, seperti semua kolom pelaku di basis data
-- ini (`created_by`, `actor_id`). Work order milik module lain, dan kontrak
-- antar-module di aplikasi ini memang bertukar id polos — sama seperti
-- closure_id yang polimorfik di tabel yang sama. Konsekuensinya diterima sadar:
-- WO atau akun yang dihapus meninggalkan id yang tak menunjuk apa-apa, dan
-- pembacanya cukup menampilkan sambungannya tanpa kode tiket / tanpa nama. Itu
-- jauh lebih baik daripada ON DELETE CASCADE yang akan MENGHAPUS SAMBUNGAN
-- SERATNYA — seratnya di dalam kotak tak ikut putus hanya karena tiketnya
-- dibersihkan dari basis data.
--
-- spliced_by/spliced_at diisi saat baris dibuat dan tak pernah berubah sesudah
-- itu (kolomnya updatable=false di sisi aplikasi): ia mencatat siapa membuka
-- kotak itu pada hari itu. Orang yang belakangan mengoreksi angka redamannya
-- bukan orang yang menyambung, dan menimpanya akan menghapus satu-satunya jejak
-- siapa yang benar-benar berdiri di depan kotaknya.
--
-- Baris lama: kedua kolom pelaku dibiarkan NULL — jujur "tak tercatat" — dan
-- spliced_at diisi now() sekali agar kolomnya bisa NOT NULL. Waktu itu memang
-- bukan waktu pengerjaan sesungguhnya, dan itu tak bisa direkayasa dari data
-- yang tak pernah ada; created_at barisnya tetap tersimpan apa adanya bagi yang
-- ingin menakar umurnya.
-- ============================================================

ALTER TABLE fiber_connection
    ADD COLUMN work_order_id uuid,
    ADD COLUMN spliced_by    uuid,
    ADD COLUMN spliced_at    timestamptz NOT NULL DEFAULT now();

-- Jalan baca "isi work order ini apa saja" — halaman WO memanggilnya tiap kali
-- dibuka, dan tanpa indeks ia memindai seluruh sambungan milik tenant.
CREATE INDEX ix_fiber_connection_work_order ON fiber_connection (work_order_id)
    WHERE work_order_id IS NOT NULL;
