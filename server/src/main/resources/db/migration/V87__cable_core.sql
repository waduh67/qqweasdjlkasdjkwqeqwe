-- ============================================================
-- Core kabel — kabel berhenti jadi "sehelai benang berlabel jumlah"
--
-- Sampai sekarang `cable.core_count` cuma ANGKA. Akibatnya, kabel 8 core yang
-- melewati ODP-1..ODP-8 tak punya tempat untuk mencatat "ODP-3 dapat core 3",
-- dan operator terpaksa menggambar kabel terpisah dari ODC ke tiap ODP —
-- padahal di lapangan selubungnya SATU dan cuma di-tap di tiap ODP.
--
-- Tabel ini memberi tiap serat identitasnya sendiri: nomor, tube, warna
-- (diturunkan, tak disimpan), status, dan catatan lapangan. Konektivitas nanti
-- (fiber_connection) menempel ke CORE, bukan ke kabel — itulah inti desain
-- ulang ini; lihat docs/topologi-kabel.html.
--
-- `tube_number` DISIMPAN, bukan sekadar dihitung dari nomor core: kabel besar
-- (24c ke atas) dibagi tube @12 core dan warna core BERULANG tiap tube. Tanpa
-- nomor tube, "core biru" pada kabel 144 core menunjuk dua belas serat yang
-- berbeda dan teknisi menyambung yang salah.
-- ============================================================

CREATE TABLE cable_core
(
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    -- Ikut terhapus bersama kabelnya: core tak punya arti tanpa selubungnya.
    cable_id    uuid        NOT NULL REFERENCES cable (id) ON DELETE CASCADE,
    tube_number integer     NOT NULL,
    core_number integer     NOT NULL,
    status      varchar(20) NOT NULL,
    -- Catatan lapangan bebas: "ke ODP-3 Jl. Melati", "redaman tinggi 2026-08".
    note        varchar(200),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_cable_core_status CHECK (status IN ('FREE', 'USED', 'RESERVED', 'DAMAGED')),
    CONSTRAINT ck_cable_core_number CHECK (core_number >= 1),
    CONSTRAINT ck_cable_core_tube CHECK (tube_number >= 1)
);

-- Satu nomor core cuma boleh ada sekali per kabel — ini yang mencegah dua
-- pelanggan diam-diam dijadwalkan di serat yang sama. Sekaligus indeks baca
-- utamanya: layar "Kelola Core" selalu mengurut nomor core untuk satu kabel.
CREATE UNIQUE INDEX ux_cable_core_number ON cable_core (cable_id, core_number);

-- ------------------------------------------------------------
-- Backfill: tiap kabel yang sudah ada langsung punya barisan corenya, terisi
-- FREE. Dua hal yang membuatnya sah lintas-tenant:
--
--   1. INSERT jalan SEBELUM RLS `cable_core` dinyalakan, jadi barisnya masuk
--      tanpa bergantung pada peran yang dipakai Flyway.
--   2. `cable` — SUMBER-nya — ter-FORCE RLS, dan Flyway jalan sebagai role
--      NOBYPASSRLS tanpa GUC app.tenant_id, sehingga SELECT-nya akan melihat
--      NOL kabel dan diam-diam mengisi nol core. Karena itu RLS-nya dimatikan
--      sebentar di sini. FORCE bertahan melewati ENABLE (relforcerowsecurity
--      terpisah dari relrowsecurity). Pola sama V29/V39/V44/V52/V75.
-- ------------------------------------------------------------
ALTER TABLE cable DISABLE ROW LEVEL SECURITY;

INSERT INTO cable_core (id, tenant_id, cable_id, tube_number, core_number, status)
SELECT gen_random_uuid(),
       c.tenant_id,
       c.id,
       ((n - 1) / 12) + 1, -- 12 core per tube, urutan standar TIA-598
       n,
       'FREE'
FROM cable c
         CROSS JOIN LATERAL generate_series(1, c.core_count) AS n;

ALTER TABLE cable ENABLE ROW LEVEL SECURITY;

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE cable_core
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE cable_core
    FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cable_core
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
