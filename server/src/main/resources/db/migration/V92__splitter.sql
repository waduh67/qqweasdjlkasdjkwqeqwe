-- ============================================================
-- Splitter jadi benda, bukan lagi satu kolom di kabinetnya.
--
-- Sampai kini `odc.splitter_ratio` menyimpan SATU rasio per kabinet. Di lapangan
-- itu tidak benar: satu ODC berisi BEBERAPA modul splitter, masing-masing
-- disuapi PON port yang berbeda, dan rasionya boleh beda-beda —
--
--     ODC-A ┬ SPL-1 · 1:8  ← PON 1/1/1
--           ├ SPL-2 · 1:8  ← PON 1/1/2
--           └ SPL-3 · 1:16 ← PON 1/1/3
--
-- Ada pula ODC yang justru TANPA splitter sama sekali: murni cross-connect,
-- splitternya menyusul di ODP. Satu kolom rasio tak bisa mengaku "nol" maupun
-- "tiga", dan karena itu ia dipecah jadi tabelnya sendiri.
--
-- Yang ikut terbuka setelah ini: input tiap splitter punya identitas, jadi
-- "PON 1/1/1 sudah dipakai berapa pelanggan" bisa dihitung — batas 64 ONU per
-- PON port itu nyata. Dan splitter bertingkat (ODC 1:4 lalu ODP 1:16) berhenti
-- jadi tebakan: tingkatnya kelihatan dari rantai sambungannya.
--
-- Lihat docs/topologi-kabel.html §6 & §13-E.
-- ============================================================

CREATE TABLE splitter
(
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    -- Pemiliknya ODC atau ODP. Sengaja TANPA foreign key: kolomnya polimorfik
    -- (dua tabel induk), dan memaksakan FK berarti dua kolom nullable yang
    -- justru membuka bentuk mustahil "milik ODC sekaligus ODP". Yang menjaga
    -- keutuhan adalah service saat kabinetnya dihapus.
    owner_kind varchar(10) NOT NULL,
    owner_id   uuid        NOT NULL,
    -- Label di badan modulnya, mis. "SPL-1". Unik DI DALAM kabinetnya saja —
    -- teknisi memang menyebutnya "SPL-2 di ODC-A", bukan nama global.
    code       varchar(40) NOT NULL,
    ratio      varchar(10) NOT NULL,
    note       varchar(200),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_splitter_owner_kind CHECK (owner_kind IN ('ODC', 'ODP')),
    CONSTRAINT ck_splitter_ratio CHECK (ratio IN ('1:2', '1:4', '1:8', '1:16', '1:32', '1:64'))
);
CREATE UNIQUE INDEX ux_splitter_code ON splitter (owner_id, code);
CREATE INDEX ix_splitter_owner ON splitter (owner_id);

-- ------------------------------------------------------------
-- Backfill 1:1 — tiap ODC/ODP yang sudah ada langsung punya satu splitter
-- dengan rasio lamanya. Harus persis satu, bukan "kira-kira": angka yang sudah
-- terlanjur dipakai (anggaran redaman, kaki splitter di sambungan) tidak boleh
-- bergeser sedetik pun oleh migrasi ini.
--
-- RLS sumbernya dimatikan sebentar dengan alasan yang sama seperti V87: Flyway
-- jalan sebagai role NOBYPASSRLS tanpa GUC app.tenant_id, jadi SELECT-nya akan
-- melihat NOL baris dan diam-diam mengisi nol splitter. FORCE bertahan melewati
-- ENABLE (relforcerowsecurity terpisah dari relrowsecurity).
-- ------------------------------------------------------------
ALTER TABLE odc DISABLE ROW LEVEL SECURITY;
ALTER TABLE odp DISABLE ROW LEVEL SECURITY;

INSERT INTO splitter (id, tenant_id, owner_kind, owner_id, code, ratio)
SELECT gen_random_uuid(), o.tenant_id, 'ODC', o.id, 'SPL-1', o.splitter_ratio
FROM odc o;

INSERT INTO splitter (id, tenant_id, owner_kind, owner_id, code, ratio)
SELECT gen_random_uuid(), o.tenant_id, 'ODP', o.id, 'SPL-1', o.splitter_ratio
FROM odp o;

-- ------------------------------------------------------------
-- Sambungan kaki splitter kini menunjuk SPLITTER-nya, bukan kabinetnya.
--
-- Sebelum ini `node_id` sebuah titik SPLITTER_IN/SPLITTER_OUT diisi id ODC/ODP,
-- sebab splitter belum punya identitas — sebuah janji sementara yang ditulis
-- terang-terangan di FiberConnectionService. Sekarang janji itu ditebus.
-- Pemetaannya tak ambigu: backfill di atas membuat tepat satu splitter per
-- kabinet.
--
-- Catatan jujur: dulu batas nomor kaki diambil dari `capacity` kabinet, jadi
-- data lama bisa berisi "kaki 20" pada kabinet berasio 1:8. Baris seperti itu
-- DIBIARKAN apa adanya — memindahkannya berarti mengarang di mana serat itu
-- sebenarnya menempel. Yang berubah: sambungan BARU divalidasi terhadap rasio
-- splitternya, dan ganti rasio ditolak selama kaki di luar rasio baru masih
-- terpakai.
-- ------------------------------------------------------------
ALTER TABLE fiber_connection_end DISABLE ROW LEVEL SECURITY;

UPDATE fiber_connection_end e
SET node_id = s.id
FROM splitter s
WHERE e.point_kind IN ('SPLITTER_IN', 'SPLITTER_OUT')
  AND s.owner_id = e.node_id;

ALTER TABLE fiber_connection_end ENABLE ROW LEVEL SECURITY;

-- Kolom lama dicabut, bukan dibiarkan jadi sumber kebenaran kedua yang
-- diam-diam menyimpang dari tabel splitter.
ALTER TABLE odc DROP COLUMN splitter_ratio;
ALTER TABLE odp DROP COLUMN splitter_ratio;

ALTER TABLE odc ENABLE ROW LEVEL SECURITY;
ALTER TABLE odp ENABLE ROW LEVEL SECURITY;

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE splitter
    ENABLE ROW LEVEL SECURITY;
ALTER TABLE splitter
    FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON splitter
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
