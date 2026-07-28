-- ============================================================
-- Modul CATALOG — paket internet sebagai SUMBER TUNGGAL
--
-- Menyatukan definisi paket yang dulu kececer (RateProfile teknis tanpa harga di bng,
-- teks bebas packageName/monthlyFee di customer, penaut rateProfileId di akun) menjadi
-- satu agregat: komersial (harga) + jaringan (kecepatan/burst/QoS/limit-at) + FUP +
-- override siklus billing.
--
-- Modul lain merujuk plan.id lewat uuid polos TANPA foreign key lintas-module:
--   customer  → snapshot sisi komersial ke langganan (invoice stabil)
--   bng       → baca live sisi jaringan → rakit atribut RADIUS Mikrotik-Rate-Limit
-- Paket tak dihapus keras (integritas snapshot & grup RADIUS); dinonaktifkan via `active`.
-- ============================================================

CREATE TABLE plan (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid          NOT NULL REFERENCES tenant (id),

    -- Komersial
    name                  varchar(60)   NOT NULL,
    description           varchar(200),
    price                 numeric(14, 2) NOT NULL,

    -- Jaringan — rate dasar (Mbps)
    down_mbps             integer       NOT NULL,
    up_mbps               integer       NOT NULL,

    -- Jaringan — burst (opsional, berpasangan)
    down_burst_mbps       integer,
    up_burst_mbps         integer,
    down_threshold_mbps   integer,
    up_threshold_mbps     integer,
    burst_time_sec        integer,

    -- Jaringan — limit-at (jaminan minimum, opsional berpasangan)
    down_min_mbps         integer,
    up_min_mbps           integer,

    -- QoS
    priority              integer       NOT NULL DEFAULT 8,
    connection_limit      integer,

    -- FUP (fair-usage)
    fup_enabled           boolean       NOT NULL DEFAULT false,
    fup_quota_mb          bigint,
    fup_down_mbps         integer,
    fup_up_mbps           integer,

    -- Ketersediaan (nama ServiceType digabung koma, mis. "PPPOE,STATIC")
    service_types         varchar(100)  NOT NULL DEFAULT 'PPPOE',

    -- Override siklus billing (NULL = ikut kebijakan global)
    prorate_on_activation boolean,
    billing_day_of_month  integer,
    due_days              integer,
    grace_days            integer,
    auto_isolir           boolean,

    active                boolean       NOT NULL DEFAULT true,
    created_at            timestamptz   NOT NULL DEFAULT now(),
    updated_at            timestamptz   NOT NULL DEFAULT now(),

    -- Nama paket unik per tenant.
    CONSTRAINT uq_plan_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_plan_priority CHECK (priority BETWEEN 1 AND 8),
    CONSTRAINT ck_plan_billing_day CHECK (billing_day_of_month IS NULL OR billing_day_of_month BETWEEN 1 AND 31),
    CONSTRAINT ck_plan_price CHECK (price >= 0)
);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE plan FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plan
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
