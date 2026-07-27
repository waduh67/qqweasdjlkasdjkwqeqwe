# Modul `vpn` — back-haul OpenVPN untuk remote perangkat tanpa IP publik

Kebanyakan Mikrotik/ONT pelanggan duduk di belakang NAT tanpa IP publik, jadi tak
bisa di-Winbox/SSH langsung. Modul ini membalik arah: operator menjalankan **hub
OpenVPN**, tiap perangkat (peer) men-**dial keluar** ke hub dan memperoleh **IP
overlay tetap** yang bisa dijangkau dari sisi operator. Tak perlu port-forward di
sisi ISP.

Module ini **swasembada** — tidak menaut ke module lain (tak ada `*Api` lintas
module, tak ada FK lintas-module). `device_type`/`device_id` hanyalah label bebas
untuk mengingat perangkat apa yang dijangkau.

---

## Model domain

```
VpnServer (hub)                          VpnPeer (perangkat yang men-dial)
├── name · host · port · protocol        ├── serverId          (FK intra-module)
├── tunnelCidr  (mis. 10.8.0.0/24)       ├── username          (unik per hub)
├── status  ACTIVE / DISABLED            ├── overlayIp         (tetap, unik per hub)
├── caCertPem   ← rahasia (terenkripsi)  ├── status  ENABLED / DISABLED
└── tlsAuthKey  ← rahasia (terenkripsi)  ├── password ← rahasia (terenkripsi)
                                         └── deviceType/deviceId  (label bebas, tanpa FK)
```

`TunnelSubnet` adalah value object CIDR IPv4 murni (matematika 32-bit):
- `parse(cidr)` — validasi dotted-quad + prefix 8..30, dinormalkan ke alamat jaringan.
- `serverAddress()` = network + 1 (alamat hub di overlay).
- `allocate(used)` — IP host bebas **terendah** di `[network+2 .. broadcast-1]`;
  melempar `ConflictException` bila blok habis.

**Password & IP overlay digenerate otomatis** saat peer dibuat; username
diturunkan dari nama (slug) dan dijamin unik per hub (sufiks `-2`, `-3`, …).

---

## Render konfigurasi (`VpnConfigRenderer`, murni tanpa I/O)

Tiga artefak siap-unduh, dirender dari entitas yang rahasianya **sudah
terdekripsi**:

1. **`.ovpn` klien** (`GET /peers/{id}/ovpn`) — mandiri, CA + kredensial inline
   (`<auth-user-pass>`, `<ca>`, dan `<tls-auth>` + `key-direction 1` bila hub punya
   tls-auth). Melempar `ConflictException` bila hub belum punya CA.
2. **Skrip RouterOS v7** (`GET /peers/{id}/routeros`) — `/interface/ovpn-client/add`
   dengan `verify-server-certificate=no`, sejalan dengan adapter Mikrotik REST v7 di
   module `bng`.
3. **`server.conf` + client-config-dir** (`GET /servers/{id}/config`) — isi
   `server.conf` plus map `username → ifconfig-push {overlayIp} {netmask}` untuk tiap
   peer **ENABLED**, sehingga tiap peer selalu mendapat IP overlay yang sama.

> Sertifikat/kunci **server** + `dh` tetap disediakan operator (easy-rsa) — modul
> hanya menyimpan CA & tls-auth dan merangkai config klien/CCD.

---

## Keamanan

- **Rahasia terenkripsi di adapter.** `caCertPem`, `tlsAuthKey`, dan `password`
  peer dienkripsi `SecretCipher` saat disimpan; database hanya melihat ciphertext.
  Kolomnya `text` (CA/tls-auth PEM) / `varchar(512)` (password) agar muat.
- **Tak pernah bocor lewat view biasa.** `VpnServerView` hanya membawa
  `hasCaCert`/`hasTlsAuth` (boolean); password peer tak pernah dibaca balik.
  Rahasia hanya keluar lewat endpoint unduh config yang **berizin terpisah**
  (`vpn.config.view`).
- **Degradasi anggun.** Rahasia yang tak bisa didekripsi (mis. kunci enkripsi
  dirotasi tanpa migrasi) tidak menggagalkan pemuatan daftar — barisnya tetap
  tampil, hanya ditandai perlu diisi ulang. Saat menyimpan field lain, sentinel
  kosong itu **tidak menimpa** ciphertext password yang ada.
- Dua-lapis RLS pada `vpn_server` & `vpn_peer` (per `V26__vpn.sql`).

---

## Konfigurasi (`ftth.vpn`)

Dipakai sebagai bawaan saat hub dibuat tanpa nilai eksplisit:

| Properti | Bawaan |
|---|---|
| `default-port` | `1194` |
| `default-protocol` | `UDP` |
| `default-tunnel-cidr` | `10.8.0.0/24` |

---

## API

| Endpoint | Izin |
|---|---|
| `GET /api/vpn/servers` · `/{id}` | `vpn.server.view` |
| `POST/PUT/DELETE /api/vpn/servers` · `/{id}` | `vpn.server.manage` |
| `PUT /api/vpn/servers/{id}/credentials` | `vpn.server.manage` |
| `GET /api/vpn/servers/{id}/config` | `vpn.config.view` |
| `GET /api/vpn/servers/{id}/peers` · `GET /api/vpn/peers/{id}` | `vpn.peer.view` |
| `POST /api/vpn/servers/{id}/peers` | `vpn.peer.manage` |
| `POST /api/vpn/peers/{id}/{enable,disable,rotate-password}` · `DELETE` | `vpn.peer.manage` |
| `GET /api/vpn/peers/{id}/ovpn` · `/routeros` | `vpn.config.view` |

Hapus hub ditolak selama masih punya peer.

---

## Alur operator (ringkas)

1. Siapkan hub OpenVPN di server operator (easy-rsa: CA, sertifikat server, `dh`).
2. `POST /api/vpn/servers` → daftarkan hub (host publik, port, `tunnelCidr`).
3. `PUT /servers/{id}/credentials` → tempel PEM CA & kunci tls-auth.
4. `POST /servers/{id}/peers` → tiap perangkat dapat username + IP overlay + password.
5. `GET /servers/{id}/config` → pasang `server.conf` + folder `ccd` di hub.
6. Di perangkat: unduh `.ovpn` (klien generik) atau skrip RouterOS, terapkan.
7. Perangkat men-dial masuk → jangkau lewat IP overlay-nya untuk Winbox/SSH.
