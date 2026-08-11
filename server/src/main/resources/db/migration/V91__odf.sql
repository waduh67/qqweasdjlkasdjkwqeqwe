-- ============================================================
-- ODF (Optical Distribution Frame) — rak terminasi serat di dalam POP.
--
-- Kabel outdoor tidak boleh dicolok langsung ke OLT: selubungnya kaku, seratnya
-- telanjang, dan tak ada konektor di ujungnya. Yang terjadi sebenarnya: kabel
-- BERHENTI di ODF, tiap core di-splice ke pigtail, pigtail dipasang ke adapter,
-- lalu dari adapter itulah patchcord ditarik ke PON port OLT.
--
-- Karena itu satu port ODF punya DUA SISI dan dua-duanya adalah sambungan:
--
--     core kabel feeder --splice--> [ BELAKANG | port 7 | DEPAN ] --patch--> PON 1/1/1
--
-- Sisi belakang menghadap kabel luar, sisi depan menghadap OLT. Yang dicabut
-- teknisi saat pindah PON port cuma patchcord di sisi depan; sisi belakangnya
-- tak tersentuh selama bertahun-tahun. Tanpa dua sisi ini, "core mana ke PON
-- mana" cuma ada di kepala orang — dan itulah keadaan yang bikin tiap gangguan
-- jadi tebak-tebakan.
--
-- ODF sengaja OPSIONAL, bukan wajib: ISP kecil yang OLT-nya nempel di dinding
-- memang menyambung feeder langsung ke pigtail OLT. Yang punya POP beneran
-- (rak, banyak feeder) tak bisa hidup tanpanya.
-- ============================================================

CREATE TABLE odf
(
    id         uuid PRIMARY KEY,
    tenant_id  uuid                  NOT NULL REFERENCES tenant (id),
    area_id    uuid REFERENCES area (id),
    -- ODF selalu berada DI DALAM sebuah POP; tak ada ODF yang berdiri di pinggir
    -- jalan. Karena itu site-nya wajib, beda dari joint box yang justru hidup di
    -- tengah rute.
    site_id    uuid                  NOT NULL REFERENCES site (id),
    code       varchar(40)           NOT NULL,
    name       varchar(150)          NOT NULL,
    -- Titiknya sendiri, bukan titik site-nya: satu POP bisa berisi beberapa rak,
    -- dan peta yang menumpuk semuanya di satu koordinat menyembunyikan itu.
    location   geometry(Point, 4326) NOT NULL,
    -- Jumlah port adapter di rak. Kelipatan 12 di lapangan (12/24/48/96/144),
    -- tapi tak dipaksakan di sini — rak campuran memang ada.
    port_count integer               NOT NULL,
    status     varchar(20)           NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz           NOT NULL DEFAULT now(),
    updated_at timestamptz           NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    CONSTRAINT ck_odf_port_count CHECK (port_count > 0 AND port_count <= 1152),
    CONSTRAINT ck_odf_status CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED'))
);
CREATE INDEX ix_odf_site ON odf (site_id);
CREATE INDEX ix_odf_area ON odf (area_id);
CREATE INDEX ix_odf_location ON odf USING GIST (location);

-- Feeder yang benar berawal & berakhir di ODF, bukan di badan OLT. Tanpa ini,
-- rak terminasi cuma jadi hiasan peta yang tak boleh disentuh kabel.
ALTER TABLE cable
    DROP CONSTRAINT ck_cable_from_kind;
ALTER TABLE cable
    DROP CONSTRAINT ck_cable_to_kind;
ALTER TABLE cable
    ADD CONSTRAINT ck_cable_from_kind
        CHECK (from_kind IN ('SITE', 'OLT', 'ODF', 'ODC', 'ODP', 'JOINT_BOX', 'CUSTOMER'));
ALTER TABLE cable
    ADD CONSTRAINT ck_cable_to_kind
        CHECK (to_kind IN ('SITE', 'OLT', 'ODF', 'ODC', 'ODP', 'JOINT_BOX', 'CUSTOMER'));

-- ------------------------------------------------------------
-- Sisi port ODF pada titik sambungan.
--
-- Sampai kini sebuah titik non-core dianggap tunggal: id simpul + nomor port
-- sudah menentukan tempatnya, dan `ux_fiber_connection_end_node` melarangnya
-- dipakai dua kali. Untuk kaki splitter & PON port itu benar; untuk port ODF
-- TIDAK — port yang sama memang dipakai dua sambungan, satu di tiap sisinya.
--
-- Karena itu sisi ikut jadi bagian identitas titik, dan larangan "satu titik
-- sekali" tetap ditegakkan DB: yang dilarang kini adalah menduduki SISI yang
-- sama dua kali, bukan portnya.
-- ------------------------------------------------------------
ALTER TABLE fiber_connection_end
    ADD COLUMN port_side varchar(5);

ALTER TABLE fiber_connection_end
    ADD CONSTRAINT ck_fce_port_side CHECK (
        CASE
            WHEN point_kind = 'ODF_PORT' THEN port_side IN ('BACK', 'FRONT')
            ELSE port_side IS NULL
            END
        );

DROP INDEX ux_fiber_connection_end_node;
CREATE UNIQUE INDEX ux_fiber_connection_end_node
    ON fiber_connection_end (point_kind, node_id, port_number, port_side) NULLS NOT DISTINCT
    WHERE core_id IS NULL;

ALTER TABLE odf
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE odf
    FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON odf
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
