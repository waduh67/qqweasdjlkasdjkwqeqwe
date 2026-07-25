-- ============================================================
-- Phase 6: modul CPE — kelola & pantau router/ONT pelanggan via GenieACS (TR-069)
--
-- cpe_device adalah PROYEKSI, bukan sumber kebenaran: cermin tipis device di ACS
-- yang disinkronkan berkala supaya UI bisa menampilkan daftar & status tanpa
-- memanggil NBI tiap render. Data yang cepat basi (jaringan WiFi, host tersambung)
-- sengaja TIDAK disimpan — dibaca langsung dari ACS saat panel dibuka.
--
-- Ditautkan ke pelanggan lewat serial ONU: device yang serialnya cocok dengan ONU
-- terdaftar diikat ke pemilik ONU. customer_id & onu_id adalah uuid polos tanpa
-- foreign key — batas antar-module dijaga tanpa FK lintas-module (sama seperti
-- entity_id pada alarm).
-- ============================================================

CREATE TABLE cpe_device (
    id               uuid PRIMARY KEY,
    tenant_id        uuid         NOT NULL REFERENCES tenant (id),
    -- Id internal GenieACS (_id): kunci setiap perintah NBI. Tak berubah seumur device.
    genieacs_id      varchar(128) NOT NULL,
    serial_number    varchar(64)  NOT NULL,
    oui              varchar(32),
    product_class    varchar(128),
    manufacturer     varchar(128),
    model            varchar(128),
    software_version varchar(128),
    ip_address       varchar(64),
    last_inform_at   timestamptz,
    -- Tautan ke pelanggan & ONU (module customer), uuid polos tanpa FK.
    customer_id      uuid,
    onu_id           uuid,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    -- Satu proyeksi per device ACS per tenant — dedup ditegakkan di DB.
    CONSTRAINT uq_cpe_device_genieacs UNIQUE (tenant_id, genieacs_id)
);
-- Daftar CPE per pelanggan (panel di halaman pelanggan).
CREATE INDEX ix_cpe_device_customer ON cpe_device (tenant_id, customer_id);

-- Jejak audit perintah ke CPE. Append-only: satu baris per reboot / ubah WiFi,
-- berhasil maupun gagal. requested_by_email didenormalisasi (seperti audit_log)
-- agar "siapa" tetap terbaca tanpa lookup ke module iam, bahkan bila user dihapus.
CREATE TABLE cpe_action_log (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid        NOT NULL REFERENCES tenant (id),
    device_id          uuid        NOT NULL,
    action             varchar(20) NOT NULL,
    status             varchar(20) NOT NULL,
    detail             varchar(500),
    requested_by       uuid        NOT NULL,
    requested_by_email varchar(320),
    requested_at       timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_cpe_action_type CHECK (action IN ('REBOOT', 'SET_WIFI')),
    CONSTRAINT ck_cpe_action_status CHECK (status IN ('SUCCESS', 'FAILED'))
);
-- Riwayat aksi satu device, terbaru dulu.
CREATE INDEX ix_cpe_action_device ON cpe_action_log (tenant_id, device_id, requested_at DESC);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE cpe_device ENABLE ROW LEVEL SECURITY;
ALTER TABLE cpe_device FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cpe_device
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE cpe_action_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE cpe_action_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cpe_action_log
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
