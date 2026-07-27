-- ============================================================
-- Modul vpn — port remote per akun (DNAT -> Winbox perangkat).
--
-- Tiap akun VPN kini memperoleh SATU port publik TCP unik di hub. Hub men-DNAT
-- `IP_HUB:remote_port` -> `overlay_ip:8291` (Winbox), jadi operator bisa meremote tiap
-- Mikrotik lewat `IP_HUB:port` tanpa ikut men-dial tunnel. Unik per HUB (lintas-tenant),
-- sejalan dengan (server_id, username) & (server_id, overlay_ip).
--
-- Backfill: alokasikan berurutan mulai 20000 per hub (urut waktu buat), lalu NOT NULL.
-- ============================================================

ALTER TABLE vpn_peer ADD COLUMN remote_port integer;

-- Isi port untuk baris lama: 20000, 20001, … per hub (deterministik: urut created_at, id).
WITH ranked AS (
    SELECT id,
           20000 + (row_number() OVER (PARTITION BY server_id ORDER BY created_at, id) - 1)::int AS port
    FROM vpn_peer
)
UPDATE vpn_peer p
SET remote_port = ranked.port
FROM ranked
WHERE p.id = ranked.id;

ALTER TABLE vpn_peer ALTER COLUMN remote_port SET NOT NULL;

-- Satu port publik hanya boleh dipakai satu akun dalam satu hub.
ALTER TABLE vpn_peer ADD CONSTRAINT uq_vpn_peer_remote_port UNIQUE (server_id, remote_port);
