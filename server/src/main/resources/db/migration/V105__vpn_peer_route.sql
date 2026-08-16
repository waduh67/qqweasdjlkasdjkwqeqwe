-- ============================================================
-- Modul vpn — akun VPN boleh membawa BLOK ALAMAT di belakangnya (site-to-site).
--
-- Sampai sekarang tunnel hanya menjangkau perangkatnya sendiri (IP overlay). Cukup untuk
-- remote Winbox, tapi buntu untuk hal yang justru paling sering ditanya: menghubungi ONT
-- pelanggan.
--
-- Duduk perkaranya. ONT tak pernah punya IP publik — yang ia punya alamat dari kolam BRAS,
-- mis. 10.20.255.254 di dalam 10.20.0.0/16. Dari sisi BRAS itu bukan "di balik NAT", melainkan
-- peer PPPoE yang menempel langsung. Yang tak tahu jalan justru SERVER KAMI: ia berdiri di luar
-- jaringan ISP, tak punya rute ke blok itu, jadi paketnya lari ke gateway bawaan dan hilang.
-- Akibatnya connection request TR-069 selalu dijawab "Not Connect" dan tiap perintah (reboot,
-- ganti SSID, diagnostik) menunggu inform berkala — sampai lima menit untuk hal yang mestinya
-- sedetik.
--
-- Padahal jalannya sudah ada: peer itu SENDIRI adalah router pemilik blok tersebut, dan
-- tunnelnya sudah terpasang. Yang kurang cuma keterangan "blok ini ada di belakang peer itu" —
-- yaitu baris `iroute` di hub plus rute kernel yang menunjuk tunnel. Tabel inilah sumber
-- kebenaran keterangan tersebut; hub menariknya lewat callback provisioning.
--
-- UNIQUE (server_id, cidr) bukan kerapian: dua peer pada hub yang sama mengklaim blok yang
-- sama membuat tabel iroute OpenVPN ambigu, dan OpenVPN tak mengeluh — ia diam-diam memilih
-- salah satu. Irisan yang tak persis sama (10.20.0.0/16 vs 10.20.5.0/24) tak bisa ditangkap
-- UNIQUE, jadi dijaga di aplikasi yang bisa membandingkan rentang.
--
-- Tanpa RLS, mengikuti vpn_peer induknya (lihat V29): callback dari hub tak tahu tenant.
-- ============================================================

CREATE TABLE vpn_peer_route (
    id         uuid PRIMARY KEY,
    peer_id    uuid        NOT NULL REFERENCES vpn_peer (id) ON DELETE CASCADE,
    -- Didenormalisasi dari peer: blok wajib unik per HUB, dan UNIQUE tak bisa menyeberang tabel.
    server_id  uuid        NOT NULL REFERENCES vpn_server (id),
    label      varchar(40) NOT NULL,
    -- Disimpan sudah kanonik (alamat network, bukan alamat host) — normalisasi di domain.
    cidr       varchar(43) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_vpn_peer_route_cidr UNIQUE (server_id, cidr)
);

-- Daftar blok satu akun (jalur panas UI + callback client-connect); jalur per hub sudah
-- ditutup UNIQUE di atas.
CREATE INDEX ix_vpn_peer_route_peer ON vpn_peer_route (peer_id);
