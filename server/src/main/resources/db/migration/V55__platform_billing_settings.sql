-- ============================================================
-- Platform SaaS billing — setelan GLOBAL (level platform, BUKAN per-tenant).
--
-- Layer ini menagih TENANT (ISP) biaya bulanan memakai aplikasi ini sendiri —
-- berbeda dari module `billing` yang menagih pelanggan-akhir milik tenant.
-- Karena itu tabel di sini PLATFORM-level: tanpa tenant_id di RLS, tanpa blok RLS
-- (cermin tabel `tenant`/`permission` di V1). Scheduler platform membaca semua
-- baris langsung tanpa TenantContext.runAs.
--
-- `platform_setting`  : satu baris global — gateway aktif (switchable) + default
--                       grace/billing-day. Super-admin SaaS bisa ganti gateway aktif
--                       kapan saja (default PAYWUZ).
-- `platform_payment_gateway` : satu baris per penyedia (PAYWUZ/XENDIT/MIDTRANS) berisi
--                       kredensialnya. Kredensial TERENKRIPSI — batas enkripsi di
--                       adapter persistence (sama pola tenant_payment_gateway), DB tak
--                       pernah melihat rahasia asli.
-- ============================================================

CREATE TABLE platform_setting (
    id                       uuid PRIMARY KEY,
    -- Gateway aktif untuk menagih langganan tenant. Bisa diganti super-admin.
    active_payment_provider  varchar(20) NOT NULL DEFAULT 'PAYWUZ',
    -- Masa tenggang (hari) setelah jatuh tempo sebelum tenant di-suspend.
    default_grace_days       int         NOT NULL DEFAULT 7,
    -- Jarak hari dari terbit tagihan ke jatuh tempo.
    default_due_days         int         NOT NULL DEFAULT 7,
    -- Tanggal minimal dalam bulan saat scheduler boleh menerbitkan tagihan.
    default_billing_day      int         NOT NULL DEFAULT 1,
    currency                 varchar(3)  NOT NULL DEFAULT 'IDR',
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_platform_setting_provider
        CHECK (active_payment_provider IN ('PAYWUZ', 'XENDIT', 'MIDTRANS'))
);

CREATE TABLE platform_payment_gateway (
    id            uuid PRIMARY KEY,
    provider      varchar(20) NOT NULL,
    enabled       boolean     NOT NULL DEFAULT false,
    -- Ciphertext. PAYWUZ: api_key (Bearer + secret HMAC webhook, satu kunci).
    -- XENDIT: secret_key (basic-auth). MIDTRANS: secret_key = Server Key (basic-auth
    -- + secret signature SHA512). webhook_token = token verifikasi callback bila ada.
    api_key       varchar(1024),
    secret_key    varchar(1024),
    webhook_token varchar(1024),
    -- PAYWUZ: kode metode bayar (mis. QRIS/VA), non-rahasia (plaintext). null → default global.
    payment_method varchar(64),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_platform_payment_gateway_provider UNIQUE (provider),
    CONSTRAINT ck_platform_payment_gateway_provider
        CHECK (provider IN ('PAYWUZ', 'XENDIT', 'MIDTRANS'))
);
