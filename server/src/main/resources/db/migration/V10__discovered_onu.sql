-- ============================================================
-- Phase 5: kotak masuk auto-provisioning ONU
--
-- ONU yang dilaporkan OLT tapi belum terdaftar (perangkat liar) ditangkap ke
-- sini alih-alih dibuang ke log. Satu baris per serial per tenant: siklus polling
-- berikutnya yang melihatnya lagi MEMPERBARUI baris ini (last_seen_at, seen_count),
-- bukan menumpuk duplikat. Dari sini operator memilih pelanggan + port ODP untuk
-- memprovisikannya tanpa mengetik ulang serial.
--
-- olt_id sengaja tanpa foreign key: OLT milik module network, dirujuk sebagai
-- uuid polos — batas antar-module dijaga tanpa FK lintas-module (sama seperti
-- entity_id pada alarm).
-- ============================================================

CREATE TABLE discovered_onu (
    id                uuid PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenant (id),
    serial_number     varchar(64) NOT NULL,
    -- Resolusi kode OLT → id inventory; null bila kodenya belum dikenal.
    olt_id            uuid,
    olt_code          varchar(64) NOT NULL,
    pon_port_label    varchar(64),
    last_status       varchar(20) NOT NULL,
    last_rx_power_dbm double precision,
    first_seen_at     timestamptz NOT NULL,
    last_seen_at      timestamptz NOT NULL,
    seen_count        integer     NOT NULL DEFAULT 1,
    state             varchar(20) NOT NULL DEFAULT 'DISCOVERED',
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    -- Satu baris per serial per tenant — dedup ditegakkan di DB, bukan hanya di app.
    CONSTRAINT uq_discovered_onu_serial UNIQUE (tenant_id, serial_number),
    CONSTRAINT ck_discovered_onu_state
        CHECK (state IN ('DISCOVERED', 'PROVISIONED', 'IGNORED'))
);
-- Kotak masuk per tenant menurut tahap, terbaru dulu.
CREATE INDEX ix_discovered_onu_state ON discovered_onu (tenant_id, state, last_seen_at DESC);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE discovered_onu ENABLE ROW LEVEL SECURITY;
ALTER TABLE discovered_onu FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON discovered_onu
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
