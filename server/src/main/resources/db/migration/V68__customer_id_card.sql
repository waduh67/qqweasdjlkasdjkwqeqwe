-- ============================================================
-- Nomor identitas pelanggan (NIK/KTP/paspor)
--
-- Kolom opsional untuk KYC & pelaporan regulasi ISP, dan menampung kolom `id_card_number`
-- saat impor pelanggan via CSV. Sengaja longgar (varchar, bukan angka 16-digit) agar paspor/
-- KITAS pun muat; panjang dibatasi domain (maks 32).
--
-- Pure ADD COLUMN nullable → tak ada backfill, tak tersentuh RLS, tak perlu toggling.
-- ============================================================

ALTER TABLE customer ADD COLUMN id_card_number varchar(32);
