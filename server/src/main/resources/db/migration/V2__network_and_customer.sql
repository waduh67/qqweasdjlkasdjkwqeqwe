-- ============================================================
-- Phase 1: inventory jaringan + pelanggan + geometri GIS
--
-- Rantai fisik yang dimodelkan:
--   SITE ──▶ OLT ──▶ PON PORT ──▶ ODC ──▶ ODP ──▶ ONU ──▶ CUSTOMER
--                └ kabel FEEDER ─┘  └ DISTRIBUSI ┘ └ DROP ┘
--
-- Port ODP TIDAK dimodelkan sebagai baris tersendiri: kapasitas disimpan di
-- `odp.capacity` dan okupansi diturunkan dari ONU yang menempatinya. Menghemat
-- ratusan ribu baris kosong dan menghilangkan sinkronisasi ganda.
-- ============================================================

-- PostGIS harus sudah dipasang oleh DBA (superuser). Role aplikasi sengaja
-- NOSUPERUSER/NOBYPASSRLS demi Row-Level Security, jadi tidak bisa membuatnya
-- sendiri. Gagal di sini jauh lebih baik daripada gagal saat query pertama.
DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis') THEN
            RAISE EXCEPTION 'Extension postgis belum terpasang. Jalankan sebagai superuser: CREATE EXTENSION postgis;';
        END IF;
    END
$$;

-- ------------------------------------------------------------
-- Site / POP
-- ------------------------------------------------------------
CREATE TABLE site (
    id         uuid PRIMARY KEY,
    tenant_id  uuid                   NOT NULL REFERENCES tenant (id),
    area_id    uuid                   REFERENCES area (id),
    code       varchar(40)            NOT NULL,
    name       varchar(150)           NOT NULL,
    address    varchar(500),
    location   geometry(Point, 4326)  NOT NULL,
    created_at timestamptz            NOT NULL DEFAULT now(),
    updated_at timestamptz            NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

-- ------------------------------------------------------------
-- OLT + PON port
-- ------------------------------------------------------------
CREATE TABLE olt (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    site_id        uuid         NOT NULL REFERENCES site (id),
    code           varchar(40)  NOT NULL,
    name           varchar(150) NOT NULL,
    vendor         varchar(20)  NOT NULL,
    model          varchar(80),
    management_ip  varchar(45),
    -- Kredensial SNMP disimpan terenkripsi oleh aplikasi (lihat SecretCipher),
    -- tidak pernah dikembalikan lewat API.
    snmp_community text,
    status         varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    CONSTRAINT ck_olt_vendor CHECK (vendor IN ('ZTE', 'HUAWEI', 'FIBERHOME', 'NOKIA', 'OTHER')),
    CONSTRAINT ck_olt_status CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED'))
);
CREATE INDEX ix_olt_site ON olt (site_id);

CREATE TABLE pon_port (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    olt_id      uuid        NOT NULL REFERENCES olt (id) ON DELETE CASCADE,
    -- Notasi lapangan slot/port, mis. frame 1 slot 2 port 3 -> "1/2/3".
    label       varchar(30) NOT NULL,
    description varchar(255),
    status      varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, olt_id, label),
    CONSTRAINT ck_pon_port_status CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED'))
);
CREATE INDEX ix_pon_port_olt ON pon_port (olt_id);

-- ------------------------------------------------------------
-- ODC (splitter tingkat 1) dan ODP (splitter tingkat 2)
-- ------------------------------------------------------------
CREATE TABLE odc (
    id             uuid PRIMARY KEY,
    tenant_id      uuid                  NOT NULL REFERENCES tenant (id),
    area_id        uuid                  REFERENCES area (id),
    -- Sumber feeder. NULL berarti ODC sudah terpasang fisik tapi belum di-uplink.
    pon_port_id    uuid                  REFERENCES pon_port (id) ON DELETE SET NULL,
    code           varchar(40)           NOT NULL,
    name           varchar(150)          NOT NULL,
    address        varchar(500),
    location       geometry(Point, 4326) NOT NULL,
    splitter_ratio varchar(10)           NOT NULL,
    capacity       integer               NOT NULL,
    status         varchar(20)           NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz           NOT NULL DEFAULT now(),
    updated_at     timestamptz           NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    CONSTRAINT ck_odc_capacity CHECK (capacity > 0 AND capacity <= 1024),
    CONSTRAINT ck_odc_status CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED'))
);
CREATE INDEX ix_odc_pon_port ON odc (pon_port_id);

