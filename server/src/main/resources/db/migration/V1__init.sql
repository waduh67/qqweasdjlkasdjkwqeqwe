-- ============================================================
-- Phase 0: tenancy + IAM/RBAC + audit
--
-- Tabel platform (TANPA tenant_id): tenant, permission
-- Tabel tenant-scoped (tenant_id + RLS FORCE): app_user, role,
--   role_permission, user_role, area, user_area, audit_log
-- refresh_token: punya tenant_id tapi TANPA RLS (lookup pre-auth
--   by hash saat refresh, sebelum tenant context ada).
-- ============================================================

CREATE TABLE tenant (
    id          uuid PRIMARY KEY,
    slug        varchar(63)  NOT NULL UNIQUE,
    name        varchar(255) NOT NULL,
    status      varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE permission (
    id            uuid PRIMARY KEY,
    code          varchar(120) NOT NULL UNIQUE,
    module        varchar(40)  NOT NULL,
    resource      varchar(40)  NOT NULL,
    action        varchar(40)  NOT NULL,
    description   varchar(255),
    platform_only boolean      NOT NULL DEFAULT false,
    active        boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id             uuid PRIMARY KEY,
    tenant_id      uuid         NOT NULL REFERENCES tenant (id),
    email          varchar(255) NOT NULL,
    name           varchar(255) NOT NULL,
    password_hash  varchar(100) NOT NULL,
    status         varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    platform_admin boolean      NOT NULL DEFAULT false,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_app_user_tenant_email ON app_user (tenant_id, lower(email));

CREATE TABLE role (
    id          uuid PRIMARY KEY,
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    name        varchar(100) NOT NULL,
    description varchar(255),
    system_role boolean      NOT NULL DEFAULT false,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name)
);

-- Join table murni (dipetakan @ElementCollection). Isolasi tenant lewat parent
-- `role` yang sudah RLS; permission bersifat platform-level. Tanpa surrogate id
-- agar cocok dengan @ElementCollection.
CREATE TABLE role_permission (
    role_id       uuid NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES permission (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE area (
    id         uuid PRIMARY KEY,
    tenant_id  uuid         NOT NULL REFERENCES tenant (id),
    code       varchar(40)  NOT NULL,
    name       varchar(120) NOT NULL,
    parent_id  uuid REFERENCES area (id),
    created_at timestamptz  NOT NULL DEFAULT now(),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

-- Join tables murni (dipetakan @ElementCollection). Isolasi lewat parent
-- `app_user` yang sudah RLS.
CREATE TABLE user_role (
    user_id uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id uuid NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE user_area (
    user_id uuid NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    area_id uuid NOT NULL REFERENCES area (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, area_id)
);

CREATE TABLE refresh_token (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_token_user ON refresh_token (user_id);

CREATE TABLE audit_log (
    id          uuid PRIMARY KEY,
    tenant_id   uuid        NOT NULL REFERENCES tenant (id),
    actor_id    uuid,
    actor_email varchar(255),
    action      varchar(80) NOT NULL,
    entity_type varchar(80),
    -- entity_id disimpan di dalam kolom `detail` (JSON), bukan kolom tersendiri:
    -- nilainya berupa string berformat UUID dan memicu bug resolusi tipe di
    -- Hibernate 7 bila dipetakan sebagai kolom String terpisah.
    detail      text,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_log_tenant_time ON audit_log (tenant_id, occurred_at DESC);

-- ============================================================
-- Row-Level Security: lapisan kedua di bawah filter @TenantId Hibernate.
-- FORCE penting karena app connect sebagai role pemilik tabel (owner
-- by default bypass RLS). GUC app.tenant_id di-set oleh
-- TenantConnectionProvider tiap connection dipinjam dari pool.
-- NULLIF(...,'') karena RESET menghasilkan string kosong, bukan NULL.
-- Kalau GUC tidak di-set: SELECT tidak dapat baris apa pun, INSERT ditolak.
-- ============================================================
DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'app_user', 'role', 'area', 'audit_log'
            ]
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
