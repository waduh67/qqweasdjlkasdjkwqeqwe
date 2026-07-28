-- ============================================================
-- Prorata tagihan pertama saat aktivasi tengah bulan
--
-- Langganan yang aktif di tengah periode hanya membayar hari terpakai (hari
-- aktivasi s/d akhir bulan, inklusif) — bukan sebulan penuh. Nilai prorata
-- dibekukan di tagihan agar invoice historis stabil walau tarif paket berubah:
--   - prorated       : penanda tagihan ini diprorata (amount < tarif penuh)
--   - prorated_days  : jumlah hari yang ditagihkan; NULL saat tagihan penuh
--
-- Keduanya diisi sekali saat penerbitan dan tak pernah berubah (updatable=false
-- di sisi entity). Kolom lama default penuh: prorated=false untuk tagihan warisan.
-- ============================================================

ALTER TABLE invoice
    ADD COLUMN prorated boolean NOT NULL DEFAULT false,
    ADD COLUMN prorated_days integer;
