-- ============================================================
-- Field wajib create sub-account Pivot (POST /v1/sub-merchants) yang sebelumnya tak dikirim —
-- akar 400/409 saat provisioning. Sourcing HYBRID:
--   * Default level-platform (referensi bisnis/industri yang SAMA untuk semua tenant) → kolom
--     default_* di pivot_master_config, diisi sekali oleh super-admin di /platform/billing.
--   * Profil spesifik-tenant (identitas + PIC + alamat) → kolom di tenant_pivot_account,
--     diisi tenant di /payment-gateway. Rekening bank pakai payout_* yang sudah ada (V71).
--
-- SEMUA kolom NON-rahasia → plaintext (tanpa enkripsi). Nilai referensi (mcc/parent/child
-- industry/district_id/business_structure) WAJIB valid menurut daftar referensi Pivot.
-- ============================================================

-- Default level-platform (non-rahasia, singleton pivot_master_config).
ALTER TABLE pivot_master_config
    ADD COLUMN default_business_type      varchar(40),
    ADD COLUMN default_business_structure varchar(40),
    ADD COLUMN default_parent_industry    varchar(120),
    ADD COLUMN default_child_industry     varchar(120),
    ADD COLUMN default_mcc                varchar(20),
    ADD COLUMN default_digital_status     varchar(40),
    ADD COLUMN default_business_country   varchar(8),
    ADD COLUMN default_country_of_entity  varchar(8),
    ADD COLUMN default_logo_url           varchar(500),
    ADD COLUMN default_website            varchar(300),
    ADD COLUMN default_district_id        integer,
    ADD COLUMN default_post_code          varchar(20);

-- Profil spesifik-tenant (non-rahasia, tenant-scoped tenant_pivot_account — RLS sudah aktif V71).
ALTER TABLE tenant_pivot_account
    ADD COLUMN legal_name      varchar(200),
    ADD COLUMN merchant_email  varchar(160),
    ADD COLUMN merchant_phone  varchar(40),
    ADD COLUMN pic_name        varchar(160),
    ADD COLUMN pic_email       varchar(160),
    ADD COLUMN pic_phone       varchar(40),
    ADD COLUMN address         varchar(500);
