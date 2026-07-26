-- ============================================================
-- Phase 7 (slice 7a): modul BNG — BRAS/RADIUS & identitas jaringan pelanggan
--
-- Tiga tabel fondasi:
--   rate_profile      paket layanan (kecepatan + pemetaan ke profil RADIUS) yang
--                     bisa dipakai ulang banyak langganan — objek paket "sungguhan"
--                     di samping teks bebas packageName/bandwidthMbps di module customer.
--   nas               registri BRAS/BNG (router master penutup sesi PPPoE, klien RADIUS);
--                     coa_secret disimpan TERENKRIPSI (batas enkripsi di adapter).
--   subscriber_access identitas jaringan (akun PPPoE) sebuah langganan; secret PPPoE
--                     TERENKRIPSI. Tertaut ke langganan/pelanggan (module customer) lewat
--                     uuid polos TANPA foreign key lintas-module. FK hanya intra-module
--                     (ke rate_profile & nas).
--
-- Slice fondasi: murni data, belum ada perintah nyata ke BRAS.
-- ============================================================

CREATE TABLE rate_profile (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid         NOT NULL REFERENCES tenant (id),
    name                varchar(60)  NOT NULL,
    description         varchar(200),
    down_mbps           integer      NOT NULL,
    up_mbps             integer      NOT NULL,
    -- Nama profil yang dikenal BRAS/RADIUS (profil PPP Mikrotik / grup FreeRADIUS).
    radius_profile_name varchar(100),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    -- Nama paket unik per tenant.
    CONSTRAINT uq_rate_profile_name UNIQUE (tenant_id, name)
);

CREATE TABLE nas (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    name           varchar(80)  NOT NULL,
    vendor         varchar(20)  NOT NULL,
    address        varchar(255),
    nas_identifier varchar(128),
    -- Ciphertext secret CoA; kolom dilonggarkan agar muat hasil enkripsi.
    coa_secret     varchar(512),
    -- Agent on-prem yang menjangkau BRAS ini (module monitoring), uuid polos tanpa FK.
    collector_id   uuid,
    enabled        boolean      NOT NULL DEFAULT true,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_nas_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_nas_vendor CHECK (vendor IN ('MIKROTIK', 'CISCO', 'JUNIPER', 'FREERADIUS', 'OTHER'))
);

CREATE TABLE subscriber_access (
    id              uuid PRIMARY KEY,
    tenant_id       uuid         NOT NULL REFERENCES tenant (id),
    -- Tautan ke langganan & pelanggan (module customer), uuid polos tanpa FK lintas-module.
    subscription_id uuid         NOT NULL,
    customer_id     uuid         NOT NULL,
    username        varchar(64)  NOT NULL,
    auth_type       varchar(20)  NOT NULL,
    -- Ciphertext password PPPoE.
    secret          varchar(512) NOT NULL,
    -- FK intra-module diperbolehkan (menjaga integritas paket/BRAS yang dirujuk).
    rate_profile_id uuid         NOT NULL REFERENCES rate_profile (id),
    nas_id          uuid         REFERENCES nas (id),
    status          varchar(20)  NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    -- Username unik per tenant, dan satu langganan maksimal satu akun.
    CONSTRAINT uq_subscriber_access_username UNIQUE (tenant_id, username),
    CONSTRAINT uq_subscriber_access_subscription UNIQUE (tenant_id, subscription_id),
    CONSTRAINT ck_subscriber_access_auth CHECK (auth_type IN ('PPPOE')),
    CONSTRAINT ck_subscriber_access_status CHECK (status IN ('ACTIVE', 'ISOLATED', 'TERMINATED'))
);
-- Daftar akun per pelanggan (panel di halaman pelanggan).
CREATE INDEX ix_subscriber_access_customer ON subscriber_access (tenant_id, customer_id);
-- Penghitungan pemakaian saat menghapus paket / BRAS.
CREATE INDEX ix_subscriber_access_rate_profile ON subscriber_access (tenant_id, rate_profile_id);
CREATE INDEX ix_subscriber_access_nas ON subscriber_access (tenant_id, nas_id);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE rate_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE rate_profile FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON rate_profile
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE nas ENABLE ROW LEVEL SECURITY;
ALTER TABLE nas FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON nas
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE subscriber_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriber_access FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON subscriber_access
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