CREATE TABLE odp (
    id             uuid PRIMARY KEY,
    tenant_id      uuid                  NOT NULL REFERENCES tenant (id),
    area_id        uuid                  REFERENCES area (id),
    odc_id         uuid                  REFERENCES odc (id) ON DELETE SET NULL,
    code           varchar(40)           NOT NULL,
    name           varchar(150)          NOT NULL,
    address        varchar(500),
    location       geometry(Point, 4326) NOT NULL,
    splitter_ratio varchar(10)           NOT NULL,
    -- Jumlah port drop yang tersedia; ONU menempati salah satunya.
    capacity       integer               NOT NULL,
    status         varchar(20)           NOT NULL DEFAULT 'ACTIVE',
    created_at     timestamptz           NOT NULL DEFAULT now(),
    updated_at     timestamptz           NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    CONSTRAINT ck_odp_capacity CHECK (capacity > 0 AND capacity <= 256),
    CONSTRAINT ck_odp_status CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED'))
);
CREATE INDEX ix_odp_odc ON odp (odc_id);
CREATE INDEX ix_odp_area ON odp (area_id);

-- ------------------------------------------------------------
-- Kabel — ujungnya polimorfik (site/olt/odc/odp/customer)
-- ------------------------------------------------------------
CREATE TABLE cable (
    id            uuid PRIMARY KEY,
    tenant_id     uuid                       NOT NULL REFERENCES tenant (id),
    code          varchar(40)                NOT NULL,
    name          varchar(150)               NOT NULL,
    cable_type    varchar(20)                NOT NULL,
    core_count    integer                    NOT NULL,
    route         geometry(LineString, 4326) NOT NULL,
    -- Panjang diturunkan dari geometri saat simpan (termasuk slack), disimpan
    -- agar bisa di-sort/agregasi tanpa menghitung ulang.
    length_meters double precision           NOT NULL,
    from_kind     varchar(20)                NOT NULL,
    from_id       uuid                       NOT NULL,
    to_kind       varchar(20)                NOT NULL,
    to_id         uuid                       NOT NULL,
    status        varchar(20)                NOT NULL DEFAULT 'ACTIVE',
    created_at    timestamptz                NOT NULL DEFAULT now(),
    updated_at    timestamptz                NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    CONSTRAINT ck_cable_type CHECK (cable_type IN ('FEEDER', 'DISTRIBUTION', 'DROP')),
    CONSTRAINT ck_cable_core_count CHECK (core_count > 0 AND core_count <= 288),
    CONSTRAINT ck_cable_from_kind CHECK (from_kind IN ('SITE', 'OLT', 'ODC', 'ODP', 'CUSTOMER')),
    CONSTRAINT ck_cable_to_kind CHECK (to_kind IN ('SITE', 'OLT', 'ODC', 'ODP', 'CUSTOMER')),
    CONSTRAINT ck_cable_status CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED'))
);
CREATE INDEX ix_cable_from ON cable (from_kind, from_id);
CREATE INDEX ix_cable_to ON cable (to_kind, to_id);

