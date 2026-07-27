-- ============================================================
-- Modul billing — mesin penagihan + pembayaran (provider-agnostik)
--
-- Dua tabel:
--   invoice  tagihan satu periode langganan. Tertaut ke langganan/pelanggan
--            (module customer) lewat uuid polos TANPA foreign key lintas-module.
--            Nomor unik per tenant; satu langganan maksimal satu tagihan per periode.
--            Referensi gateway (provider/ref/pay_url) dilekatkan setelah charge dibuat.
--   payment  catatan pembayaran (append-only) atas sebuah tagihan. FK intra-module
--            ke invoice diperbolehkan (menjaga integritas tagihan yang dirujuk).
--
-- Auto-isolir/auto-pulih tidak menulis ke tabel langganan dari sini — module billing
-- memintanya lewat kontrak lintas-module (CustomerApi).
-- ============================================================

CREATE TABLE invoice (
    id               uuid PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    -- Tautan ke pelanggan & langganan (module customer), uuid polos tanpa FK lintas-module.
    customer_id      uuid          NOT NULL,
    subscription_id  uuid          NOT NULL,
    number           varchar(40)   NOT NULL,
    period_start     date          NOT NULL,
    period_end       date          NOT NULL,
    amount           numeric(14,2) NOT NULL,
    status           varchar(20)   NOT NULL,
    issued_at        timestamptz   NOT NULL,
    due_date         date          NOT NULL,
    paid_at          timestamptz,
    gateway_provider varchar(40),
    gateway_ref      varchar(200),
    pay_url          varchar(1000),
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    -- Nomor unik per tenant, dan satu langganan maksimal satu tagihan per periode.
    CONSTRAINT uq_invoice_number UNIQUE (tenant_id, number),
    CONSTRAINT uq_invoice_period UNIQUE (tenant_id, subscription_id, period_start),
    CONSTRAINT ck_invoice_status CHECK (status IN ('ISSUED', 'PAID', 'OVERDUE', 'VOID'))
);
-- Daftar tagihan per pelanggan (panel di halaman pelanggan).
CREATE INDEX ix_invoice_customer ON invoice (tenant_id, customer_id);
-- Penegakan tunggakan: tagihan terbit yang lewat jatuh tempo.
CREATE INDEX ix_invoice_status_due ON invoice (tenant_id, status, due_date);

CREATE TABLE payment (
    id          uuid PRIMARY KEY,
    tenant_id   uuid          NOT NULL REFERENCES tenant (id),
    -- FK intra-module diperbolehkan (menjaga integritas tagihan yang dirujuk).
    invoice_id  uuid          NOT NULL REFERENCES invoice (id),
    customer_id uuid          NOT NULL,
    amount      numeric(14,2) NOT NULL,
    provider    varchar(40)   NOT NULL,
    gateway_ref varchar(200),
    paid_at     timestamptz   NOT NULL,
    note        varchar(500),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now()
);
-- Pembayaran sebuah tagihan (riwayat pelunasan).
CREATE INDEX ix_payment_invoice ON payment (tenant_id, invoice_id);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
