-- ============================================================
-- Modul vpn — token node per hub (kredensial installer + callback VPS)
--
-- Installer OpenVPN di VPS dan skrip callback (verify user/pass, minta IP overlay)
-- mengautentikasi ke aplikasi dengan token ini. SENGAJA TANPA RLS: barisnya dicari
-- lewat hash token SEBELUM tenant diketahui (persis pola API key `collector`).
-- tenant_id disimpan agar TenantContext bisa dipasang sebelum membaca vpn_server
-- yang ber-RLS. Hanya HASH (SHA-256) yang disimpan; token mentah tampil sekali.
-- Satu token aktif per hub (UNIQUE server_id); rotasi = hapus lama, terbit baru.
-- ============================================================

CREATE TABLE vpn_node_token (
    id           uuid PRIMARY KEY,
    server_id    uuid        NOT NULL REFERENCES vpn_server (id) ON DELETE CASCADE,
    tenant_id    uuid        NOT NULL,
    token_hash   varchar(64) NOT NULL,
    token_hint   varchar(16) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_vpn_node_token_server UNIQUE (server_id),
    CONSTRAINT uq_vpn_node_token_hash   UNIQUE (token_hash)
);
-- Jalur panas autentikasi node: lookup by hash.
CREATE INDEX ix_vpn_node_token_hash ON vpn_node_token (token_hash);
