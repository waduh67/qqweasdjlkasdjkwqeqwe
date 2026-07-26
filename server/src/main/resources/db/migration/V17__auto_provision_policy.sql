-- ============================================================
-- Phase 5: kebijakan auto-provisioning zero-touch per tenant
--
-- Bila enabled, ONU liar yang teresolusi ke keyakinan HIGH ditautkan otomatis ke
-- {pelanggan, ODP, port}-nya oleh pemindai terjadwal — tanpa menunggu operator.
-- Default MATI: menautkan ONU memutasi data pelanggan tanpa mata manusia, jadi
-- tenant harus menyalakannya dengan sadar. Satu baris per tenant; tak ada baris
-- berarti mati (nilai bawaan dari kode), jadi tabel hanya berisi yang menyalakannya.
-- ============================================================

CREATE TABLE auto_provision_policy (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    enabled    boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- Satu kebijakan per tenant.
    CONSTRAINT uq_auto_provision_policy_tenant UNIQUE (tenant_id)
);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE auto_provision_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE auto_provision_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON auto_provision_policy
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
