ALTER TABLE tenant_payment_gateway
    ADD COLUMN tripay_merchant_code varchar(80),
    ADD COLUMN tripay_api_key varchar(1024),
    ADD COLUMN tripay_private_key varchar(1024),
    ADD COLUMN tripay_sandbox boolean NOT NULL DEFAULT true;

ALTER TABLE tenant_payment_gateway DROP CONSTRAINT IF EXISTS ck_tpg_provider;
ALTER TABLE tenant_payment_gateway
    ADD CONSTRAINT ck_tpg_provider CHECK (provider IN ('PIVOT', 'TRIPAY', 'MANUAL'));
