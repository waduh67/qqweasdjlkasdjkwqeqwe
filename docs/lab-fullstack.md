# Lab Full-Stack di Docker (satu perintah)

Panduan menjalankan **seluruh platform FTTH di Docker** — Postgres (PostGIS + TimescaleDB),
MinIO, backend Spring Boot, frontend React, reverse-proxy Caddy, plus lab BRAS/RADIUS —
tanpa perlu memasang Gradle, npm, atau Postgres di mesin lokal. Cocok untuk demo end-to-end
atau mencoba fitur baru tanpa pusing menyiapkan toolchain.

File yang dipakai: [`docker-compose.lab.yml`](../docker-compose.lab.yml).

> Beda dengan [`docs/lab-bras-radius.md`](lab-bras-radius.md): dokumen itu memakai stack **dev**
> (`docker-compose.yml`, hanya database/infra — server & web dijalankan manual via Gradle/npm).
> Dokumen **ini** membangun & menjalankan *semuanya* di dalam Docker.

---

## 1. Prasyarat

- Docker Engine dengan **BuildKit/buildx** (Docker ≥ 23; bawaan Docker Desktop/Engine terbaru).
  Build memakai `--mount=type=cache`, jadi buildx wajib aktif.
- RAM luang ± 4 GB (build Gradle + Vite jalan sekali di tahap image).
- Port host **8080** kosong (satu-satunya port yang diekspos ke host).
- `curl` + `jq` bila mau memakai skrip helper lab BNG di bawah.

---

## 2. Jalankan

```bash
docker compose -f docker-compose.lab.yml up -d --build
```

Build pertama memakan beberapa menit (kompilasi server + bundling web). Setelah itu:

- Buka **http://localhost:8080**
- Login admin tenant: **admin@demo.ftth** / **admin12345**
- Login admin platform (lintas tenant): **root@ftth.local** / **rootadmin123**

Tenant demo di-seed otomatis (`FTTH_SEED_DEMO=true`). Migrasi Flyway jalan saat server start.

Pantau kesiapan:

```bash
docker compose -f docker-compose.lab.yml logs -f server   # tunggu "Started FtthApplication"
docker compose -f docker-compose.lab.yml ps                # semua service healthy/running
```

---

## 3. Isi stack

| Service     | Image / build             | Peran                                                        | Port ke host |
|-------------|---------------------------|-------------------------------------------------------------|--------------|
| `postgres`  | timescale/timescaledb-ha  | DB aplikasi (PostGIS + TimescaleDB). App konek sbg role `ftth` non-superuser → **RLS beneran menegakkan isolasi tenant** | — (internal) |
| `minio`     | minio/minio               | Object storage bukti work order                              | — (internal) |
| `server`    | `server/Dockerfile`       | Backend Spring Modulith                                      | — (via Caddy)|
| `web`       | `web/Dockerfile`          | SPA React (disajikan Nginx)                                  | — (via Caddy)|
| `caddy`     | caddy:2                   | Reverse proxy: `/api` → server, sisanya → web               | **8080**     |
| `radius-db` | postgres:16-alpine        | Skema FreeRADIUS + 1 sesi contoh (lab BNG)                  | — (internal) |
| `freeradius`| freeradius 3.2.5          | *(profil `freeradius`)* server RADIUS nyata utk uji DAE/CoA | — (internal) |
| `collector` | `collector/Dockerfile`    | *(profil `collector`)* agent BNG — poll radacct + eksekusi Disconnect/CoA | — (internal) |

Semua secret memakai **default dev** dari `server/application.yml` (JWT, encryption, admin,
kredensial S3) — tidak ada nilai sensitif di file compose. **Jangan dipakai untuk produksi.**

Project name compose dipisah (`ftth-lab`) supaya volume & container tidak bentrok dengan stack
dev maupun prod.

---

## 4. Lab voucher HOTSPOT: RADIUS pusat, portal milik NAS

Jalankan jalur lab yang benar dengan satu perintah berikut; jangan gunakan helper BNG lama
yang bukan bagian dari target `make lab`:

```bash
make lab
```

`make lab` membangun stack, menjalankan `docker/lab/seed-lab.sh`, lalu menggambar topologi demo.
Simulator bertindak sebagai **virtual NAS** pada `172.30.0.10`: ia berbagi `radius-db` dengan
server untuk data otorisasi (`radcheck`) dan akuntansi (`radacct`), serta menerima DAE pada UDP
3799 di jaringan Docker. Ini mempertahankan boundary yang benar:

