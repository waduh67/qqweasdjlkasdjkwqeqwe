-- ============================================================
-- Modul vpn — penerusan port jadi DAFTAR milik akun (bukan satu port yang dipaku ke Winbox 8291).
--
-- Sebelumnya tiap akun punya SATU port publik dan hub men-DNAT-nya ke `overlay_ip:8291`, dengan
-- angka 8291 tertanam di installer. Di lapangan itu patah dua kali:
--   1. Banyak ISP memindah port Winbox demi keamanan. Begitu dipindah, perangkatnya tak
--      terjangkau lagi lewat hub padahal tunnelnya sehat — dan tak ada tempat untuk
--      memberi tahu sistem port barunya.
--   2. Satu perangkat lazim perlu dijangkau lewat lebih dari satu layanan: Winbox untuk
--      teknisi, API untuk otomasi, SSH untuk yang terbiasa CLI, WebFig untuk yang tak
--      memasang Winbox.
--
-- Sekarang tiap penerusan satu baris: port publik (dialokasikan sistem, unik per HUB) →
-- port layanan di perangkat (boleh diubah kapan saja).
--
-- Backfill: satu baris "Winbox" per akun dari remote_port lama, lalu kolomnya DIBUANG supaya
-- tak ada dua sumber kebenaran untuk port publik.
--
-- Tanpa RLS, mengikuti vpn_peer induknya (lihat V29): callback dari hub tak tahu tenant.
-- ============================================================

CREATE TABLE vpn_port_forward (
    id          uuid PRIMARY KEY,
    peer_id     uuid        NOT NULL REFERENCES vpn_peer (id) ON DELETE CASCADE,
    -- Didenormalisasi dari peer: port publik wajib unik per HUB, dan UNIQUE tak bisa menyeberang tabel.
    server_id   uuid        NOT NULL REFERENCES vpn_server (id),
    label       varchar(40) NOT NULL,
    public_port integer     NOT NULL,
    device_port integer     NOT NULL,
    protocol    varchar(3)  NOT NULL DEFAULT 'TCP',
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    -- Satu port publik hanya boleh menunjuk satu tujuan dalam satu hub (lintas-tenant).
    CONSTRAINT uq_vpn_port_forward_public UNIQUE (server_id, public_port),
    CONSTRAINT ck_vpn_port_forward_protocol CHECK (protocol IN ('TCP', 'UDP')),
    CONSTRAINT ck_vpn_port_forward_ports CHECK (
        public_port BETWEEN 1 AND 65535 AND device_port BETWEEN 1 AND 65535
    )
);
-- Daftar penerusan satu akun (jalur panas UI) — jalur per hub sudah ditutup UNIQUE di atas.
CREATE INDEX ix_vpn_port_forward_peer ON vpn_port_forward (peer_id);

-- Akun yang sudah ada tetap persis seperti sebelumnya: port publiknya sama, sasarannya Winbox.
INSERT INTO vpn_port_forward (id, peer_id, server_id, label, public_port, device_port, protocol)
SELECT gen_random_uuid(), p.id, p.server_id, 'Winbox', p.remote_port, 8291, 'TCP'
FROM vpn_peer p;

ALTER TABLE vpn_peer DROP CONSTRAINT IF EXISTS uq_vpn_peer_remote_port;
ALTER TABLE vpn_peer DROP COLUMN remote_port;
