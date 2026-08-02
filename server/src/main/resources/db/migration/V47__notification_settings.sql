-- ============================================================
-- Notifikasi (fase pengiriman): setelan gateway WhatsApp per-tenant + saklar pemicu
--
-- Gateway sengaja BYO per-tenant (bukan milik platform): nomor WA adalah identitas
-- pengirim yang dilihat pelanggan, mengisolasi risiko blokir antar tenant, dan tiap
-- tenant membayar gateway-nya sendiri. Dua tipe yang bisa dipilih:
--   HTTP_GENERIC  gateway HTTP pihak-ketiga (Fonnte/Wablas/dsb), field nomor/pesan disetel.
--   META_CLOUD    WhatsApp Business Cloud API resmi Meta (kirim template ke Graph API).
--   LOG           bawaan dev: pesan hanya dicatat ke log, tak keluar.
--
-- Token gateway (http_token/meta_access_token) disimpan TERENKRIPSI — batas enkripsi
-- ada di adapter persistence, DB tak pernah melihat token asli (sama seperti coa_secret).
--
-- Satu baris setelan per tenant. Default aman: LOG, gateway MATI, semua saklar MATI.
-- ============================================================

CREATE TABLE notification_settings (
    id                            uuid PRIMARY KEY,
    tenant_id                     uuid         NOT NULL REFERENCES tenant (id),
    provider                      varchar(20)  NOT NULL DEFAULT 'LOG',
    -- Saklar induk: mati = tak ada pesan keluar apa pun, apa pun saklar pemicunya.
    gateway_enabled               boolean      NOT NULL DEFAULT false,
    -- Gateway HTTP generik
    http_endpoint_url             varchar(500),
    http_token                    varchar(512),   -- ciphertext token gateway
    http_phone_field              varchar(50)  NOT NULL DEFAULT 'target',
    http_message_field            varchar(50)  NOT NULL DEFAULT 'message',
    -- WhatsApp Business Cloud (Meta)
    meta_phone_number_id          varchar(64),
    meta_access_token             varchar(2048),  -- ciphertext; token Meta bisa panjang
    meta_template_name            varchar(128),
    meta_template_lang            varchar(10)  NOT NULL DEFAULT 'id',
    -- Saklar pemicu otomatis (default semua mati; tenant menyalakan dengan sadar)
    notify_subscription_lifecycle boolean      NOT NULL DEFAULT false,
    notify_invoice_reminder       boolean      NOT NULL DEFAULT false,
    notify_work_order_schedule    boolean      NOT NULL DEFAULT false,
    notify_incident_open          boolean      NOT NULL DEFAULT false,
    created_at                    timestamptz  NOT NULL DEFAULT now(),
    updated_at                    timestamptz  NOT NULL DEFAULT now(),
    -- Satu baris setelan per tenant.
    CONSTRAINT uq_notification_settings_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_notification_settings_provider CHECK (provider IN ('LOG', 'HTTP_GENERIC', 'META_CLOUD'))
);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE notification_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON notification_settings
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
