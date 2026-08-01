-- ============================================================
-- Login tanpa slug tenant: 1 email = 1 tenant secara GLOBAL.
--
-- `user_directory` = indeks pre-auth email→tenant. Pola SAMA dengan `refresh_token`:
-- punya tenant_id TAPI TANPA RLS, karena lookup terjadi SEBELUM tenant context ada
-- (saat login server belum tahu tenant mana). Menyimpan HANYA (user_id, tenant_id,
-- email_lower) — tak ada password/hash, jadi aman dibaca lintas-tenant pra-auth.
--
-- PK = id app_user (1:1). UNIQUE(email_lower) menegakkan keunikan email GLOBAL
-- (menggantikan uq_app_user_tenant_email yang cuma unik per-tenant). FK ON DELETE
-- CASCADE ikut menghapus baris direktori saat user dihapus.
-- ============================================================
CREATE TABLE user_directory (
    id          uuid PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    email_lower varchar(255) NOT NULL UNIQUE,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_user_directory_tenant ON user_directory (tenant_id);

-- Backfill dari app_user. Flyway jalan sebagai role NOBYPASSRLS → app_user yang ter-RLS
-- memfilter SEMUA baris (GUC app.tenant_id tak di-set saat migrasi). Nonaktifkan RLS
-- sementara untuk membaca lintas-tenant; FORCE tetap bertahan melewati ENABLE
-- (relforcerowsecurity terpisah dari relrowsecurity). Pola sama V29/V39.
--
-- CATATAN: gagal bila ADA email kembar lintas-tenant (indeks lama cuma unik per-tenant).
-- Itu justru invarian baru yang ditegakkan — bersihkan duplikat email sebelum migrasi.
ALTER TABLE app_user DISABLE ROW LEVEL SECURITY;
INSERT INTO user_directory (id, tenant_id, email_lower)
SELECT id, tenant_id, lower(email) FROM app_user;
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
