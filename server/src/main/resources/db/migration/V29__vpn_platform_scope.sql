-- ============================================================
-- Modul vpn — PIVOT ke "VPN-as-a-service": hub jadi milik PLATFORM, bukan tenant.
--
-- Model baru: platform (operator SaaS) menjalankan hub OpenVPN di VPS ber-IP publik dan
-- mengelolanya hanya di dashboard admin platform. Tenant tinggal men-generate AKUN VPN;
-- sistem menautkannya ke salah satu hub yang tersedia (auto-assign). Karena satu hub kini
-- dibagi banyak tenant, username & IP overlay harus unik per HUB (lintas-tenant), dan alur
-- callback OpenVPN harus bisa me-resolve peer tanpa tahu tenant lebih dulu.
--
-- Perubahan:
--   vpn_server      → lepas tenant_id + RLS (jadi tabel platform, tanpa tenant).
--   vpn_peer        → tetap milik tenant (kolom tenant_id + FK dipertahankan) TAPI tanpa RLS
--                     (pola sama dengan `collector`: difilter tenant di aplikasi, sekaligus
--                     bisa dibaca lintas-tenant untuk auth callback). Unik per (server, ...).
--   vpn_node_token  → lepas tenant_id (hub tak lagi punya tenant tunggal).
-- ============================================================

-- ------------------------------------------------------------
-- vpn_server: infrastruktur platform (buang tenant + RLS)
-- ------------------------------------------------------------
DROP POLICY IF EXISTS tenant_isolation ON vpn_server;
ALTER TABLE vpn_server NO FORCE ROW LEVEL SECURITY;
ALTER TABLE vpn_server DISABLE ROW LEVEL SECURITY;
-- Membuang kolom ikut membuang FK tenant otomatis.
ALTER TABLE vpn_server DROP COLUMN tenant_id;

-- ------------------------------------------------------------
-- vpn_peer: akun VPN milik tenant, tanpa RLS (cermin collector)
--   - tenant_id + FK dipertahankan (integritas & filter aplikasi)
--   - unik per HUB, lintas-tenant: (server_id, username) & (server_id, overlay_ip)
-- ------------------------------------------------------------
DROP POLICY IF EXISTS tenant_isolation ON vpn_peer;
ALTER TABLE vpn_peer NO FORCE ROW LEVEL SECURITY;
ALTER TABLE vpn_peer DISABLE ROW LEVEL SECURITY;

ALTER TABLE vpn_peer DROP CONSTRAINT IF EXISTS uq_vpn_peer_username;
ALTER TABLE vpn_peer DROP CONSTRAINT IF EXISTS uq_vpn_peer_overlay;
ALTER TABLE vpn_peer ADD CONSTRAINT uq_vpn_peer_username UNIQUE (server_id, username);
ALTER TABLE vpn_peer ADD CONSTRAINT uq_vpn_peer_overlay  UNIQUE (server_id, overlay_ip);

-- Daftar akun per tenant (jalur panas UI tenant); daftar per hub sudah ditutup UNIQUE di atas.
DROP INDEX IF EXISTS ix_vpn_peer_server;
CREATE INDEX ix_vpn_peer_tenant ON vpn_peer (tenant_id);

-- ------------------------------------------------------------
-- vpn_node_token: menaut ke hub saja (hub tanpa tenant)
-- ------------------------------------------------------------
ALTER TABLE vpn_node_token DROP COLUMN tenant_id;
