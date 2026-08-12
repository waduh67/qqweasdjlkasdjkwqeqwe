-- ============================================================
-- Simpul yang disentuh kabel — dari SEPASANG ujung jadi BARISAN singgahan
--
-- Sejak V2 sebuah kabel cuma punya dua kolom simpul: from dan to. Itu benar
-- selama kabel = seutas tali antara dua kotak. Di lapangan tidak begitu:
-- satu selubung distribusi berangkat dari ODC lalu MELEWATI belasan ODP, dan
-- di tiap ODP selubungnya dikupas, satu-dua core diambil, sisanya jalan terus.
-- ODP ke-3 bukan "from", bukan "to" — ia singgahan di tengah bentang, dan
-- skema lama tak punya tempat untuk menyebutnya.
--
-- Akibatnya kemarin ditambal dengan GEOMETRI: "kabel dianggap lewat sebuah
-- kotak kalau rutenya <500 m dari kotak itu". Tambalan itu salah secara asas.
-- Jarak bukan topologi — kabel yang kebetulan melintas di depan rumah tetap
-- terhitung "sampai" ke kotak yang tak pernah dibukanya, sedangkan kabel yang
-- benar-benar dikupas di sana bisa luput hanya karena rutenya digambar kasar.
-- Yang menentukan sebuah kabel bisa disambung di sebuah kotak BUKAN jaraknya,
-- melainkan perbuatan manusia: selubungnya dibuka di situ, atau tidak.
--
-- Tabel ini mencatat perbuatan itu apa adanya. Tiap baris = satu simpul yang
-- disinggahi kabel, berurutan sepanjang rute, dengan PERAN yang jelas:
--
--   END     ujung kabel. Selubungnya habis di sini, semua core terbuka.
--   TAPPED  dikupas di tengah bentang. Sebagian core diambil, sisanya lewat.
--   PASSING cuma numpang lewat / digulung di dalam kotak. Selubung UTUH — tak
--           bisa disambung, tapi WAJIB tercatat supaya teknisi yang membuka
--           kotak tahu kabel itu ada dan tidak memotong yang salah.
--
-- Urutan (`sequence`) ikut arah gambar rute: 0 = pangkal, terbesar = ujung.
-- Arahnya dipertahankan karena laporan "dari mana ke mana" dan penelusuran
-- hulu-hilir bergantung padanya.
-- ============================================================

CREATE TABLE cable_attachment
(
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    -- Ikut terhapus bersama kabelnya: singgahan tak punya arti tanpa selubungnya.
    cable_id    uuid        NOT NULL REFERENCES cable (id) ON DELETE CASCADE,
    sequence    integer     NOT NULL,
    -- Polimorfik seperti ujung kabel yang lama: simpulnya bisa ODC, ODP, joint
    -- box, sampai rumah pelanggan. Keberadaannya diperiksa domain, bukan FK.
    node_kind   varchar(20) NOT NULL,
    node_id     uuid        NOT NULL,
    role        varchar(20) NOT NULL,
    -- Pindahan dari cable.from_pon_port_id / from_port_number / to_port_number.
    pon_port_id uuid,
    port_number integer,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_cable_attachment_node_kind
        CHECK (node_kind IN ('SITE', 'OLT', 'ODF', 'ODC', 'ODP', 'JOINT_BOX', 'CUSTOMER')),
    CONSTRAINT ck_cable_attachment_role CHECK (role IN ('END', 'TAPPED', 'PASSING')),
    CONSTRAINT ck_cable_attachment_sequence CHECK (sequence >= 0),
    CONSTRAINT ck_cable_attachment_port CHECK (port_number IS NULL OR port_number >= 1),
    -- Port cuma ada di UJUNG. Kabel yang cuma dikupas di tengah tidak "dicolok"
    -- ke port mana pun — yang bertemu di sana adalah core dengan core, dan itu
    -- dicatat sebagai sambungan serat (fiber_connection), bukan sebagai port.
    CONSTRAINT ck_cable_attachment_mid_no_port
        CHECK (role = 'END' OR (pon_port_id IS NULL AND port_number IS NULL)),
    -- Satu urutan sekali per kabel: tanpa ini dua singgahan bisa mengaku titik
    -- yang sama sepanjang rute dan arah hulu-hilir jadi tak tentu.
    --
    -- DEFERRABLE karena menyisipkan satu ODP di tengah menggeser urutan SEMUA
    -- singgahan sesudahnya. Pergeseran itu sah sebagai satu kesatuan, tapi
    -- baris-per-baris ia sempat bertabrakan dengan tetangganya yang belum
    -- sempat digeser — pemeriksaan di akhir transaksi menilai hasilnya, bukan
    -- langkah setengah jadinya. Berlaku juga untuk simpul: ujung kabel yang
    -- dipindahkan ke kotak yang tadinya cuma disinggahi menukar peran dua baris
    -- sekaligus.
    CONSTRAINT ux_cable_attachment_sequence UNIQUE (cable_id, sequence)
        DEFERRABLE INITIALLY DEFERRED,
    -- Satu kabel menyinggahi sebuah simpul paling banyak sekali. Kabel yang
    -- masuk-keluar kotak yang sama tetap SATU singgahan, bukan dua.
    CONSTRAINT ux_cable_attachment_node UNIQUE (cable_id, node_id)
        DEFERRABLE INITIALLY DEFERRED
);

