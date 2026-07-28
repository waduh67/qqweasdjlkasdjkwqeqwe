# Modul `vpn` — VPN-as-a-service untuk remote perangkat tanpa IP publik

Kebanyakan Mikrotik/ONT pelanggan duduk di belakang NAT tanpa IP publik, jadi tak
bisa di-Winbox/SSH langsung. Modul ini membalik arah: **platform** menjalankan
**hub OpenVPN** di VPS-nya sendiri (IP publik milik platform), lalu **tenant cukup
sekali klik "Generate akun"** — sistem meng-**auto-assign** akun itu ke hub yang
tersedia dan mengembalikan kredensial siap tempel di Mikrotik (host:port, protokol,
tipe keamanan, username, password). Perangkat men-**dial keluar** ke hub dan
memperoleh **IP overlay tetap** yang bisa dijangkau dari sisi operator. Tak perlu
port-forward di sisi ISP, dan tak perlu langkah teknis manual di sisi tenant.

Dua bidang yang tegas dipisah izin:

- **Hub (server)** = infrastruktur **platform**. Hanya admin platform yang
  mengelolanya (`vpn.server.*`, platform-only). Banyak hub didukung.
- **Akun** = milik **tenant**. Tenant tak pernah memilih/melihat hub — cukup
  generate akun (`vpn.peer.*`), lalu unduh config (`vpn.config.view`).

Module ini **swasembada** — tidak menaut ke module lain (tak ada `*Api` lintas
module, tak ada FK lintas-module). `deviceType`/`deviceId` hanyalah label bebas.

---

## Model domain

```
VpnServer (hub — infrastruktur PLATFORM)     VpnPeer (akun — milik TENANT)
├── name · host · port · protocol            ├── tenantId          (kolom polos, pemilik akun)
├── tunnelCidr  (mis. 10.8.0.0/24)           ├── serverId          (FK intra-module → hub)
├── status  ACTIVE / DISABLED                ├── username          (unik per hub, lintas-tenant)
├── caCertPem / caKeyPem       ← rahasia     ├── overlayIp         (tetap, unik per hub)
├── serverCertPem / serverKeyPem ← rahasia   ├── status  ENABLED / DISABLED
└── tlsAuthKey                 ← rahasia     ├── password          ← rahasia (terenkripsi)
                                             └── deviceType/deviceId  (label bebas, tanpa FK)

VpnNodeToken (kredensial installer/callback VPS, satu aktif per hub)
├── serverId  (menaut ke hub saja — bukan tenant)
├── tokenHash (SHA-256; token mentah `ftthv_…` sekali tampil)
└── tokenHint (beberapa char terakhir untuk pengenalan)
```

`TunnelSubnet` adalah value object CIDR IPv4 murni (matematika 32-bit):
- `parse(cidr)` — validasi dotted-quad + prefix 8..30, dinormalkan ke alamat jaringan.
- `serverAddress()` = network + 1 (alamat hub di overlay).
- `allocate(used)` — IP host bebas **terendah** di `[network+2 .. broadcast-1]`;
  melempar `ConflictException` bila blok habis.

**Password & IP overlay digenerate otomatis** saat akun di-generate; username
diturunkan dari label (slug) — atau eksplisit — dan dijamin unik **per hub**
(sufiks `-2`, `-3`, …). Auto-assign memilih hub **paling lengang** di antara yang
siap-pakai (ACTIVE + PKI); menolak dengan pesan jelas bila belum ada hub tersedia.

---

## Aplikasi jadi CA-nya sendiri (`ServerPkiIssuer`)

Saat hub dibuat, aplikasi **menerbitkan CA + sertifikat server** otomatis
(BouncyCastle) — operator tak perlu easy-rsa manual. PEM CA/kunci CA & sertifikat/
kunci server disimpan terenkripsi di hub. `pkiReady` menandai hub siap dipasang.

## Provisioning satu-perintah + callback (tanpa langkah manual)

Setiap hub lahir dengan **satu token node** (`ftthv_…`, sekali tampil) dan
**perintah pasang satu-baris** (`curl … | sudo bash`). Installer, yang berjalan di
VPS, memasang OpenVPN + PKI aplikasi dan menyambungkan callback verifikasi.
Endpoint provisioning di-authentikasi lewat **token node** (bukan bearer JWT) dan
di-allowlist di `SecurityConfig` (`/api/vpn/provision/**`):

- `GET /api/vpn/provision/install.sh?token=…` — render installer untuk hub token itu.
- `POST /api/vpn/provision/authenticate` — dipanggil `auth-user-pass-verify`; cocokkan
  username/password akun (banding waktu-tetap), hanya akun **ENABLED** → 204/403.