```text
Klien → captive portal/login page milik NAS → NAS → FreeRADIUS pusat
                                             ↓
                             accounting/DAE ↔ platform FTTH
```

Platform tidak meng-host captive portal dan tidak menerima pembayaran. NAS yang memiliki halaman
login dan meneruskan **username + password** voucher ke RADIUS pusat.

### 4.1 Siapkan API dan paket HOTSPOT

Perintah berikut menggunakan admin demo dan hanya membuat data tenant demo. Simpan token di shell
saat ini saja; jangan masukkan token atau password voucher ke source control.

```bash
BASE=http://localhost:8080
TOKEN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"tenantSlug":"demo","email":"admin@demo.ftth","password":"admin12345"}' | jq -r .accessToken)
AUTH="Authorization: Bearer $TOKEN"

PLAN_ID=$(curl -fsS -X POST "$BASE/api/catalog/plans" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"Voucher Lab 1 Jam","description":"Voucher HOTSPOT lab","price":5000,"downMbps":10,"upMbps":5,"serviceTypes":["HOTSPOT"],"active":true}' | jq -r .id)
```

Paket harus `active: true` dan `serviceTypes` harus berisi `HOTSPOT`; selain itu penerbitan voucher
ditolak. Respons paket berisi antara lain `id`, `serviceTypes`, `active`, dan `rateLimit`.

### 4.2 Daftarkan NAS dan site hotspot

Ambil koordinat RADIUS pusat yang harus dipakai NAS, kemudian daftarkan NAS. Pada lab, alamat NAS
adalah IP simulator. `coaSecret` adalah shared secret RADIUS/DAE; ganti dengan secret acak di
lingkungan nyata. Field REST RouterOS tidak dibutuhkan untuk flow RADIUS ini.

```bash
curl -fsS "$BASE/api/bng/radius-endpoint" -H "$AUTH" | jq

NAS_ID=$(curl -fsS -X POST "$BASE/api/bng/nas" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"name":"NAS HOTSPOT Lab","vendor":"OTHER","address":"172.30.0.10","nasIdentifier":"hotspot-lab","coaSecret":"testing123","collectorId":null,"enabled":true,"areaIds":[]}' | jq -r .id)

SITE_ID=$(curl -fsS -X POST "$BASE/api/hotspot/sites" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"nasId\":\"$NAS_ID\",\"name\":\"Hotspot Lab\",\"location\":\"Lab Docker\",\"portalMode\":\"OFF\",\"defaultPlanId\":\"$PLAN_ID\"}" | jq -r .id)
```

`portalMode: "OFF"` sengaja menegaskan bahwa platform tidak menyediakan portal. Konfigurasikan
NAS fisik/virtual menggunakan host/port dari `/api/bng/radius-endpoint`, shared secret yang sama,
dan arahkan captive portal NAS ke halaman miliknya sendiri.

### 4.3 Terbitkan voucher dan catat kredensial sekali saja

```bash
ISSUED=$(curl -fsS -X POST "$BASE/api/hotspot/voucher-batches" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"siteId\":\"$SITE_ID\",\"planId\":\"$PLAN_ID\",\"durationSeconds\":3600,\"quantity\":1}")
echo "$ISSUED" | jq
VOUCHER_ID=$(jq -r '.credentials[0].voucherId' <<<"$ISSUED")
USERNAME=$(jq -r '.credentials[0].username' <<<"$ISSUED")
PASSWORD=$(jq -r '.credentials[0].password' <<<"$ISSUED")
printf 'username=%s password=%s\n' "$USERNAME" "$PASSWORD"
```

Kredensial RADIUS adalah pasangan **username dan password** dari `credentials[]`, bukan username
saja. Password hanya dikembalikan pada respons penerbitan yang ber-header `Cache-Control: no-store`
dan tidak pernah muncul lagi pada detail/daftar voucher; salin ke media aman untuk operator/NAS.
Detail voucher hanya mengembalikan status dan username.

### 4.4 Autentikasi, accounting, dan observasi sesi