-- Jalan baca utama: "kabel apa saja yang menyentuh kotak ini" — dipakai meja
-- sambung tiap kali sebuah closure dibuka. Kolom cable_id sudah terindeks
-- lewat awalan ux_cable_attachment_sequence, jadi tak perlu indeks sendiri.
CREATE INDEX ix_cable_attachment_node ON cable_attachment (node_id);

-- ------------------------------------------------------------
-- Backfill — tak ada satu pun catatan lama yang hilang.
--
-- Tiap kabel yang sudah ada langsung punya dua singgahan END dari from/to-nya,
-- lengkap dengan nomor portnya. Di antaranya disisipkan singgahan TAPPED yang
-- DISIMPULKAN dari kenyataan yang sudah tercatat: kotak yang menyambung core
-- milik kabel ini padahal bukan ujungnya — itu persis definisi "dikupas di
-- tengah", dan datanya sudah ada di fiber_connection sejak V89.
--
-- Urutannya dihitung sekali di sini dengan ST_LineLocatePoint. Ini SATU-SATUNYA
-- tempat geometri boleh ikut bicara, dan perannya cuma mengurutkan singgahan
-- yang keanggotaannya sudah pasti — bukan menentukan siapa anggotanya.
--
-- RLS sumber dimatikan sebentar: Flyway jalan sebagai role NOBYPASSRLS tanpa
-- GUC app.tenant_id, jadi SELECT-nya akan melihat NOL baris dan diam-diam
-- mengisi nol singgahan. FORCE bertahan melewati ENABLE (relforcerowsecurity
-- terpisah dari relrowsecurity). Pola sama V87.
-- ------------------------------------------------------------
ALTER TABLE cable DISABLE ROW LEVEL SECURITY;
ALTER TABLE cable_core DISABLE ROW LEVEL SECURITY;
ALTER TABLE fiber_connection DISABLE ROW LEVEL SECURITY;
ALTER TABLE fiber_connection_end DISABLE ROW LEVEL SECURITY;
ALTER TABLE odc DISABLE ROW LEVEL SECURITY;
ALTER TABLE odp DISABLE ROW LEVEL SECURITY;
ALTER TABLE joint_box DISABLE ROW LEVEL SECURITY;
ALTER TABLE odf DISABLE ROW LEVEL SECURITY;

