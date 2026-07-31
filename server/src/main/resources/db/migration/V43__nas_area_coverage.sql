-- ============================================================
-- Cakupan area per-BRAS (auto-pilih BRAS dari area pelanggan)
--
-- Sebuah BRAS menaungi sekumpulan area; PSB Ekspres memakai peta ini untuk otomatis
-- memilih BRAS dari area pelanggan (operator tetap boleh menimpanya). area_id adalah
-- UUID polos — TANPA FK lintas-module ke `area` milik iam, sama seperti
-- subscriber_access.plan_id merujuk catalog tanpa FK (batas module dijaga di kode).
--
-- UNIQUE (tenant_id, area_id) menjaga tiap area dinaungi PALING BANYAK satu BRAS →
-- resolusi area→BRAS deterministik. FK nas_id ON DELETE CASCADE: hapus BRAS ikut
-- melepas cakupannya. RLS dua-lapis sama seperti tabel bng lain.
-- ============================================================

CREATE TABLE nas_area (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    nas_id     uuid        NOT NULL REFERENCES nas (id) ON DELETE CASCADE,
    area_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_nas_area_area UNIQUE (tenant_id, area_id)
);

-- Muat cakupan sebuah BRAS (tenant_id memimpin agar selaras RLS).
CREATE INDEX ix_nas_area_nas ON nas_area (tenant_id, nas_id);

ALTER TABLE nas_area ENABLE ROW LEVEL SECURITY;
ALTER TABLE nas_area FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON nas_area
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
