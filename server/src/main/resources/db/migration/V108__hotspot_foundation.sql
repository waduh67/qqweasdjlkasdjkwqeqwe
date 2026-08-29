CREATE TABLE hotspot_site (
    id                    uuid PRIMARY KEY,
    tenant_id             uuid         NOT NULL REFERENCES tenant (id),
    nas_id                uuid         NOT NULL,
    portal_id             varchar(22)  NOT NULL,
    name                  varchar(120) NOT NULL,
    location              varchar(300),
    portal_mode           varchar(20)  NOT NULL,
    branding_display_name varchar(100),
    branding_logo_url     varchar(500),
    default_plan_id       uuid,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_hotspot_site_tenant_nas UNIQUE (tenant_id, nas_id),
    CONSTRAINT uq_hotspot_site_portal_id UNIQUE (portal_id),
    CONSTRAINT ck_hotspot_site_portal_mode CHECK (portal_mode IN ('OFF', 'NAS_OWNED', 'NETOPS_HOSTED')),
    CONSTRAINT ck_hotspot_site_portal_id CHECK (portal_id ~ '^[A-Za-z0-9_-]{22}$')
);

CREATE INDEX ix_hotspot_site_tenant ON hotspot_site (tenant_id);
CREATE INDEX ix_hotspot_site_tenant_portal_id ON hotspot_site (tenant_id, portal_id);

ALTER TABLE hotspot_site ENABLE ROW LEVEL SECURITY;
ALTER TABLE hotspot_site FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON hotspot_site
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE hotspot_voucher_batch (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    status     varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_hotspot_voucher_batch_tenant_status ON hotspot_voucher_batch (tenant_id, status);

ALTER TABLE hotspot_voucher_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE hotspot_voucher_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON hotspot_voucher_batch
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE hotspot_voucher (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    status     varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_hotspot_voucher_tenant_status ON hotspot_voucher (tenant_id, status);

ALTER TABLE hotspot_voucher ENABLE ROW LEVEL SECURITY;
ALTER TABLE hotspot_voucher FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON hotspot_voucher
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE hotspot_session (
    id         uuid PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenant (id),
    status     varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_hotspot_session_tenant_status ON hotspot_session (tenant_id, status);

ALTER TABLE hotspot_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE hotspot_session FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON hotspot_session
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- E-N 26