WITH node_point AS (SELECT id, location FROM odc
                    UNION ALL
                    SELECT id, location FROM odp
                    UNION ALL
                    SELECT id, location FROM joint_box
                    UNION ALL
                    SELECT id, location FROM odf),
     tapped AS (SELECT DISTINCT c.id            AS cable_id,
                                c.tenant_id     AS tenant_id,
                                fc.closure_kind AS node_kind,
                                fc.closure_id   AS node_id
                FROM cable c
                         JOIN cable_core cc ON cc.cable_id = c.id
                         JOIN fiber_connection_end fce ON fce.core_id = cc.id
                         JOIN fiber_connection fc ON fc.id = fce.connection_id
                WHERE fc.closure_id <> c.from_id
                  AND fc.closure_id <> c.to_id),
     singgahan AS (SELECT c.id                   AS cable_id,
                          c.tenant_id            AS tenant_id,
                          c.from_kind            AS node_kind,
                          c.from_id              AS node_id,
                          'END'                  AS role,
                          c.from_pon_port_id     AS pon_port_id,
                          c.from_port_number     AS port_number,
                          0.0::double precision  AS along
                   FROM cable c
                   UNION ALL
                   SELECT c.id,
                          c.tenant_id,
                          c.to_kind,
                          c.to_id,
                          'END',
                          NULL,
                          c.to_port_number,
                          1.0::double precision
                   FROM cable c
                   UNION ALL
                   SELECT t.cable_id,
                          t.tenant_id,
                          t.node_kind,
                          t.node_id,
                          'TAPPED',
                          NULL,
                          NULL,
                          -- Dijepit ke (0,1) supaya singgahan tengah tak pernah
                          -- mendahului pangkal atau melewati ujung, sekalipun
                          -- kotaknya digambar agak melenceng dari rutenya.
                          LEAST(GREATEST(COALESCE(ST_LineLocatePoint(c.route, np.location), 0.5), 0.001), 0.999)
                   FROM tapped t
                            JOIN cable c ON c.id = t.cable_id
                            LEFT JOIN node_point np ON np.id = t.node_id)
INSERT
INTO cable_attachment (id, tenant_id, cable_id, sequence, node_kind, node_id, role, pon_port_id, port_number)
SELECT gen_random_uuid(),
       s.tenant_id,
       s.cable_id,
       (row_number() OVER (PARTITION BY s.cable_id ORDER BY s.along, s.node_id))::int - 1,
       s.node_kind,
       s.node_id,
       s.role,
       s.pon_port_id,
       s.port_number
FROM singgahan s;

ALTER TABLE cable ENABLE ROW LEVEL SECURITY;
ALTER TABLE cable_core ENABLE ROW LEVEL SECURITY;
ALTER TABLE fiber_connection ENABLE ROW LEVEL SECURITY;
ALTER TABLE fiber_connection_end ENABLE ROW LEVEL SECURITY;
ALTER TABLE odc ENABLE ROW LEVEL SECURITY;
ALTER TABLE odp ENABLE ROW LEVEL SECURITY;
ALTER TABLE joint_box ENABLE ROW LEVEL SECURITY;
ALTER TABLE odf ENABLE ROW LEVEL SECURITY;

-- ------------------------------------------------------------
-- Kolom lama DIHAPUS, bukan ditandai usang.
--
-- V94 pernah memilih sebaliknya untuk cable.from_port_number, dan alasannya
-- masih benar untuk kasus itu: kolomnya memuat catatan bertahun-tahun yang tak
-- punya rumah baru. Di sini keadaannya berbeda — SELURUH isi tujuh kolom ini
-- baru saja pindah utuh ke cable_attachment beberapa baris di atas. Yang
-- tertinggal cuma salinan yang akan mulai berbohong begitu singgahan pertama
-- disisipkan di tengah: "to" tak lagi berarti ujung, dan tak ada cara
-- memaksa dua sumber kebenaran tetap sepakat. Menyimpannya bukan mengarsipkan,
-- melainkan memelihara jebakan.
-- ------------------------------------------------------------
DROP INDEX IF EXISTS ix_cable_from;
DROP INDEX IF EXISTS ix_cable_to;

ALTER TABLE cable
    DROP COLUMN from_kind,
    DROP COLUMN from_id,
    DROP COLUMN to_kind,
    DROP COLUMN to_id,
    DROP COLUMN from_pon_port_id,
    DROP COLUMN from_port_number,
    DROP COLUMN to_port_number;

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE cable_attachment
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE cable_attachment
    FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cable_attachment
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