Masukkan `$USERNAME` dan `$PASSWORD` pada portal yang disajikan NAS. Untuk voucher valid, NAS harus
menerima **Access-Accept**, mengizinkan sesi, lalu mengirim accounting start/interim. Voucher
menjadi `ACTIVE` saat pertama dipakai, terikat ke perangkat pertama, dan masa berlaku 3.600 detik
dihitung sejak aktivasi.

Amati status voucher dan sesi dari platform:

```bash
curl -fsS "$BASE/api/hotspot/vouchers/$VOUCHER_ID" -H "$AUTH" | jq
# externalId untuk endpoint sesi adalah username voucher.
curl -fsS "$BASE/api/hotspot/vouchers/$USERNAME/session" -H "$AUTH" | jq
```

Respons sesi yang diharapkan memuat `online: true`, `nasId`, `startedAt`, `lastSeenAt`,
`inputBytes`, dan `outputBytes`. Pada `make lab`, simulator memang membuat/memperbarui `radacct`
untuk identitas yang sudah diotorisasi; gunakan ini untuk mengamati accounting dari aplikasi.

### 4.5 Cabut voucher dan buktikan penolakan

```bash
curl -fsS -X POST "$BASE/api/hotspot/vouchers/$VOUCHER_ID/revoke" -H "$AUTH" \
  -H 'Content-Type: application/json' -d '{"reason":"uji pencabutan lab"}' | jq
```

Respons voucher harus memiliki `status: "REVOKED"`, `revokedAt`, dan `revocationReason`.
Platform menghapus otorisasi RADIUS serta meminta Disconnect/DAE bila ada sesi. Coba login lagi
di portal NAS dengan pasangan username/password yang sama: hasil yang benar adalah
**Access-Reject** dan tidak ada sesi baru. Jangan menafsirkan halaman portal sebagai bukti tunggal;
periksa juga status voucher dan `radacct`/endpoint sesi.

> **Batas lab yang diketahui (T8):** simulator pada `make lab` mengemulasi NAS melalui
> `radcheck`/`radacct` dan DAE, tetapi tidak mengekspos endpoint Access-Request RADIUS ke host.
> Selain itu, `seed-lab.sh` tidak dapat membuat voucher yang dapat dipakai ulang karena password
> voucher hanya diserahkan sekali pada respons penerbitan (plaintext credential handoff). Maka
> autentikasi Access-Accept/Access-Reject end-to-end harus dijalankan oleh NAS/RADIUS client nyata
> atau harness yang menerima pasangan itu langsung; jangan menambahkan password ke seed/config.
> Flow API, provisioning RADIUS pusat, observasi accounting, dan revoke/deprovision di atas tetap
> dapat diverifikasi pada lab.

---

## 5. Runbook operator: paket → site → batch → voucher

Bagian ini adalah alur operasional untuk voucher HOTSPOT. Voucher bersifat sementara dan
bukan pelanggan/subscription baru. **Paket Internet** tetap menjadi sumber harga, QoS/FUP,
dan `Mikrotik-Rate-Limit`; modul hotspot mengurus site, voucher, aktivasi, masa berlaku,
dan pencabutan, sedangkan `bng`/RADIUS pusat mengurus NAS, provisioning RADIUS, accounting,
dan Disconnect/CoA.

1. Buat atau pilih **Paket Internet** yang aktif dan `serviceTypes`-nya memuat `HOTSPOT`.
   Paket lain tidak dapat dipakai untuk menerbitkan voucher.
2. Daftarkan NAS di **BRAS & RADIUS**, lalu pastikan NAS aktif dan alamat manajemennya dapat
   dipakai untuk jalur Disconnect/CoA. Ambil host/port RADIUS pusat dari
   `GET /api/bng/radius-endpoint`; jangan menebak host dari URL aplikasi.
3. Buat **hotspot site** yang mengikat tepat satu NAS, tentukan mode portal, dan (bila perlu)
   pilih paket default. `portalId` pada respons site adalah identitas publik acak; jangan
   menggantinya dengan UUID site atau menyusun URL dari parameter bebas.
4. Terbitkan **voucher batch** dengan `siteId`, `planId`, `durationSeconds`, dan `quantity`.
   Simpan pasangan `username`/`password` dari `credentials[]` segera di media operasional
   yang aman. Respons dibuat `no-store`; password tidak dapat diambil kembali dari daftar
   atau detail voucher. Jangan menaruhnya di shell history, source control, seed, atau `.env`.
