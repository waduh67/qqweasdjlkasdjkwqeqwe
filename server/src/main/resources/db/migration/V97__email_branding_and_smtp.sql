-- ============================================================
-- Setelan email: sambungan SMTP + identitas pengirim + tampilan (logo/warna/footer)
-- + baris subjek per pemicu — level PLATFORM sebagai bawaan, ditimpa per TENANT.
--
-- Sebelum ini seluruh setelan email terkunci di env (`spring.mail.*` dan `ftth.mail.*`):
-- mengganti server SMTP menuntut akses shell + restart container, subjeknya dipaku di
-- kode (`NotificationSender.subjectFor`) sehingga sama untuk semua ISP, dan surat yang
-- keluar berupa teks polos tanpa logo — pelanggan menerima tagihan yang bentuknya tak
-- beda dari pesan tak dikenal.
--
-- Dua tingkat, satu aturan: apa pun yang TAK diisi tenant mewarisi baris platform, dan
-- apa pun yang tak diisi platform jatuh ke bawaan di kode/env. Karena itu seluruh kolom
-- di tabel tenant nullable — null di sini berarti "ikut bawaan", bukan "kosongkan".
--
-- Pemisahan platform/tenant sengaja jadi TABEL TERPISAH, bukan satu tabel dengan
-- tenant_id nullable: kebijakan RLS menyaring baris ber-tenant_id lain, jadi baris
-- bawaan platform justru akan tak terlihat dari sesi tenant — persis kebalikan dari
-- yang dibutuhkan pewarisan.
-- ============================================================

-- Singleton global (PLATFORM-level, tanpa RLS — pola `platform_setting`/`pivot_master_config`).
CREATE TABLE platform_email_setting (
    id                uuid PRIMARY KEY,
    -- Sambungan SMTP. Host kosong = jatuh ke `spring.mail.*` dari env (deploy lama tetap jalan).
    smtp_host         varchar(255),
    smtp_port         integer      NOT NULL DEFAULT 587,
    smtp_username     varchar(255),
    -- Ciphertext (SecretCipher) — batas enkripsi di adapter persistence, DB tak pernah
    -- melihat password asli. Sama seperti kredensial pivot_master_config.
    smtp_password     varchar(1024),
    smtp_auth         boolean      NOT NULL DEFAULT true,
    smtp_starttls     boolean      NOT NULL DEFAULT true,
    -- Identitas pengirim bawaan; tenant boleh menimpanya.
    from_address      varchar(254),
    from_name         varchar(100) NOT NULL DEFAULT 'NetOps Console',
    -- Tampilan bawaan yang diwarisi tenant yang tak menimpanya.
    logo_storage_key  varchar(300),
    logo_content_type varchar(80),
    accent_color      varchar(9),
    footer_text       varchar(500),
    signature_text    varchar(200),
    -- URL absolut aplikasi untuk merangkai <img src> logo di badan HTML. Klien email tak
    -- mengerti path relatif. Kosong = email tetap terkirim, hanya tanpa logo.
    public_base_url   varchar(300),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_platform_email_port CHECK (smtp_port BETWEEN 1 AND 65535)
);

-- Timpaan subjek level platform. Baris ABSEN = pakai subjek bawaan di kode
-- (EmailSubjectResolver.DEFAULT_SUBJECTS) — baris kosong bukan cara menyatakan "bawaan".
CREATE TABLE platform_email_subject (
    id         uuid PRIMARY KEY,
    trigger    varchar(40)  NOT NULL UNIQUE,
    subject    varchar(200) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now()
);

-- Timpaan per tenant. SEMUA kolom nullable: null = warisi platform.
CREATE TABLE tenant_email_setting (
    id                uuid PRIMARY KEY,
    tenant_id         uuid        NOT NULL UNIQUE REFERENCES tenant (id),
    -- Dipakai apa adanya sebagai From (plus Reply-To). Peringatan SPF/DKIM ada di UI:
    -- relay SMTP platform belum tentu berwenang atas domain tenant.
    from_address      varchar(254),
    from_name         varchar(100),
    logo_storage_key  varchar(300),
    logo_content_type varchar(80),
    accent_color      varchar(9),
    footer_text       varchar(500),
    signature_text    varchar(200),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tenant_email_subject (
    id         uuid PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenant (id),
    trigger    varchar(40)  NOT NULL,
    subject    varchar(200) NOT NULL,
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    -- Satu pemicu = paling banyak satu subjek per tenant.
    CONSTRAINT uq_tenant_email_subject UNIQUE (tenant_id, trigger)
);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain).
-- Hanya tabel tenant; yang platform sengaja tanpa RLS karena tak ber-tenant_id.
-- ------------------------------------------------------------
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['tenant_email_setting', 'tenant_email_subject']
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
