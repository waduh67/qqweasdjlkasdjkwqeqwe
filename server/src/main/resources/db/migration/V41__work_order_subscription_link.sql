-- ============================================================
-- Modul workorder — tautan langganan sebagai tulang-punggung akuntabilitas PSB/DISMANTLE.
--
-- Sampai kini work order berdiri lepas dari langganan: penyelesaian WO tak menggerakkan
-- apa pun, aktivasi langganan sepenuhnya klik manual. Slice ini menautkan WO ke satu
-- langganan (opsional) supaya penyelesaiannya menggerakkan lifecycle langganan:
--   - WO PSB selesai  → langganan diaktifkan (layanan resmi hidup, mulai ditagih prorata).
--   - WO DISMANTLE selesai → langganan diterminasi.
--
-- `subscription_id` NULL untuk WO tanpa langganan (perbaikan jaringan, preventif) & baris
-- lama — tak perlu backfill. Id lintas-module disimpan polos tanpa FK (pola sama dgn
-- customer_id/incident_id): dinamainya lewat kontrak module customer.
--
-- Hanya ADD COLUMN (nullable) → tak ada UPDATE lintas-tenant, RLS tak perlu dimatikan.
-- ============================================================

ALTER TABLE work_order ADD COLUMN subscription_id uuid NULL;
