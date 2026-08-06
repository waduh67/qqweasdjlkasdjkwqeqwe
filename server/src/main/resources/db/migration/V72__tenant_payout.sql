-- ============================================================
-- Riwayat penyaluran dana per-tenant (tenant-scoped + RLS). Jejak audit finansial untuk:
--   PAYOUT     : dana NON_KYC di balance MASTER platform → disalurkan platform ke rekening tenant
--                (POST /v1/payouts memakai payout_inquiry_id tenant). Dipicu operator platform.
--   WITHDRAWAL : dana KYC di balance SUB-ACCOUNT tenant → ditarik tenant sendiri on-behalf
--                (POST /v1/withdrawals, header x-submerchant-id).
--
-- Baris ini TIDAK menghitung saldo (Pivot sumber kebenaran balance) — hanya mencatat perintah +
-- hasil rekonsiliasi callback (WITHDRAW.* / payout webhook, diverifikasi X-API-Key master).
-- Nominal minor-unit IDR (zero-decimal), konsisten dengan amount.value charge Pivot.
-- ============================================================

CREATE TABLE tenant_payout (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenant (id),
    kind            varchar(20) NOT NULL,
    amount_minor    bigint      NOT NULL,
    -- Rekening tujuan (snapshot saat perintah dibuat) — non-rahasia.
    channel_code    varchar(40),
    account_number  varchar(60),
    account_name    varchar(160),
    status          varchar(20) NOT NULL DEFAULT 'PENDING',
    -- Referensi transaksi di Pivot (data.id/referenceId) — kunci rekonsiliasi callback.
    pivot_ref       varchar(128),
    failure_reason  varchar(500),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_tenant_payout_kind CHECK (kind IN ('PAYOUT', 'WITHDRAWAL')),
    CONSTRAINT ck_tenant_payout_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_tenant_payout_amount CHECK (amount_minor > 0)
);

-- Rekonsiliasi callback mencari baris via ref Pivot dalam konteks tenant.
CREATE INDEX ix_tenant_payout_ref ON tenant_payout (pivot_ref);
CREATE INDEX ix_tenant_payout_tenant_created ON tenant_payout (tenant_id, created_at DESC);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain — lihat V50).
-- ------------------------------------------------------------
ALTER TABLE tenant_payout ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_payout FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_payout
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
