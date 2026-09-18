-- V173: Dukungan Multi-Server RADIUS dengan Batas Kapasitas (Auto-Distribute Tenant)

CREATE TABLE radius_server (
    id            uuid PRIMARY KEY,
    name          varchar(100) NOT NULL UNIQUE,
    host          varchar(255) NOT NULL,
    auth_port     int NOT NULL DEFAULT 1812,
    acct_port     int NOT NULL DEFAULT 1813,
    coa_port      int NOT NULL DEFAULT 3799,
    shared_secret varchar(255) NOT NULL,
    db_url        varchar(500) NOT NULL,
    db_user       varchar(100) NOT NULL,
    db_password   text NOT NULL,
    max_tenants   int NOT NULL DEFAULT 2,
    status        varchar(30) NOT NULL DEFAULT 'ACTIVE',
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tenant_radius_server (
    tenant_id        uuid PRIMARY KEY REFERENCES tenant(id) ON DELETE CASCADE,
    radius_server_id uuid NOT NULL REFERENCES radius_server(id) ON DELETE RESTRICT,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_tenant_radius_server_server_id ON tenant_radius_server(radius_server_id);
