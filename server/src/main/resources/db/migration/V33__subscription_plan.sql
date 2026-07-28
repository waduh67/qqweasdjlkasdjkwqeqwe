-- ============================================================
-- Langganan MERUJUK paket katalog + snapshot siklus billing
--
-- Sebelumnya packageName/bandwidthMbps/monthlyFee diketik bebas per-langganan.
-- Kini langganan menyalin snapshot dari `plan` (catalog) saat create/update:
--   - plan_id                : asal paket (uuid polos, TANPA FK lintas-module) —
--                              bng membaca sisi jaringannya live untuk RADIUS
--   - prorate_on_activation  ┐
--   - billing_day_of_month   ├ override siklus per-paket; NULL = ikut BillingProperties
--   - grace_days             │  global. Dibekukan agar invoice historis stabil.
--   - auto_isolir            ┘
-- Kolom nullable: langganan warisan (dibuat sebelum sistem paket terpadu) tetap valid
-- dengan plan_id NULL dan tetap ditagih memakai monthly_fee yang sudah tersimpan.
-- ============================================================

ALTER TABLE subscription
    ADD COLUMN plan_id               uuid,
    ADD COLUMN prorate_on_activation boolean,
    ADD COLUMN billing_day_of_month  integer,
    ADD COLUMN grace_days            integer,
    ADD COLUMN auto_isolir           boolean;

ALTER TABLE subscription
    ADD CONSTRAINT ck_subscription_billing_day
        CHECK (billing_day_of_month IS NULL OR billing_day_of_month BETWEEN 1 AND 31),
    ADD CONSTRAINT ck_subscription_grace_days
        CHECK (grace_days IS NULL OR grace_days BETWEEN 0 AND 90);