5. Bagikan kredensial ke pengguna melalui proses operator, lalu amati voucher dan sesi lewat
   `GET /api/hotspot/vouchers/{voucherId}` dan
   `GET /api/hotspot/vouchers/{username}/session`. Voucher berubah `ACTIVE` pada pemakaian
   pertama, terikat ke perangkat pertama, dan `expiresAt` dihitung dari aktivasi.

### Pilih mode portal dengan benar

| Mode | Gunakan ketika | Tanggung jawab NAS/portal | Status rilis ini |
|---|---|---|---|
| `OFF` | Tidak ada captive portal NetOps untuk site tersebut. | Operator mengatur perilaku akses sendiri. | Tidak menerbitkan portal NetOps. |
| `NAS_OWNED` | NAS/MikroTik menyajikan login page sendiri. Ini pilihan operasional yang siap untuk voucher. | NAS mengirim username/password voucher ke RADIUS pusat dan mengirim accounting. | Didukung untuk alur voucher/RADIUS. |
| `NETOPS_HOSTED` | Ingin menyiapkan tampilan portal NetOps dan context yang tervalidasi. | NAS harus mengarahkan browser hanya dengan context yang diterbitkan/ditandatangani platform. | **Belum dapat dipakai untuk login voucher.** T8 (credential handoff ke NAS/RADIUS) masih diblokir. |

`NETOPS_HOSTED` tidak boleh diperlakukan sebagai pengganti `NAS_OWNED`. Portal publik hanya
memuat context bertanda tangan melalui `POST /api/public/hotspot/portal-context/resolve`; ia
tidak mempercayai site, NAS, tujuan redirect, MAC, atau IP dari query bebas. Form login saat ini
dinonaktifkan dan tidak mengirim kredensial, tidak mengaktifkan voucher, serta tidak memberikan
indikasi sukses palsu. Jangan mengarahkan pelanggan produksi ke mode ini sampai handoff T8
tersedia dan telah divalidasi ulang.

### Kontrak MikroTik/NAS untuk mode `NAS_OWNED`

Kontrak yang didukung adalah NAS sebagai RADIUS client: NAS menampilkan portalnya sendiri,
meneruskan **pasangan** username dan password voucher ke FreeRADIUS pusat, menerima
`Access-Accept`/`Access-Reject`, lalu mengirim accounting start dan interim. Untuk MikroTik,
aktifkan RADIUS pada profil hotspot (ganti placeholder dengan profil yang benar):

```rsc
/ip hotspot profile set <profil> use-radius=yes radius-interim-update=5m
```

Konfigurasikan target auth/accounting dan shared secret dari endpoint RADIUS pusat serta data NAS
yang didaftarkan di aplikasi. Untuk production, gunakan IP publik VPS langsung untuk UDP RADIUS,
bukan domain di belakang proxy HTTP; buka dan batasi `1812/udp` dan `1813/udp` hanya dari NAS
seperlunya. Kontrak ini bukan klaim dukungan captive portal eksternal universal-vendor.

Sebelum menjual voucher, buktikan pada NAS nyata bahwa login menghasilkan `Access-Accept`,
accounting muncul, lalu revoke menghasilkan penolakan login baru dan—bila sesi masih ada—
Disconnect/DAE. Lihat [`bras-radius.md`](bras-radius.md) untuk pendaftaran NAS, jaringan, dan
konfigurasi router yang lebih lengkap.

### Kedaluwarsa, revoke, dan rollback operasional

Voucher kedaluwarsa menurut waktu aktivasi/massa berlaku; jangan mencoba memperpanjangnya dengan
mengubah data RADIUS langsung. Untuk menghentikan satu voucher gunakan endpoint revoke pada
bagian 4.5. Revoke menghapus otorisasi RADIUS dan meminta Disconnect/DAE untuk sesi yang ada;
verifikasi status `REVOKED`, `revokedAt`, `revocationReason`, dan accounting/sesi—bukan hanya
halaman captive portal. Riwayat voucher dan audit dipertahankan.

Untuk rollback cepat saat insiden, hentikan penerbitan batch baru dan cabut voucher terdampak.
Jika masalah berada di konfigurasi portal, ubah site kembali ke `NAS_OWNED` atau `OFF`; jangan
menghapus NAS atau data RADIUS secara manual sebagai prosedur rollback. Jika NAS tidak dapat
menerima DAE karena masalah jaringan, revoke tetap mencegah autentikasi baru, tetapi sesi yang
sudah berjalan harus diputus pada NAS setelah konektivitas manajemen pulih.

