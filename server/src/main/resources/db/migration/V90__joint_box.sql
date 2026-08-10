-- ============================================================
-- Joint box (JB) — kotak sambung serat.
--
-- Simpul yang selama ini hilang dari model, padahal di lapangan ada di
-- mana-mana. Isinya cuma tray dan sambungan fusion: TIDAK ADA SPLITTER di
-- dalamnya, jadi ia tak "membagi cahaya" — ia menyambung serat ke serat.
--
-- Tiga pekerjaan yang membuatnya perlu:
--   1. Sambungan haspel. Kabel dijual per haspel (mis. 2 km); jalur 5 km berarti
--      tiga potong yang harus disambung di dua titik. Titik itu adalah JB.
--   2. Persimpangan. Jalur bercabang di tengah: sebagian core lanjut ke kanan,
--      sebagian ke kiri. JB memegang pemetaannya — penomoran core mulai dari 1
--      lagi di tiap kabel cabang, dan hanya JB yang tahu core 5 kabel induk
--      menjadi core 1 kabel cabang kanan.
--   3. Perbaikan darurat. Kabel putus tertimpa pohon disambung di tempat; sebuah
--      JB muncul di tengah rute yang tadinya mulus dan harus tercatat, karena
--      tiap sambungan menambah redaman dan jadi tersangka pertama saat rusak.
--
-- Karena itu JB boleh jadi UJUNG kabel maupun titik di TENGAH rute. Core yang
-- lewat begitu saja tanpa disambung tak perlu baris apa pun — yang tercatat
-- hanyalah sambungan yang benar-benar dibuat (lihat fiber_connection, V89).
-- ============================================================

CREATE TABLE joint_box (
    id         uuid PRIMARY KEY,
    tenant_id  uuid                  NOT NULL REFERENCES tenant (id),
    area_id    uuid                  REFERENCES area (id),
    code       varchar(40)           NOT NULL,
    name       varchar(150)          NOT NULL,
    address    varchar(500),
    location   geometry(Point, 4326) NOT NULL,
    -- Berapa tray yang muat di dalamnya. Informasi lapangan untuk teknisi yang
    -- membuka kotaknya; batas yang ditegakkan sistem ada di `capacity`.
    tray_count integer               NOT NULL DEFAULT 1,
    -- Batas jumlah sambungan yang muat. Umumnya tray_count x 12 atau x 24.
    capacity   integer               NOT NULL,
    status     varchar(20)           NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz           NOT NULL DEFAULT now(),
    updated_at timestamptz           NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code),
    CONSTRAINT ck_joint_box_tray_count CHECK (tray_count > 0 AND tray_count <= 64),
    CONSTRAINT ck_joint_box_capacity CHECK (capacity > 0 AND capacity <= 1536),
    CONSTRAINT ck_joint_box_status CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'INACTIVE', 'PLANNED'))
);
CREATE INDEX ix_joint_box_area ON joint_box (area_id);
CREATE INDEX ix_joint_box_location ON joint_box USING GIST (location);

-- Ujung kabel boleh berupa joint box. Tanpa ini, kabel feeder yang disambung di
-- tengah jalan tetap harus digambar utuh OLT->ODC dan sambungannya tak punya
-- tempat untuk dicatat.
ALTER TABLE cable DROP CONSTRAINT ck_cable_from_kind;
ALTER TABLE cable DROP CONSTRAINT ck_cable_to_kind;
ALTER TABLE cable
    ADD CONSTRAINT ck_cable_from_kind CHECK (from_kind IN ('SITE', 'OLT', 'ODC', 'ODP', 'JOINT_BOX', 'CUSTOMER'));
ALTER TABLE cable
    ADD CONSTRAINT ck_cable_to_kind CHECK (to_kind IN ('SITE', 'OLT', 'ODC', 'ODP', 'JOINT_BOX', 'CUSTOMER'));

ALTER TABLE joint_box ENABLE ROW LEVEL SECURITY;
ALTER TABLE joint_box FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON joint_box
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
