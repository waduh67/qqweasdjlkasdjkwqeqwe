-- ============================================================
-- Biaya payout yang ditagihkan platform ke tenant.
--
-- Tiap `POST /v1/payouts` mendebit DUA dompet: nominalnya dari DISBURSEMENT sub-account tenant,
-- BIAYANYA (Rp 4.000 flat di sandbox) dari DISBURSEMENT master. Tanpa kolom ini biaya itu ditelan
-- platform — tiap tenant menyalurkan dana, platform tekor sebesar tarif Pivot.
--
-- payout_fee_minor dipotong dari nominal yang diminta tenant lalu dipindahkan ke master lewat
-- `POST /v1/transfers` (arah sub → master, terverifikasi di sandbox). Angkanya SETELAN, bukan
-- konstanta: tarif Pivot bisa berbeda di produksi, dan platform boleh memasang markup.
--
-- Default 0 = perilaku lama (platform menanggung) → migrasi ini tak mengubah apa pun sampai
-- super-admin mengisinya di /platform/billing.
-- ============================================================

ALTER TABLE pivot_master_config
    ADD COLUMN payout_fee_minor bigint      NOT NULL DEFAULT 0,
    ADD COLUMN payout_fee_type  varchar(20) NOT NULL DEFAULT 'FIXED';

ALTER TABLE pivot_master_config
    ADD CONSTRAINT ck_pivot_master_payout_fee_type CHECK (payout_fee_type IN ('FIXED', 'PERCENTAGE')),
    ADD CONSTRAINT ck_pivot_master_payout_fee_nonneg CHECK (payout_fee_minor >= 0);

-- Biaya yang benar-benar dipotong dari tiap penyaluran, dibekukan per baris. Bukan diturunkan dari
-- setelan saat ditampilkan: tarifnya bisa berubah, dan riwayat harus tetap menunjukkan angka yang
-- berlaku saat itu. 0 = tak ada biaya (withdrawal KYC, atau setelan masih 0).
ALTER TABLE tenant_payout
    ADD COLUMN fee_minor bigint NOT NULL DEFAULT 0;

ALTER TABLE tenant_payout
    ADD CONSTRAINT ck_tenant_payout_fee_nonneg CHECK (fee_minor >= 0);
