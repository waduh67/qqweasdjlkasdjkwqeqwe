-- ============================================================
-- Setelan pajak & kontribusi regulatoris per-tenant
--
-- Satu baris per tenant menampung dua kebijakan pajak:
--   PPN        (ppn_enabled/ppn_rate)         — komponen yang DITAGIHKAN ke pelanggan.
--   BHP/USO    (regulatory_enabled/bhp_rate/uso_rate) — kewajiban LAPORAN tenant, tak ditagih;
--              dihitung dari peredaran bruto (pendapatan tertagih sebelum PPN) untuk KPI/laporan.
--
-- Default aman meniru notification_settings: kedua fitur MATI, tapi tarif sudah terisi angka
-- lazim Indonesia (PPN 11%, BHP 0.5%, USO 1.25%). Semua tarif PECAHAN di [0,1), bukan persen.
-- ============================================================

CREATE TABLE billing_tax_settings (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid          NOT NULL REFERENCES tenant (id),
    -- PPN: komponen tagihan pelanggan
    ppn_enabled        boolean       NOT NULL DEFAULT false,
    ppn_rate           numeric(6, 4) NOT NULL DEFAULT 0.1100,
    -- BHP/USO: kewajiban pelaporan tenant (bukan tagihan pelanggan)
    regulatory_enabled boolean       NOT NULL DEFAULT false,
    bhp_rate           numeric(6, 4) NOT NULL DEFAULT 0.0050,
    uso_rate           numeric(6, 4) NOT NULL DEFAULT 0.0125,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    updated_at         timestamptz   NOT NULL DEFAULT now(),
    -- Satu baris setelan per tenant.
    CONSTRAINT uq_billing_tax_settings_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_billing_tax_ppn_rate CHECK (ppn_rate >= 0 AND ppn_rate < 1),
    CONSTRAINT ck_billing_tax_bhp_rate CHECK (bhp_rate >= 0 AND bhp_rate < 1),
    CONSTRAINT ck_billing_tax_uso_rate CHECK (uso_rate >= 0 AND uso_rate < 1)
);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE billing_tax_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing_tax_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON billing_tax_settings
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