- `POST /api/vpn/provision/client-connect` — kunci IP overlay tetap akun
  (`ifconfig-push {overlayIp} {netmask}`).
- `POST /api/vpn/provision/client-connected` / `…/client-disconnected` — **telemetri
  liveness**: hub melapor akun terhubung/putus → aplikasi menandai `online` + `lastHandshakeAt`.

Model OpenVPN-nya: `verify-client-cert none` + `username-as-common-name` +
`auth-user-pass-verify … via-file` + `client-connect …` + `script-security 2` +
`dh none`. Cipher dinego lewat **NCP** (`data-ciphers AES-256-GCM:AES-256-CBC` +
`data-ciphers-fallback AES-256-CBC`) → **satu hub melayani RouterOS v7 (GCM) &
v6 (CBC)** sekaligus. Jadi VPS tak menyimpan daftar user — semua verifikasi
memanggil balik aplikasi.

**Dukungan RouterOS v6.** Klien OVPN v6 itu **TCP-only** + **CBC-only** (tanpa NCP)
dan tanpa properti `verify-server-certificate`. Maka: (a) hub yang melayani v6 harus
berprotokol **TCP** (v6 mustahil dial UDP) — `VpnAccountView.supportsV6` = true hanya
untuk hub TCP; (b) config v6 dirender terpisah lewat `VpnClientVariant.V6` (menu lama
`/interface ovpn-client add`, `cipher=aes256-cbc`, `certificate=none`). Perangkat v7
tak terpengaruh (tetap nego GCM). Render v6 **best-effort** — properti CLI v6 bervariasi
antar-rilis; minta akun varian v6 pada hub non-TCP ditolak `ConflictException`.

**Liveness peer (online nyata).** Selain status administratif (ENABLED/DISABLED), tiap akun
punya `online` + `lastHandshakeAt` yang **mencerminkan koneksi nyata**. Skrip `client-connect`
di hub memanggil `client-connected` saat perangkat men-dial, dan `client-disconnect` memanggil
`client-disconnected` saat putus — keduanya best-effort (tak menggerbang tunnel). Telemetri
**sengaja terpisah** dari endpoint `client-connect` yang load-bearing: hub lama (pra-liveness)
memakai ulang `client-connect` untuk connect DAN disconnect, jadi endpoint terpisah menjamin hub
lama sekadar tak melapor (`online=false`, `lastHandshakeAt=null` → "belum diketahui") alih-alih
salah melapor online saat putus. Jalankan ulang installer di hub untuk mengaktifkan liveness.

---

## Non-RLS lintas-tenant (cermin `collector`)

Satu hub platform dibagi banyak tenant, jadi username/overlay harus unik **per hub
lintas semua tenant**, dan callback OpenVPN (yang tak tahu tenant) me-resolve akun
lewat `(serverId, username)`. RLS FORCE akan memblokir pembacaan lintas-tenant itu.
Solusinya sama seperti `collector`: `vpn_server`, `vpn_peer`, `vpn_node_token`
adalah tabel **tanpa RLS**; `vpn_peer` menyimpan `tenant_id` sebagai **kolom polos**
(bukan `@TenantId`).

- **Daftar tenant** difilter di aplikasi: `findByTenant(TenantContext.tenantId())`.
- **Alokasi/keunikan/callback** membaca lintas-tenant per hub.
- **Kepemilikan** ditegakkan di layanan: tiap mutasi/unduh akun memverifikasi
  `peer.tenantId == TenantContext.tenantId()` (jika bukan → disamarkan 404).
- `VpnNodeAuthenticator` memakai `REQUIRES_NEW` (cermin `CollectorAuthenticator`)
  agar sesi resolusi token menutup rapi sebelum pembacaan berikutnya.

---

## Render konfigurasi (`VpnConfigRenderer`, murni tanpa I/O)

Artefak siap-unduh, dirender dari entitas yang rahasianya **sudah terdekripsi**:

1. **`.ovpn` klien** (`GET /accounts/{id}/ovpn?variant=V7|V6`) — mandiri, CA + kredensial
   inline (`<auth-user-pass>`, `<ca>`, dan `<tls-auth>` + `key-direction 1` bila hub punya
   tls-auth). `variant=V6` memakai `cipher AES-256-CBC` + `proto tcp`. Melempar
   `ConflictException` bila hub belum punya CA (atau V6 pada hub non-TCP).
2. **Skrip RouterOS** (`GET /accounts/{id}/routeros?variant=V7|V6`) — default **v7**
   (`/interface/ovpn-client/add cipher=aes256-gcm verify-server-certificate=no`, sejalan
   dengan adapter Mikrotik REST v7 di module `bng`). `variant=V6` merender sintaks menu lama
   (`/interface ovpn-client add cipher=aes256-cbc certificate=none`) untuk perangkat lama —
   hanya pada hub TCP.
