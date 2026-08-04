-- ------------------------------------------------------------
-- Kabel level-port: tiap ujung kabel kini merekam PORT fisiknya, bukan sekadar
-- "menyentuh simpul". Dengan begitu jelas sebuah kabel mencolok dari port mana ke
-- port mana — PON port OLT untuk feeder, kaki splitter ODC / slot ODP untuk
-- distribusi & drop — dan dua kabel tak bisa diam-diam berebut port keluaran sama.
--
-- Semua kolom NULLABLE dan tanpa backfill: kabel lama (direkam sebelum fitur ini)
-- tetap sah dengan port kosong. Pemeriksaan okupansi di CableService mengabaikan
-- kabel ber-port null, jadi data lama tak menghalangi apa pun. Karena hanya
-- ADD COLUMN nullable (tak ada UPDATE lintas-tenant), RLS tak perlu disentuh.
-- ------------------------------------------------------------

ALTER TABLE cable ADD COLUMN from_pon_port_id uuid;      -- FEEDER: PON port OLT sumber
ALTER TABLE cable ADD COLUMN from_port_number integer;   -- sumber: kaki splitter ODC / slot ODP
ALTER TABLE cable ADD COLUMN to_port_number   integer;   -- input tujuan (opsional, umumnya tunggal)
