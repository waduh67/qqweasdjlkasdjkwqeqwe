-- ============================================================
-- Sambungan serat — konektivitas pindah dari KABEL ke CORE
--
-- Sampai kini "apa tersambung ke apa" cuma tersirat dari kabel A→B plus nomor
-- port di ujungnya. Itu cukup selama satu kabel = satu hubungan, dan runtuh
-- begitu satu selubung 8 core melewati delapan ODP: kabelnya SATU, tapi ada
-- delapan hubungan berbeda di dalamnya. Tabel ini mencatat hubungan itu apa
-- adanya — sepasang titik yang benar-benar disambung orang di dalam sebuah
-- closure (ODC/ODP, nanti joint box & ODF).
--
-- Bentuknya sepasang SISI karena begitulah wujudnya di lapangan: satu splice
-- tray memegang dua ujung serat, dan keduanya setara — tak ada "induk" dan
-- "anak". Sisi disimpan sebagai baris sendiri (`fiber_connection_end`), bukan
-- sepuluh kolom a_*/b_* di satu baris, karena hanya dengan begitu aturan
-- terpenting di seluruh desain ini bisa dijaga DATABASE, bukan sekadar kode:
--
--     satu titik cuma boleh dipakai satu sambungan.
--
-- Dengan dua sisi di satu baris, "core X sudah dipakai" harus dicari di kolom
-- sisi-A DAN sisi-B; unique index tak bisa menjangkau keduanya sekaligus, dan
-- dua operator yang menyimpan bersamaan bisa lolos berdua — persis bagaimana
-- satu core diam-diam dijual ke dua pelanggan.
--
-- Ruang lingkup potongan ini (lihat docs/topologi-kabel.html §13): closure yang
-- ada baru ODC & ODP. JOINT_BOX dan ODF sudah masuk daftar CHECK sejak sekarang
-- supaya potongan C & D cukup menambah baris tabel simpulnya tanpa migrasi
-- pengubah kendala; sampai itu tiba, aplikasi menolaknya karena memang belum
-- ada simpul yang bisa ditunjuk.
-- ============================================================

CREATE TABLE fiber_connection
(
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    -- Closure = tempat sambungan itu SECARA FISIK berada. Rujukannya polimorfik
    -- (ODC/ODP/JB/ODF) sehingga tak ber-foreign-key; keberadaannya diperiksa
    -- domain, pola sama dengan ujung kabel.
    closure_kind varchar(20) NOT NULL,
    closure_id   uuid        NOT NULL,
    method       varchar(20) NOT NULL,
    -- Rugi sambungan hasil ukur (dB). Null = belum diukur, bukan nol: nol
    -- berarti "sempurna" dan itu kebohongan yang merusak anggaran redaman.
    loss_db      numeric(5, 2),
    note         varchar(200),
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_fiber_connection_closure_kind CHECK (closure_kind IN ('ODC', 'ODP', 'JOINT_BOX', 'ODF')),
    CONSTRAINT ck_fiber_connection_method CHECK (method IN ('FUSION', 'MECHANICAL', 'CONNECTOR')),
    CONSTRAINT ck_fiber_connection_loss CHECK (loss_db IS NULL OR loss_db >= 0),
    -- Sasaran FK gabungan dari sisi: menjamin closure_id di sisi tak pernah
    -- berbeda dari closure induknya (lihat fk_fiber_connection_end_parent).
    CONSTRAINT ux_fiber_connection_id_closure UNIQUE (id, closure_id)
);

CREATE INDEX ix_fiber_connection_closure ON fiber_connection (closure_id);