3. **`server.conf` + client-config-dir** (`GET /servers/{id}/config`, admin platform)
   — isi `server.conf` plus map `username → ifconfig-push {overlayIp} {netmask}` untuk
   tiap akun **ENABLED**. (Umumnya tak perlu disentuh manual: installer sudah
   menuliskannya dan callback yang mengunci IP.)

---

## Keamanan

- **Rahasia terenkripsi di adapter.** PEM CA/kunci CA, sertifikat/kunci server,
  `tlsAuthKey`, dan `password` akun dienkripsi `SecretCipher` saat disimpan; DB hanya
  melihat ciphertext. Token node disimpan sebagai **hash SHA-256** (mentahnya sekali
  tampil).
- **Tak pernah bocor lewat view biasa.** `VpnServerView` hanya membawa
  `hasCaCert`/`hasTlsAuth`/`pkiReady` (boolean). `VpnAccountView.password` hanya
  terisi **sekali** saat generate/rotasi; pada list/get selalu `null`. Rahasia hanya
  keluar lewat endpoint unduh config yang **berizin terpisah** (`vpn.config.view`).
- **Degradasi anggun.** Rahasia yang tak bisa didekripsi tidak menggagalkan pemuatan
  daftar; saat menyimpan field lain, sentinel kosong **tidak menimpa** ciphertext
  password yang ada.
- **Isolasi.** Hub platform-only (tenant tak bisa mengelola). Akun difilter &
  di-cek kepemilikan per tenant. Token satu hub tak bisa mengautentikasi akun di hub
  lain (scoping per hub via `serverId`).

---

## Konfigurasi (`ftth.vpn`)

| Properti | Bawaan | Guna |
|---|---|---|
| `default-port` | `1194` | Bawaan saat hub dibuat tanpa nilai eksplisit |
| `default-protocol` | `UDP` | idem |
| `default-tunnel-cidr` | `10.8.0.0/24` | idem |
| `public-base-url` | `""` | URL publik aplikasi untuk perintah pasang; kosong → placeholder |

---

## API

**Hub (platform, `vpn.server.*` platform-only):**

| Endpoint | Izin |
|---|---|
| `GET /api/vpn/servers` · `/{id}` | `vpn.server.view` |
| `POST/PUT/DELETE /api/vpn/servers` · `/{id}` | `vpn.server.manage` |
| `PUT /api/vpn/servers/{id}/credentials` | `vpn.server.manage` |
| `POST /api/vpn/servers/{id}/regenerate-token` | `vpn.server.manage` |
| `GET /api/vpn/servers/{id}/config` | `vpn.config.view` |

**Akun (tenant):**

| Endpoint | Izin |
|---|---|
| `GET /api/vpn/accounts` · `/{id}` | `vpn.peer.view` |
| `POST /api/vpn/accounts/generate` | `vpn.peer.manage` |
| `POST /api/vpn/accounts/{id}/{enable,disable,rotate-password}` · `DELETE` | `vpn.peer.manage` |
| `GET /api/vpn/accounts/{id}/ovpn` · `/routeros` (`?variant=V7\|V6`) | `vpn.config.view` |

**Provisioning (dari VPS, auth via token node — tanpa bearer, di-allowlist):**

| Endpoint | Auth |
|---|---|
| `GET /api/vpn/provision/install.sh?token=…` | token node |
| `POST /api/vpn/provision/authenticate` | token node |
| `POST /api/vpn/provision/client-connect` | token node |
| `POST /api/vpn/provision/client-connected` · `/client-disconnected` | token node |

Hapus hub ditolak selama masih menampung akun.

---

## Alur (ringkas)

**Admin platform (sekali per hub):**

1. `POST /api/vpn/servers` → daftarkan hub (host publik VPS, port, `tunnelCidr`).
   App menerbitkan CA + cert server otomatis dan mengembalikan **perintah pasang +
   token node** (sekali tampil).
2. Jalankan perintah pasang **sekali** di VPS sebagai root. Selesai — hub siap.

**Tenant (satu klik, berulang):**

3. `POST /api/vpn/accounts/generate` → sistem auto-assign ke hub tersedia dan
   mengembalikan endpoint + kredensial (password sekali tampil).
4. Unduh `.ovpn`/RouterOS atau salin host:port + username/password → tempel di
   Mikrotik. Perangkat men-dial masuk → jangkau lewat IP overlay-nya untuk Winbox/SSH.
