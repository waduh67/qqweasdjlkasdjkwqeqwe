-- ============================================================
-- Modul vpn — manajer VPN back-haul (OpenVPN) untuk menjangkau perangkat tanpa IP publik
--
-- Dua tabel:
--   vpn_server  hub OpenVPN yang dijalankan operator. Perangkat men-dial ke sini dan
--               memperoleh IP overlay tetap yang bisa di-Winbox/SSH. ca_cert/tls_auth_key
--               disimpan TERENKRIPSI (batas enkripsi di adapter), kolomnya text agar muat
--               PEM + ciphertext. Modul ini SWASEMBADA: tak menaut ke module lain.
--   vpn_peer    satu perangkat terkelola yang men-dial hub. FK intra-module ke vpn_server
--               diperbolehkan. username & overlay_ip unik per (tenant, server). password
--               disimpan TERENKRIPSI. device_type/device_id hanya label bebas TANPA FK.
-- ============================================================

CREATE TABLE vpn_server (
    id           uuid PRIMARY KEY,
    tenant_id    uuid        NOT NULL REFERENCES tenant (id),
    name         varchar(100) NOT NULL,
    host         varchar(255) NOT NULL,
    port         integer      NOT NULL,
    protocol     varchar(10)  NOT NULL,
    tunnel_cidr  varchar(64)  NOT NULL,
    status       varchar(20)  NOT NULL,
    -- Rahasia terenkripsi (PEM/ciphertext panjang) — text, bukan varchar.
    ca_cert      text,
    tls_auth_key text,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_vpn_server_protocol CHECK (protocol IN ('UDP', 'TCP')),
    CONSTRAINT ck_vpn_server_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE vpn_peer (
    id               uuid PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenant (id),
    -- FK intra-module diperbolehkan (menjaga integritas hub yang dirujuk).
    server_id        uuid        NOT NULL REFERENCES vpn_server (id),
    name             varchar(100) NOT NULL,
    username         varchar(64)  NOT NULL,
    overlay_ip       varchar(45)  NOT NULL,
    status           varchar(20)  NOT NULL,
    -- Label bebas atas perangkat yang dijangkau, TANPA FK lintas-module.
    device_type      varchar(60),
    device_id        uuid,
    last_handshake_at timestamptz,
    -- Password terenkripsi (batas enkripsi di adapter).
    password         varchar(512) NOT NULL,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    -- Username & IP overlay unik dalam satu hub.
    CONSTRAINT uq_vpn_peer_username UNIQUE (tenant_id, server_id, username),
    CONSTRAINT uq_vpn_peer_overlay UNIQUE (tenant_id, server_id, overlay_ip),
    CONSTRAINT ck_vpn_peer_status CHECK (status IN ('ENABLED', 'DISABLED'))
);
-- Daftar peer per hub.
CREATE INDEX ix_vpn_peer_server ON vpn_peer (tenant_id, server_id);

-- ------------------------------------------------------------
-- Row-Level Security (dua-lapis, sama dengan tabel bisnis lain)
-- ------------------------------------------------------------
ALTER TABLE vpn_server ENABLE ROW LEVEL SECURITY;
ALTER TABLE vpn_server FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vpn_server
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE vpn_peer ENABLE ROW LEVEL SECURITY;
ALTER TABLE vpn_peer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON vpn_peer
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