CREATE TABLE fiber_connection_end
(
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant (id),
    connection_id uuid        NOT NULL,
    -- Disalin dari induknya supaya keunikan "satu titik sekali per closure" bisa
    -- diindeks; FK gabungan di bawah yang menjaga salinannya tak pernah meleset.
    closure_id    uuid        NOT NULL,
    side          varchar(1)  NOT NULL,
    point_kind    varchar(20) NOT NULL,
    -- Titik CORE menunjuk sehelai serat; ikut lenyap bila seratnya lenyap.
    core_id       uuid REFERENCES cable_core (id),
    -- Titik non-core menunjuk simpul (ODC/ODP/ODF/OLT/ONU) + nomor port bila
    -- simpul itu berport. Polimorfik, jadi tanpa foreign key.
    node_id       uuid,
    port_number   integer,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_fiber_connection_end_parent
        FOREIGN KEY (connection_id, closure_id)
            REFERENCES fiber_connection (id, closure_id) ON DELETE CASCADE,
    CONSTRAINT ck_fce_side CHECK (side IN ('A', 'B')),
    CONSTRAINT ck_fce_point_kind
        CHECK (point_kind IN ('CORE', 'ODF_PORT', 'SPLITTER_IN', 'SPLITTER_OUT', 'PON_PORT', 'ONU')),
    -- Bentuk titik: CORE menunjuk serat, sisanya menunjuk simpul. Salah satu,
    -- tak pernah dua-duanya — tanpa ini sebuah sisi bisa menunjuk dua benda
    -- sekaligus dan penelusuran jalur nanti diam-diam bercabang.
    CONSTRAINT ck_fce_shape CHECK (
        (point_kind = 'CORE' AND core_id IS NOT NULL AND node_id IS NULL)
            OR (point_kind <> 'CORE' AND core_id IS NULL AND node_id IS NOT NULL)
        ),
    -- Nomor port hanya untuk titik yang memang berport. Kaki splitter & port ODF
    -- bernomor; input splitter, PON port, dan ONU adalah titik tunggal.
    CONSTRAINT ck_fce_port CHECK (
        CASE
            WHEN point_kind IN ('SPLITTER_OUT', 'ODF_PORT') THEN port_number IS NOT NULL AND port_number >= 1
            ELSE port_number IS NULL
            END
        ),
    CONSTRAINT ux_fiber_connection_end_side UNIQUE (connection_id, side)
);

-- ------------------------------------------------------------
-- "Satu titik dipakai sekali" — dijaga DB, bukan cuma kode.
--
-- CORE: unik per CLOSURE, bukan global. Sehelai core punya DUA ujung dan
-- masing-masing disambung di closure yang berbeda (ujung ODC dan ujung ODP);
-- unik global akan melarang ujung keduanya dan membuat model ini tak berguna.
--
-- Titik non-core: unik GLOBAL. Kaki splitter, port ODF, PON port, dan ONU cuma
-- ada satu-satunya di seluruh jaringan, jadi id simpul + nomor portnya sudah
-- menentukan tempat secara tunggal. NULLS NOT DISTINCT dipakai supaya port yang
-- memang kosong (input splitter, PON, ONU) tetap dibandingkan sebagai nilai,
-- bukan diperlakukan "selalu berbeda" seperti perilaku baku NULL.
-- ------------------------------------------------------------
CREATE UNIQUE INDEX ux_fiber_connection_end_core
    ON fiber_connection_end (closure_id, core_id)
    WHERE core_id IS NOT NULL;

CREATE UNIQUE INDEX ux_fiber_connection_end_node
    ON fiber_connection_end (point_kind, node_id, port_number) NULLS NOT DISTINCT
    WHERE core_id IS NULL;

-- Jalan baca utama penelusuran jalur nanti: "sambungan apa yang menyentuh core
-- ini" (dari serat ke tetangganya) dan "apa saja isi closure ini".
CREATE INDEX ix_fiber_connection_end_core ON fiber_connection_end (core_id) WHERE core_id IS NOT NULL;
CREATE INDEX ix_fiber_connection_end_connection ON fiber_connection_end (connection_id);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE fiber_connection
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE fiber_connection
    FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fiber_connection
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE fiber_connection_end
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE fiber_connection_end
    FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON fiber_connection_end
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
