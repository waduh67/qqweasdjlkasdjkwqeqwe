-- ============================================================
-- Sistem paket terpadu (S2): bng menaut ke catalog `plan` + jalur-tulis RADIUS
--
-- Tiga perubahan besar:
--  1. rate_profile (paket teknis lama modul bng, tanpa harga & dead-end ke RADIUS)
--     DIHAPUS — perannya pindah ke `plan` (catalog, sumber tunggal harga+speed+QoS).
--     subscriber_access.rate_profile_id → plan_id (uuid polos, TANPA FK lintas-module;
--     bng membaca sisi jaringan plan live untuk menulis grup RADIUS).
--  2. bng_action kini juga membawa perintah otorisasi RADIUS: PROVISION (tulis
--     kredensial+grup akun), DEPROVISION (hapus akun), SYNC_GROUP (setel atribut grup
--     paket). Kolom payload baru menampung parameter grup; password SENGAJA tak
--     disimpan di sini — diresolusi+dekripsi dari subscriber_access saat dispatch.
--  3. subscriber_access_id dilonggarkan jadi NULLABLE: perintah tingkat-grup
--     (SYNC_GROUP) & penghapusan (DEPROVISION) sengaja LEPAS dari akun agar tak ikut
--     ter-CASCADE saat akun dihapus — penghapusan RADIUS tetap terkirim.
-- ============================================================

-- ------------------------------------------------------------
-- 1. bng_action: tipe aksi baru + kolom payload grup + akun opsional
-- ------------------------------------------------------------
ALTER TABLE bng_action
    DROP CONSTRAINT ck_bng_action_action;
ALTER TABLE bng_action
    ADD CONSTRAINT ck_bng_action_action
        CHECK (action IN ('DISCONNECT', 'COA', 'PROVISION', 'DEPROVISION', 'SYNC_GROUP'));

-- Perintah tingkat-grup / penghapusan lepas dari akun (lihat header). FK & ON DELETE
-- CASCADE tetap: perintah per-akun (PROVISION/DISCONNECT/COA) masih ikut terhapus.
ALTER TABLE bng_action
    ALTER COLUMN subscriber_access_id DROP NOT NULL;

ALTER TABLE bng_action
    ADD COLUMN groupname        varchar(128),  -- grup paket: PROVISION (diikuti) & SYNC_GROUP (disetel)
    ADD COLUMN rate_limit       varchar(200),  -- atribut Mikrotik-Rate-Limit grup: SYNC_GROUP
    ADD COLUMN simultaneous_use integer,       -- batas sesi simultan grup: SYNC_GROUP; NULL = tanpa batas
    ADD COLUMN fup_group        varchar(128),  -- grup throttle FUP: SYNC_GROUP bila FUP aktif
    ADD COLUMN fup_rate_limit   varchar(200);  -- rate-limit grup FUP: SYNC_GROUP bila FUP aktif

-- ------------------------------------------------------------
-- 2. subscriber_access: rate_profile_id → plan_id (rujuk catalog, tanpa FK)
-- ------------------------------------------------------------
DROP INDEX ix_subscriber_access_rate_profile;

ALTER TABLE subscriber_access
    RENAME COLUMN rate_profile_id TO plan_id;

-- Paket per akun untuk re-sync grup RADIUS saat plan berubah (memimpin tenant_id RLS).
CREATE INDEX ix_subscriber_access_plan ON subscriber_access (tenant_id, plan_id);

-- ------------------------------------------------------------
-- 3. Buang paket teknis lama. CASCADE menuntaskan FK subscriber_access→rate_profile
--    (kini bernama plan_id) yang tersisa dari inline REFERENCES di V21.
-- ------------------------------------------------------------
DROP TABLE rate_profile CASCADE;
