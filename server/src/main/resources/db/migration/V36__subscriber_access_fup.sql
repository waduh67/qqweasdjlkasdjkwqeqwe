-- ============================================================
-- Penanda throttle FUP pada akun jaringan
--
-- Saat pemakaian periode sebuah akun melewati kuota FUP paketnya, penegak FUP
-- memindahkannya ke grup RADIUS throttle (plan:{id}:fup) lalu CoA menurunkan
-- kecepatan sesi hidup. Bendera ini merekam keadaan itu agar penegakan idempoten:
--   - fup_throttled = true  → akun sedang di grup FUP (jangan antre ulang)
--   - fup_throttled = false → akun di grup normal (dipulihkan saat pemakaian turun
--                             atau siklus berganti)
--
-- Akun warisan default tidak ter-throttle. Nilai diubah HANYA oleh penegak FUP &
-- penggantian paket, jadi kolom biasa (bukan updatable=false).
-- ============================================================

ALTER TABLE subscriber_access
    ADD COLUMN fup_throttled boolean NOT NULL DEFAULT false;
