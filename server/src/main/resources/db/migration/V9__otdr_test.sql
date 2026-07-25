-- ============================================================
-- Phase 5: hasil uji OTDR per kabel — plotting jarak gangguan
--
-- Reflektometer melaporkan jarak (meter serat) dari ujung ukur ke sebuah
-- peristiwa. Yang disimpan hanya angka mentahnya; titik perkiraan di peta
-- dihitung dari geometri kabel saat dibaca, jadi selalu ikut bila jalur kabel
-- kelak disunting. Terikat ke satu kabel — ikut terhapus bila kabelnya dihapus.
-- ============================================================

CREATE TABLE otdr_test (
    id              uuid PRIMARY KEY,
    tenant_id       uuid             NOT NULL REFERENCES tenant (id),
    cable_id        uuid             NOT NULL REFERENCES cable (id) ON DELETE CASCADE,
    -- Jarak ujung-ukur → peristiwa, dalam meter serat (memuat slack, ≥ panjang jalur).
    distance_meters double precision NOT NULL,
    measured_from   varchar(10)      NOT NULL,
    event_type      varchar(20)      NOT NULL,
    loss_db         double precision,
    note            varchar(500),
    recorded_by     uuid             NOT NULL,
    recorded_at     timestamptz      NOT NULL,
    created_at      timestamptz      NOT NULL DEFAULT now(),
    updated_at      timestamptz      NOT NULL DEFAULT now(),
    CONSTRAINT ck_otdr_measured_from CHECK (measured_from IN ('FROM', 'TO')),
    CONSTRAINT ck_otdr_event_type
        CHECK (event_type IN ('BREAK', 'HIGH_LOSS', 'REFLECTION', 'SPLICE', 'END')),
    CONSTRAINT ck_otdr_distance CHECK (distance_meters >= 0)
);
-- Riwayat uji per kabel, terbaru dulu.
CREATE INDEX ix_otdr_test_cable ON otdr_test (cable_id, recorded_at DESC);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE otdr_test ENABLE ROW LEVEL SECURITY;
ALTER TABLE otdr_test FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON otdr_test
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