### Validasi lab dan batas T8

Jalankan `make lab`, lalu ikuti langkah 4.1–4.5 di atas menggunakan NAS simulator dan
`portalMode: "OFF"`/portal milik NAS. `make lab` adalah target **lab/dev saja**, bukan perintah
produksi. Ia memverifikasi API, provisioning RADIUS pusat, accounting yang tersimulasi, observasi
sesi, dan revoke/deprovision. Batas T8 tetap berlaku: simulator tidak mengekspos Access-Request
RADIUS ke host dan seed tidak menyimpan ulang password voucher. Karena itu, verifikasi
Access-Accept/Access-Reject penuh harus memakai NAS/RADIUS client nyata atau harness yang
menerima kredensial sekali-terbit langsung.

### Deploy produksi dan rollback rilis

Deploy otomatis dipicu oleh `git push origin main`; workflow menguji web, membangun image,
mengirimkannya ke GHCR, lalu menjalankan `docker compose pull` dan `up -d` di VPS. Untuk deploy
manual di `/opt/ftth`, gunakan perintah yang sama seperti panduan deploy:

```bash
cd /opt/ftth
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml logs -f server
```

Flyway berjalan saat server mulai. Jangan menghapus volume/database untuk "rollback" hotspot:
migrasi dan data voucher/RADIUS harus dipulihkan hanya melalui prosedur backup/restore yang
berlaku setelah keputusan pemulihan data dibuat.

**Caveat penting:** CI hanya mengirim image, bukan `deploy/docker-compose.prod.yml`, `Caddyfile`,
ataupun `.env`. Bila rilis menyentuh `deploy/` atau membutuhkan variabel baru, salin file compose
terbaru ke `/opt/ftth`, perbarui `.env` dari `.env.example` tanpa memasukkan secret ke Git, lalu
jalankan `up -d`. Konfigurasi produksi yang relevan mencakup `FTTH_SITE_ADDRESS`,
`FTTH_ISOLIR_PORTAL_URL` (untuk redirect walled-garden port 8880), `IMAGE_PREFIX`, `IMAGE_TAG`,
dan kredensial database RADIUS (`RADIUS_DB_NAME`, `RADIUS_DB_USER`, `RADIUS_DB_PASSWORD`).
Gunakan nilai nyata hanya di `.env` VPS. `FTTH_ISOLIR_PORTAL_URL` adalah portal tagihan untuk
pelanggan terisolir, bukan captive portal voucher.

Saat rollback image diperlukan, pilih tag image yang sebelumnya sudah tersedia di GHCR, set
`IMAGE_TAG` pada `.env` VPS ke tag itu, lalu jalankan `docker compose -f docker-compose.prod.yml
up -d` dan pantau log server. Jangan menurunkan image melewati migrasi Flyway yang sudah diterapkan
tanpa rencana kompatibilitas dan pemulihan database yang teruji. Setelah rollback, uji login NAS,
accounting, revoke, dan status container sebelum membuka penerbitan voucher lagi.

---

## 6. Perintah berguna

```bash
# Lihat log satu service
docker compose -f docker-compose.lab.yml logs -f server

# Rebuild setelah ubah kode (server/web)
docker compose -f docker-compose.lab.yml up -d --build server web

# Stop (data tetap ada di volume)
docker compose -f docker-compose.lab.yml down

# Reset TOTAL — hapus volume (DB, MinIO, radius-db) → mulai bersih
docker compose -f docker-compose.lab.yml down -v
```

---

## 6. Troubleshooting

| Gejala | Sebab / solusi |
|---|---|
| `docker compose ... --build` gagal di `--mount=type=cache` | BuildKit belum aktif. Set `DOCKER_BUILDKIT=1` atau pakai Docker terbaru. |
| http://localhost:8080 menolak koneksi | Server belum selesai start / migrasi. Tunggu log `Started FtthApplication`. |
| Login gagal | Pastikan tenant slug `demo`, kredensial `admin@demo.ftth` / `admin12345`. |
| Container `collector` langsung keluar | `FTTH_COLLECTOR_KEY` belum diisi — cek log, jalankan ulang langkah 4b. |
| Port 8080 dipakai proses lain | Ubah mapping `ports: ["8080:80"]` di compose ke port lain. |
