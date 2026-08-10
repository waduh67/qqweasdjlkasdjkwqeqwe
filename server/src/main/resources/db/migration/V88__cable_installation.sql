-- ============================================================
-- Cara pasang & kepemilikan kabel
--
-- Dua pertanyaan yang selalu datang saat ada gangguan atau tagihan, tapi sampai
-- kini cuma hidup di kepala orang lapangan:
--
--   "Kabel ini di atas atau di bawah?"  → menentukan siapa yang dikirim, alat
--       apa yang dibawa, dan berapa lama perbaikannya (galian butuh izin,
--       tiang cukup tangga). Juga menentukan siapa yang ditelepon saat pohon
--       ditebang atau jalan digali.
--   "Ini kabel kita, atau numpang/sewa?" → menentukan siapa yang boleh
--       menyentuhnya dan siapa yang menagih sewa tiap bulan.
--
-- `installation_method` sengaja NULLABLE. Kabel yang sudah tergambar sebelum
-- kolom ini ada memang TIDAK diketahui cara pasangnya, dan menebak "udara"
-- untuk semuanya (mayoritas memang udara) akan melahirkan data yang terlihat
-- lengkap padahal karangan — persis jenis kebohongan yang bikin teknisi
-- berangkat bawa tangga ke gangguan yang ternyata di dalam duct. NULL berarti
-- "belum disurvei", dan itu pertanyaan yang bisa ditanyakan balik ke operator.
--
-- `ownership` sebaliknya NOT NULL DEFAULT 'OWNED': kabel yang digambar sendiri
-- di peta sendiri adalah milik sendiri kecuali dinyatakan lain. Sewa / dark
-- fiber pihak lain justru kekecualian yang HARUS ditandai sadar, sebab dari
-- situlah tagihan bulanan dan batas kewenangan berasal.
--
-- Tiang sebagai ASET tersendiri (dengan sewa per titik) sengaja belum dibuat —
-- lihat docs/topologi-kabel.html §"yang sengaja tidak dikerjakan sekarang".
-- ============================================================

ALTER TABLE cable
    ADD COLUMN installation_method varchar(20),
    ADD COLUMN ownership           varchar(20) NOT NULL DEFAULT 'OWNED';

ALTER TABLE cable
    ADD CONSTRAINT ck_cable_installation_method
        CHECK (installation_method IS NULL OR installation_method IN ('AERIAL', 'BURIED', 'DUCT')),
    ADD CONSTRAINT ck_cable_ownership
        CHECK (ownership IN ('OWNED', 'LEASED'));

-- Default cuma untuk mengisi baris lama; selanjutnya nilainya selalu datang
-- dari domain supaya tak ada dua sumber kebenaran.
ALTER TABLE cable
    ALTER COLUMN ownership DROP DEFAULT;
