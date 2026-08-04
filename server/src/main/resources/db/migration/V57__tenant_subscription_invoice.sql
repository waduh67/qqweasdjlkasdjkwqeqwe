-- ============================================================
-- Tagihan & pembayaran langganan tenant (SaaS) — PLATFORM-level (tanpa RLS).
--
-- `tenant_subscription_invoice` : tagihan satu periode langganan sebuah tenant.
--   Nomor unik `SUB-<yyyymm>-<tenant8>`. Referensi gateway (provider/ref/pay_url)
--   dilekatkan setelah charge dibuat, tak mengubah nilai.
-- `tenant_subscription_payment` : catatan pembayaran (append-only) atas tagihan.
-- ============================================================

CREATE TABLE tenant_subscription_invoice (
    id               uuid PRIMARY KEY,
    tenant_id        uuid           NOT NULL REFERENCES tenant (id),
    subscription_id  uuid           NOT NULL REFERENCES tenant_subscription (id),
    number           varchar(40)    NOT NULL,
    period_start     date           NOT NULL,
    period_end       date           NOT NULL,
    amount           numeric(14, 2) NOT NULL,
    status           varchar(20)    NOT NULL DEFAULT 'ISSUED',
    issued_at        timestamptz    NOT NULL DEFAULT now(),
    due_date         date           NOT NULL,
    paid_at          timestamptz,
    gateway_provider varchar(20),
    gateway_ref      varchar(255),
    pay_url          varchar(1024),
    created_at       timestamptz    NOT NULL DEFAULT now(),
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_subscription_invoice_number UNIQUE (number),
    CONSTRAINT ck_tenant_subscription_invoice_status
        CHECK (status IN ('ISSUED', 'PAID', 'OVERDUE', 'VOID'))
);

CREATE INDEX ix_tenant_subscription_invoice_sub
    ON tenant_subscription_invoice (subscription_id);
CREATE INDEX ix_tenant_subscription_invoice_unpaid
    ON tenant_subscription_invoice (due_date)
    WHERE status IN ('ISSUED', 'OVERDUE');

CREATE TABLE tenant_subscription_payment (
    id           uuid PRIMARY KEY,
    tenant_id    uuid           NOT NULL REFERENCES tenant (id),
    invoice_id   uuid           NOT NULL REFERENCES tenant_subscription_invoice (id),
    amount       numeric(14, 2) NOT NULL,
    provider     varchar(20)    NOT NULL,
    gateway_ref  varchar(255),
    paid_at      timestamptz    NOT NULL,
    note         varchar(500),
    created_at   timestamptz    NOT NULL DEFAULT now(),
    updated_at   timestamptz    NOT NULL DEFAULT now()
);

CREATE INDEX ix_tenant_subscription_payment_invoice
    ON tenant_subscription_payment (invoice_id);