-- ------------------------------------------------------------
-- Pelanggan, langganan, ONU
-- ------------------------------------------------------------
CREATE TABLE customer (
    id         uuid PRIMARY KEY,
    tenant_id  uuid                  NOT NULL REFERENCES tenant (id),
    area_id    uuid                  REFERENCES area (id),
    code       varchar(40)           NOT NULL,
    name       varchar(150)          NOT NULL,
    phone      varchar(30),
    email      varchar(255),
    address    varchar(500)          NOT NULL,
    location   geometry(Point, 4326) NOT NULL,
    status     varchar(20)           NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz           NOT NULL DEFAULT now(),
    updated_at timestamptz           NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    CONSTRAINT ck_customer_status CHECK (status IN ('PROSPECT', 'ACTIVE', 'SUSPENDED', 'TERMINATED'))
);
CREATE INDEX ix_customer_area ON customer (area_id);

CREATE TABLE subscription (
    id             uuid PRIMARY KEY,
    tenant_id      uuid           NOT NULL REFERENCES tenant (id),
    customer_id    uuid           NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    package_name   varchar(100)   NOT NULL,
    bandwidth_mbps integer        NOT NULL,
    monthly_fee    numeric(14, 2) NOT NULL,
    status         varchar(20)    NOT NULL DEFAULT 'PENDING',
    activated_at   timestamptz,
    terminated_at  timestamptz,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ck_subscription_bandwidth CHECK (bandwidth_mbps > 0),
    CONSTRAINT ck_subscription_status CHECK (status IN ('PENDING', 'ACTIVE', 'ISOLATED', 'TERMINATED'))
);
CREATE INDEX ix_subscription_customer ON subscription (customer_id);

CREATE TABLE onu (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid         NOT NULL REFERENCES tenant (id),
    customer_id           uuid         NOT NULL REFERENCES customer (id) ON DELETE CASCADE,
    odp_id                uuid         REFERENCES odp (id) ON DELETE SET NULL,
    -- Nomor port pada ODP (1..odp.capacity). Batas atas divalidasi domain karena
    -- CHECK tidak bisa membaca kolom tabel lain.
    odp_port_number       integer,
    serial_number         varchar(60)  NOT NULL,
    model                 varchar(80),
    -- Redaman terukur saat instalasi; jadi baseline deteksi degradasi di Phase 2.
    install_rx_power_dbm  double precision,
    status                varchar(20)  NOT NULL DEFAULT 'PENDING',
    installed_at          timestamptz,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, serial_number),
    CONSTRAINT ck_onu_port_number CHECK (odp_port_number IS NULL OR odp_port_number > 0),
    CONSTRAINT ck_onu_status CHECK (status IN ('PENDING', 'ONLINE', 'OFFLINE', 'LOS', 'DISMANTLED')),
    -- ONU yang terpasang wajib punya ODP sekaligus nomor port, tidak boleh separuh.
    CONSTRAINT ck_onu_attachment CHECK ((odp_id IS NULL) = (odp_port_number IS NULL))
);
-- Aturan bisnis inti: satu port ODP hanya boleh ditempati satu ONU.
CREATE UNIQUE INDEX uq_onu_odp_port ON onu (odp_id, odp_port_number) WHERE odp_id IS NOT NULL;
CREATE INDEX ix_onu_customer ON onu (customer_id);

-- ------------------------------------------------------------
-- Indeks spasial: wajib agar query bbox peta tidak jadi seq-scan.
-- ------------------------------------------------------------
CREATE INDEX ix_site_location ON site USING GIST (location);
CREATE INDEX ix_odc_location ON odc USING GIST (location);
CREATE INDEX ix_odp_location ON odp USING GIST (location);
CREATE INDEX ix_customer_location ON customer USING GIST (location);
CREATE INDEX ix_cable_route ON cable USING GIST (route);

-- ------------------------------------------------------------
-- Row-Level Security — sama seperti V1, lapisan kedua di bawah @TenantId.
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'site', 'olt', 'pon_port', 'odc', 'odp', 'cable',
            'customer', 'subscription', 'onu'
            ]
            LOOP
                EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format($f$
                    CREATE POLICY tenant_isolation ON %I
                        USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                        WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                    $f$, t);
            END LOOP;
    END
$$;
