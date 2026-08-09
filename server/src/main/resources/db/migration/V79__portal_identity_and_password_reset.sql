-- ============================================================
-- Portal pelanggan: masuk TANPA "kode ISP", dan bisa memulihkan password sendiri.
--
-- Dua tabel, keduanya dipakai PRA-AUTENTIKASI sehingga SENGAJA TANPA RLS — pola sama
-- `user_directory` (V44) dan `portal_refresh_token` (V64). Saat pelanggan mengetik
-- identitasnya, server belum tahu tenant mana; justru itulah yang sedang dicari, jadi
-- GUC `app.tenant_id` belum bisa di-set dan RLS akan menyaring habis semua baris.
--
--   portal_identity        indeks identitas → (tenant, pelanggan). HANYA penunjuk; tak ada
--                          password/hash, jadi aman dibaca lintas-tenant sebelum login.
--   portal_password_reset  kode sekali-pakai (OTP) pemulihan password, disimpan sebagai
--                          hash — kode terbacanya hanya pernah ada di pesan ke pelanggan.
-- ============================================================

-- ------------------------------------------------------------
-- Indeks identitas portal
--
-- Berbeda tajam dari `user_directory` operator yang menegakkan 1 email = 1 tenant secara
-- GLOBAL: di sini keunikan SENGAJA hanya per-tenant. Seorang warga boleh berlangganan di
-- dua ISP dengan email/HP yang sama, dan memaksakan keunikan global berarti menolak
-- pelanggan sah milik ISP kedua. Konsekuensinya satu identitas bisa menunjuk ke beberapa
-- tenant — diselesaikan saat login dengan memverifikasi password LEBIH DULU, baru
-- menawarkan pilihan ISP (lihat PortalAuthenticationService).
-- ------------------------------------------------------------
CREATE TABLE portal_identity (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    customer_id uuid         NOT NULL,
    -- Asal baris: LOGIN (username portal) | EMAIL | PHONE. Disimpan supaya sinkronisasi
    -- ulang bisa mengganti tepat jenisnya, dan supaya pertanyaan lapangan "kenapa nomor
    -- saya tak bisa dipakai masuk" bisa dijawab dengan melihat data, bukan menebak.
    kind        varchar(16)  NOT NULL,
    -- Nilai TERNORMALKAN — inilah yang dicocokkan saat masuk. Email/username lower-case,
    -- nomor HP dijadikan bentuk kanonik 62… (lihat PortalIdentifier di sisi aplikasi).
    value       varchar(255) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_portal_identity_kind CHECK (kind IN ('LOGIN', 'EMAIL', 'PHONE')),
    -- Satu nilai menunjuk paling banyak satu pelanggan DI DALAM satu tenant. Bila dua
    -- pelanggan satu ISP berbagi nomor (satu keluarga, satu HP), yang kedua tak mendapat
    -- baris ini — dan tetap bisa masuk lewat username-nya sendiri.
    CONSTRAINT uq_portal_identity_value UNIQUE (tenant_id, value)
);
-- Jalur panas: cocokkan apa yang diketik pelanggan, lintas semua tenant.
CREATE INDEX ix_portal_identity_value ON portal_identity (value);
-- Sinkronisasi menghapus-lalu-tulis seluruh baris milik satu pelanggan.
CREATE INDEX ix_portal_identity_customer ON portal_identity (customer_id);

-- ------------------------------------------------------------
-- Kode pemulihan password (OTP)
--
-- Satu baris = satu permintaan "lupa password". Kode 6 digit dikirim lewat email atau
-- WhatsApp, lalu ditukar dengan password baru. Baris tak dihapus setelah dipakai
-- ([consumed_at] diisi) supaya jejak percobaan pemulihan tetap terbaca saat audit.
-- ------------------------------------------------------------
CREATE TABLE portal_password_reset (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    customer_id uuid         NOT NULL,
    -- Identitas yang DIKETIK pemohon (ternormalkan). Verifikasi menuntut kode DAN identitas
    -- yang sama, sehingga kode yang bocor tak bisa dipakai atas nama identitas lain.
    identifier  varchar(255) NOT NULL,
    -- SHA-256 dari kode; DB tak pernah melihat kode aslinya (pola sama token refresh).
    code_hash   varchar(64)  NOT NULL,
    -- EMAIL | WHATSAPP — ke mana kode dikirim; untuk audit & kalimat bantuan di UI.
    channel     varchar(16)  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    -- Percobaan salah. Melewati batas ⇒ kode mati, supaya 6 digit tak bisa ditebak paksa.
    attempts    smallint     NOT NULL DEFAULT 0,
    consumed_at timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_portal_password_reset_channel CHECK (channel IN ('EMAIL', 'WHATSAPP'))
);
-- Penukaran kode mencari lewat hash-nya (tanpa tahu tenant).
CREATE INDEX ix_portal_password_reset_code ON portal_password_reset (code_hash);
-- Permintaan baru mencabut kode lama milik pelanggan yang sama.
CREATE INDEX ix_portal_password_reset_customer ON portal_password_reset (customer_id);

