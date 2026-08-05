-- ============================================================
-- Portal self-service pelanggan: kredensial LOGIN TERPISAH dari IAM operator.
--
-- Pelanggan akhir masuk ke portal-nya sendiri (tenant slug + login + password) dan
-- hanya melihat data DIRINYA. Ini realm berbeda: token-nya ditandatangani secret lain
-- (isolasi kripto), principal-nya `PortalCustomer` (bukan `AuthenticatedUser` IAM),
-- dan rantai keamanannya sendiri (`/api/portal/**`). Ke depan bisa dipisah domain.
--
-- Dua tabel, meniru pola auth operator:
--   portal_credential      TENANT-AWARE + RLS — 1 kredensial per pelanggan, login unik
--                          per-tenant (login kembar antar-tenant boleh, sama seperti PPPoE).
--   portal_refresh_token   TANPA RLS — lookup by hash terjadi SEBELUM tenant context ada
--                          (saat refresh/logout). Pola sama `refresh_token`/`user_directory`.
-- ============================================================

CREATE TABLE portal_credential (
    id            uuid PRIMARY KEY,
    tenant_id     uuid         NOT NULL REFERENCES tenant (id),
    -- Pelanggan pemilik kredensial (agregat customer, RLS-nya sendiri). Tak di-FK lintas
    -- module demi jaga batas; keunikan 1:1 ditegakkan constraint di bawah.
    customer_id   uuid         NOT NULL,
    -- Identitas login yang diketik pelanggan (dinormalkan lower-case di domain).
    login         varchar(64)  NOT NULL,
    -- BCrypt hash — DB tak pernah melihat password asli.
    password_hash varchar(100) NOT NULL,
    -- Non-null = kredensial dinonaktifkan operator (pelanggan tak bisa login).
    disabled_at   timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    -- Satu kredensial portal per pelanggan.
    CONSTRAINT uq_portal_credential_customer UNIQUE (tenant_id, customer_id),
    -- Login unik per tenant (bukan global) — pelanggan tenant beda boleh sama.
    CONSTRAINT uq_portal_credential_login UNIQUE (tenant_id, login)
);

-- Row-Level Security dua-lapis, sama dengan tabel bisnis lain.
ALTER TABLE portal_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE portal_credential FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON portal_credential
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- ------------------------------------------------------------
-- Refresh token portal — SENGAJA tanpa RLS (lookup by hash pra-tenant).
-- Kolom tenant_id tetap disimpan agar rotasi memasang tenant context yang benar.
-- ------------------------------------------------------------
CREATE TABLE portal_refresh_token (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    customer_id uuid        NOT NULL,
    token_hash  varchar(64) NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_portal_refresh_token_customer ON portal_refresh_token (customer_id);
