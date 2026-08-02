-- ============================================================
-- Penjaga idempoten pengingat "mendekati jatuh tempo" pada tagihan.
--
-- Sweep pengingat berjalan berkala (tiap 12 jam bersama scheduler penagihan). Tanpa
-- penanda ini, satu tagihan yang jatuh tempo dalam beberapa hari ke depan akan dikirimi
-- pengingat berulang setiap sweep. Kolom ini dinyalakan sekali saat pengingat dikirim
-- dan tak pernah dimatikan (tagihan tak terbit ulang). Tagihan lama dianggap belum
-- pernah diingatkan → default false.
-- ============================================================

ALTER TABLE invoice
    ADD COLUMN due_soon_reminded boolean NOT NULL DEFAULT false;