-- ============================================================
-- Backfill: pelanggan yang SUDAH punya login portal harus tetap bisa masuk setelah
-- layar "kode ISP" dihapus. Tanpa ini mereka mendadak tak dikenali.
--
-- Flyway berjalan sebagai role NOBYPASSRLS dan GUC app.tenant_id tak di-set, jadi tabel
-- ber-RLS memfilter SEMUA baris. Nonaktifkan sementara untuk membaca lintas-tenant;
-- FORCE bertahan melewati ENABLE (relforcerowsecurity terpisah dari relrowsecurity).
-- Pola sama V29/V39/V44.
-- ============================================================
ALTER TABLE portal_credential DISABLE ROW LEVEL SECURITY;
ALTER TABLE customer DISABLE ROW LEVEL SECURITY;

-- LOGIN lebih dulu: bila email/HP seseorang kebetulan sama dengan username orang lain
-- di tenant yang sama, username-lah yang menang (itu identitas yang sengaja diberikan
-- operator, sedangkan kontak bisa saja salah ketik).
INSERT INTO portal_identity (id, tenant_id, customer_id, kind, value)
SELECT gen_random_uuid(), pc.tenant_id, pc.customer_id, 'LOGIN', lower(pc.login)
FROM portal_credential pc
ON CONFLICT (tenant_id, value) DO NOTHING;

INSERT INTO portal_identity (id, tenant_id, customer_id, kind, value)
SELECT gen_random_uuid(), pc.tenant_id, pc.customer_id, 'EMAIL', lower(trim(c.email))
FROM portal_credential pc
         JOIN customer c ON c.id = pc.customer_id
WHERE c.email IS NOT NULL
  AND position('@' IN c.email) > 1
ON CONFLICT (tenant_id, value) DO NOTHING;

-- Normalisasi nomor HARUS mencerminkan PortalIdentifier.normalizePhone di sisi aplikasi:
-- buang semua non-digit, lalu awalan '0' lokal ditukar kode negara '62'. Nomor terlalu
-- pendek dibuang — itu isian sampah, bukan nomor yang bisa dihubungi.
INSERT INTO portal_identity (id, tenant_id, customer_id, kind, value)
SELECT gen_random_uuid(), pc.tenant_id, pc.customer_id, 'PHONE', p.normalized
FROM portal_credential pc
         JOIN customer c ON c.id = pc.customer_id
         CROSS JOIN LATERAL (
    SELECT CASE
               WHEN digits LIKE '0%' THEN '62' || substring(digits FROM 2)
               ELSE digits
               END AS normalized
    FROM (SELECT regexp_replace(coalesce(c.phone, ''), '[^0-9]', '', 'g') AS digits) d
    ) p
WHERE length(p.normalized) BETWEEN 8 AND 20
ON CONFLICT (tenant_id, value) DO NOTHING;

ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE portal_credential ENABLE ROW LEVEL SECURITY;

-- ============================================================
-- Pemicu baru: PORTAL_PASSWORD_RESET
--
-- Kode pemulihan lewat WhatsApp TIDAK melewati NotificationSender (isi pesan akan terekam
-- di riwayat broadcast, dan kode rahasia tak boleh terbaca operator). Pemicu ini ada
-- semata agar ISP bisa MEMETAKAN template WhatsApp yang sudah disetujui Meta/Qontak untuk
-- pesan OTP — penyedia seperti Qontak sama sekali tak menerima teks bebas, jadi tanpa
-- pemetaan ini pemulihan lewat WA mustahil bagi mereka.
--
-- Kedua daftar di bawah harus tetap seragam (V48 & V76 sengaja menyalin daftar yang sama).
-- ============================================================
ALTER TABLE notification_broadcast
    DROP CONSTRAINT ck_notification_broadcast_trigger;
ALTER TABLE notification_broadcast
    ADD CONSTRAINT ck_notification_broadcast_trigger CHECK (trigger IN (
        'MANUAL',
        'SUBSCRIPTION_ACTIVATED', 'SUBSCRIPTION_ISOLATED', 'SUBSCRIPTION_TERMINATED',
        'INVOICE_DUE_SOON', 'INVOICE_OVERDUE',
        'WORK_ORDER_SCHEDULED',
        'INCIDENT_OPENED',
        'PORTAL_PASSWORD_RESET'
    ));

ALTER TABLE notification_trigger_template
    DROP CONSTRAINT ck_notification_trigger_template_trigger;
ALTER TABLE notification_trigger_template
    ADD CONSTRAINT ck_notification_trigger_template_trigger CHECK (trigger IN (
        'MANUAL',
        'SUBSCRIPTION_ACTIVATED', 'SUBSCRIPTION_ISOLATED', 'SUBSCRIPTION_TERMINATED',
        'INVOICE_DUE_SOON', 'INVOICE_OVERDUE',
        'WORK_ORDER_SCHEDULED',
        'INCIDENT_OPENED',
        'PORTAL_PASSWORD_RESET'
    ));
