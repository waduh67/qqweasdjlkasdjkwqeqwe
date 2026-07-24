-- ============================================================
-- Phase 3, slice 3: notification — broadcast pemberitahuan gangguan
--
-- Operator memicu penyiaran pesan ke seluruh pelanggan terdampak sebuah insiden
-- ("layanan Anda terganggu, tim kami menanganinya") sebelum mereka komplain.
-- "Siapa yang terdampak" dihitung ulang oleh module incident dari akar masalah;
-- di sini yang disimpan adalah CATATAN penyiaran: apa yang dikirim, ke siapa, dan
-- hasilnya per penerima. Bersifat append-only (snapshot titik waktu).
-- ============================================================

CREATE TABLE notification_broadcast (
    id              uuid PRIMARY KEY,
    tenant_id       uuid          NOT NULL REFERENCES tenant (id),
    -- Insiden pemicu. Nullable menyiapkan siaran ad-hoc (non-insiden) kelak.
    incident_id     uuid,
    channel         varchar(20)   NOT NULL,
    message         varchar(2000) NOT NULL,
    created_by      uuid          NOT NULL,
    -- Jumlah ter-denormalisasi agar daftar riwayat tak perlu memuat baris penerima.
    -- Broadcast tak pernah berubah setelah tersiar, jadi angka ini tetap benar.
    recipient_count integer       NOT NULL DEFAULT 0,
    sent_count      integer       NOT NULL DEFAULT 0,
    skipped_count   integer       NOT NULL DEFAULT 0,
    failed_count    integer       NOT NULL DEFAULT 0,
    sent_at         timestamptz   NOT NULL DEFAULT now(),
    created_at      timestamptz   NOT NULL DEFAULT now(),
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_broadcast_channel CHECK (channel IN ('WHATSAPP', 'SMS', 'TELEGRAM'))
);
CREATE INDEX ix_broadcast_tenant_sent ON notification_broadcast (tenant_id, sent_at DESC);
CREATE INDEX ix_broadcast_incident ON notification_broadcast (incident_id);

-- Satu baris per pelanggan yang disasar, dengan hasil pengirimannya.
CREATE TABLE notification_broadcast_recipient (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    broadcast_id  uuid         NOT NULL REFERENCES notification_broadcast (id) ON DELETE CASCADE,
    -- Pelanggan disalin (nama/telepon) saat kirim agar riwayat tetap terbaca meski
    -- datanya kemudian berubah; customer_id boleh null untuk penerima non-pelanggan.
    customer_id   uuid,
    customer_name varchar(150) NOT NULL,
    phone         varchar(30),
    status        varchar(20)  NOT NULL,
    detail        varchar(300),
    at            timestamptz  NOT NULL DEFAULT now(),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_broadcast_recipient_status CHECK (status IN ('SENT', 'SKIPPED', 'FAILED'))
);
CREATE INDEX ix_broadcast_recipient_broadcast ON notification_broadcast_recipient (broadcast_id, at);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['notification_broadcast', 'notification_broadcast_recipient']
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
