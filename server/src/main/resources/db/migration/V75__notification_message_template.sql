-- ============================================================
-- Template pesan WhatsApp (Meta Cloud): katalog per-tenant + pemetaan ke pemicu
--
-- Sebelumnya setelan template menempel di notification_settings sebagai SATU pasang
-- kolom global (meta_template_name/meta_template_lang) yang dipakai untuk semua pesan,
-- apa pun pemicunya. Padahal template *utility* Meta dibuat per-kejadian (tagihan jatuh
-- tempo ≠ menunggak ≠ layanan diisolir), masing-masing punya nama & status persetujuan
-- sendiri. Dua tabel di bawah memisahkannya:
--
--   notification_message_template   katalog template tenant (boleh banyak). Diisi manual
--                                   atau ditarik dari Meta (GET /{waba-id}/message_templates).
--   notification_trigger_template   pemetaan pemicu → template. UNIQUE (tenant_id, trigger)
--                                   menegakkan aturan "satu pemicu hanya boleh satu template"
--                                   di lapisan DB. Sebaliknya satu template BOLEH melayani
--                                   beberapa pemicu — karena itu tabel terpisah, bukan kolom
--                                   trigger di tabel template.
--
-- Pemicu tanpa pemetaan → dispatcher mengirim teks biasa (perilaku lama), bukan gagal.
-- Tak ada rahasia di sini: token tetap tinggal di notification_settings (terenkripsi).
-- ============================================================

-- WABA ID: dibutuhkan untuk menarik daftar template dari Meta. Bukan rahasia (id publik
-- akun bisnis), jadi disimpan apa adanya — beda dari meta_access_token yang terenkripsi.
ALTER TABLE notification_settings
    ADD COLUMN meta_waba_id varchar(64);

CREATE TABLE notification_message_template (
    id               uuid PRIMARY KEY,
    tenant_id        uuid          NOT NULL REFERENCES tenant (id),
    -- Nama template sebagaimana terdaftar di Meta (huruf kecil, angka, garis bawah).
    name             varchar(128)  NOT NULL,
    -- Kode bahasa template Meta: 'id', 'en_US', ...
    language         varchar(10)   NOT NULL,
    category         varchar(20)   NOT NULL DEFAULT 'UTILITY',
    -- Status persetujuan di Meta. 'UNKNOWN' = entri manual yang belum pernah disinkron.
    status           varchar(20)   NOT NULL DEFAULT 'UNKNOWN',
    -- MANUAL = diketik operator; META = hasil tarik dari Graph API.
    source           varchar(10)   NOT NULL DEFAULT 'MANUAL',
    meta_template_id varchar(64),
    -- Teks komponen BODY, untuk ditampilkan operator saat memilih template.
    body_preview     varchar(1024),
    -- Jumlah placeholder {{n}} unik di body. Dispatcher selalu mengirim TEPAT SATU
    -- parameter ({{1}} = seluruh pesan), jadi nilai ≠ 1 diperingatkan di UI.
    body_param_count integer       NOT NULL DEFAULT 1,
    synced_at        timestamptz,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    -- Kunci alami template di Meta; dipakai sync untuk upsert.
    CONSTRAINT uq_notification_template UNIQUE (tenant_id, name, language),
    CONSTRAINT ck_notification_template_category CHECK (category IN ('UTILITY', 'MARKETING', 'AUTHENTICATION')),
    CONSTRAINT ck_notification_template_status CHECK (status IN ('APPROVED', 'PENDING', 'REJECTED', 'PAUSED', 'DISABLED', 'UNKNOWN')),
    CONSTRAINT ck_notification_template_source CHECK (source IN ('MANUAL', 'META'))
);

CREATE TABLE notification_trigger_template (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    trigger     varchar(30) NOT NULL,
    template_id uuid        NOT NULL REFERENCES notification_message_template (id) ON DELETE CASCADE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    -- Satu pemicu = paling banyak satu template.
    CONSTRAINT uq_notification_trigger_template UNIQUE (tenant_id, trigger),
    -- Daftar sama dengan ck_notification_broadcast_trigger (V48). 'MANUAL' diizinkan di DB
    -- untuk broadcast manual kelak, tapi belum ditawarkan di UI.
    CONSTRAINT ck_notification_trigger_template_trigger CHECK (trigger IN (
        'MANUAL',
        'SUBSCRIPTION_ACTIVATED', 'SUBSCRIPTION_ISOLATED', 'SUBSCRIPTION_TERMINATED',
        'INVOICE_DUE_SOON', 'INVOICE_OVERDUE',
        'WORK_ORDER_SCHEDULED',
        'INCIDENT_OPENED'
    ))
);
CREATE INDEX ix_notification_trigger_template_template ON notification_trigger_template (template_id);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['notification_message_template', 'notification_trigger_template']
            LOOP
                EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format($f$
                    CREATE POLICY tenant_isolation ON %I
                        USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                        WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                    $f$, t);
            END LOOP;
    END
$$;

-- ------------------------------------------------------------
-- Backfill: template global lama tetap berlaku untuk KETUJUH pemicu otomatis, agar
-- tenant yang sudah menyetelnya tak mendadak berubah jadi kirim teks biasa.
-- Dijalankan sebagai superuser migrasi (RLS di-bypass oleh FORCE hanya untuk pemilik
-- tabel — Flyway berjalan sebagai pemilik, jadi INSERT lintas-tenant di sini sah).
-- ------------------------------------------------------------
INSERT INTO notification_message_template (id, tenant_id, name, language, category, status, source, body_param_count)
SELECT gen_random_uuid(),
       tenant_id,
       -- Domain menormalkan nama ke huruf kecil (aturan Meta); samakan agar entri lama
       -- yang terlanjur berhuruf besar tetap cocok saat sync mencari (name, language).
       lower(trim(meta_template_name)),
       coalesce(nullif(trim(meta_template_lang), ''), 'id'),
       'UTILITY',
       'UNKNOWN',
       'MANUAL',
       1
FROM notification_settings
WHERE nullif(trim(meta_template_name), '') IS NOT NULL;

-- Aman menyapu seluruh tabel: pada titik ini isinya HANYA baris hasil backfill di atas.
INSERT INTO notification_trigger_template (id, tenant_id, trigger, template_id)
SELECT gen_random_uuid(), t.tenant_id, trg, t.id
FROM notification_message_template t
         CROSS JOIN unnest(ARRAY [
             'SUBSCRIPTION_ACTIVATED', 'SUBSCRIPTION_ISOLATED', 'SUBSCRIPTION_TERMINATED',
             'INVOICE_DUE_SOON', 'INVOICE_OVERDUE',
             'WORK_ORDER_SCHEDULED',
             'INCIDENT_OPENED'
             ]) AS trg;

ALTER TABLE notification_settings
    DROP COLUMN meta_template_name,
    DROP COLUMN meta_template_lang;
