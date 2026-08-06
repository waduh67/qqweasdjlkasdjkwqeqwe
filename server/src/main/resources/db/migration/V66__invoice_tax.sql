-- ============================================================
-- PPN pada tagihan pelanggan
--
-- PPN (11%) diperlakukan sebagai KOMPONEN tagihan pelanggan: ditambahkan ke atas dasar
-- (DPP) sehingga total yang ditagih = dasar + PPN. Kedua kolom disetel saat penerbitan dan
-- tak pernah berubah (updatable=false di entity) — seperti `amount`.
--
--   tax_amount  nilai PPN yang termasuk dalam `amount` (total). Baris lama & tenant yang
--               tak mengaktifkan PPN bernilai 0 → total tetap = dasar (perilaku lama utuh).
--   tax_rate    tarif yang diterapkan (mis. 0.1100); NULL bila tagihan tanpa PPN.
--
-- ADD COLUMN dengan DEFAULT 0 mengisi baris lama otomatis di tingkat DDL (bukan UPDATE),
-- jadi tak tersentuh RLS — tak perlu toggling. Dasar sebelum pajak dihitung di domain
-- (`Invoice.baseAmount = amount − tax_amount`), tak perlu kolom terpisah.
-- ============================================================

ALTER TABLE invoice ADD COLUMN tax_amount numeric(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE invoice ADD COLUMN tax_rate   numeric(6, 4);

-- Tarif PPN adalah pecahan di [0,1) — mis. 0.1100 untuk 11%, bukan persen.
ALTER TABLE invoice ADD CONSTRAINT ck_invoice_tax_rate CHECK (tax_rate IS NULL OR (tax_rate >= 0 AND tax_rate < 1));
ALTER TABLE invoice ADD CONSTRAINT ck_invoice_tax_amount CHECK (tax_amount >= 0);
