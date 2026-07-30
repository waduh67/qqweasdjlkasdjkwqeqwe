# Deploy ftth ke VPS (Azure Ubuntu 24.04) — Panduan Langkah demi Langkah

Panduan ini nganggep kamu belum pernah deploy web app. Ikutin dari atas ke bawah,
sekali doang setup-nya. Setelah ini, tiap `git push` ke `main` otomatis nge-deploy.

## Gambaran besar (biar ngerti yang lagi kamu bangun)

```
  Laptop kamu ── git push main ──▶ GitHub
                                     │
                                     │  GitHub Actions (robot CI/CD):
                                     │   1. jalanin test
                                     │   2. build 2 image Docker (server + web)
                                     │   3. simpan image di GHCR (gudang image GitHub)
                                     │   4. SSH ke VPS -> tarik image -> nyalain ulang
                                     ▼
                    ┌──────────── VPS Azure (Ubuntu) ────────────┐
   Browser ──443──▶ │ Caddy (HTTPS) ─┬─ /api/* ─▶ server (Spring)│
   pelanggan        │                └─ /*     ─▶ web (Nginx+SPA)│
                    │ Postgres(+PostGIS+Timescale) · MinIO        │
                    └─────────────────────────────────────────────┘
```

Istilah singkat:
- **VPS** = komputer sewaan di cloud yang nyala 24 jam. Punya IP publik.
- **Docker image** = "APK"-nya dunia server: paket berisi aplikasi siap jalan.
- **GHCR** = GitHub Container Registry, tempat nyimpen image (kayak Play Store buat image).
- **Caddy** = penjaga pintu depan; ngatur HTTPS otomatis & ngarahin request ke server/web.
- **Secret** = password/kunci. TIDAK PERNAH masuk ke Git. Disimpan di GitHub Secrets & file `.env` di VPS.

> Collector (agent yang narik data OLT) TIDAK ikut di VPS — itu dipasang di jaringan
> ISP masing-masing. Yang di-cloud cuma server + web + database.

---

## Bagian A — Bikin VPS di Azure

1. Portal Azure → **Create a resource** → **Virtual machine**.
2. Isi:
   - **Image**: Ubuntu Server 24.04 LTS.
   - **Size**: minimal 2 vCPU / 4 GB RAM (mis. `Standard B2s`). Kurang dari ini, build/DB berat.
   - **Authentication**: SSH public key (Azure bisa generate, atau pakai key kamu).
   - **Username**: `azureuser` (default; catat ini).
3. **Networking / NSG (firewall Azure)** — buka **inbound port**:
   - `22` (SSH), `80` (HTTP), `443` (HTTPS). Sisanya biarin ketutup.
   - **Mau pakai fitur VPN** (remote Mikrotik tanpa IP publik)? buka juga port hub
     OpenVPN — default `1194/UDP` (samakan dengan Port/Protokol saat bikin hub di
     dashboard) **dan** rentang port remote Winbox `20000-40000/TCP`. Lihat Bagian I.
4. Create. Setelah jadi, catat **Public IP** VM-nya (mis. `20.11.22.33`).
5. Coba SSH dari laptop:
   ```bash
   ssh azureuser@20.11.22.33
   ```
   Kalau masuk, lanjut.

---

## Bagian B — Pasang Docker di VPS

Jalankan ini **di dalam VPS** (setelah SSH masuk):

```bash
# Update + tools dasar
sudo apt-get update && sudo apt-get install -y ca-certificates curl git

# Pasang Docker Engine + plugin compose (cara resmi Docker)
curl -fsSL https://get.docker.com | sudo sh

# Biar bisa jalanin docker tanpa sudo (logout-login lagi setelah ini)
sudo usermod -aG docker $USER
```

Keluar (`exit`) lalu SSH lagi, cek:

```bash
docker version
docker compose version
```

---

## Bagian C — Siapkan folder deploy di VPS

```bash
sudo mkdir -p /opt/ftth
sudo chown $USER:$USER /opt/ftth
cd /opt/ftth
```

Salin 3 hal dari repo ke folder ini: `docker-compose.prod.yml`, `Caddyfile`, folder
`postgres-init/`. Cara paling gampang — dari **laptop** (bukan VPS), di root repo:

```bash
scp deploy/docker-compose.prod.yml deploy/Caddyfile azureuser@20.11.22.33:/opt/ftth/
scp -r deploy/postgres-init deploy/radius azureuser@20.11.22.33:/opt/ftth/
```

> Folder `radius/` berisi skema DB FreeRADIUS + config server RADIUS yang
> di-mount stack (lihat **Bagian K**). Kalau nanti file ini berubah, ulang `scp`-nya.

> Kalau nanti file infra ini berubah, ulang `scp`-nya. Image aplikasi mah otomatis
> ke-update lewat CI, tapi file compose/Caddy disalin manual.

---

## Bagian D — Isi file `.env` di VPS (tempat semua secret)

Di VPS, di `/opt/ftth`, bikin `.env` dari contoh:

```bash
# ambil template dari repo (atau tulis manual pakai nano)
curl -fsSL -o .env.example https://raw.githubusercontent.com/fajarxfce/ftth/main/deploy/.env.example
cp .env.example .env
nano .env
```

Isi tiap baris. Untuk secret acak yang kuat, jalankan di VPS lalu tempel hasilnya:

```bash
openssl rand -base64 48    # jalankan beberapa kali untuk JWT, ENCRYPTION, password DB, dll.
```

Yang WAJIB kamu ganti di `.env`:
- `FTTH_SITE_ADDRESS` → domain kamu (mis. `app.contoh.com`) **atau** `:80` kalau belum punya domain.
- `IMAGE_PREFIX` → `ghcr.io/<username-github-kamu>` (mis. `ghcr.io/fajarxfce`).
- `FTTH_DB_PASSWORD`, `POSTGRES_SUPER_PASSWORD` → password kuat (huruf+angka aja).
- `FTTH_JWT_SECRET`, `FTTH_ENCRYPTION_SECRET` → dua hasil `openssl` yang BERBEDA.
- `FTTH_PLATFORM_ADMIN_EMAIL` / `FTTH_PLATFORM_ADMIN_PASSWORD` → akun login pertamamu.
- `FTTH_S3_SECRET_KEY` → password MinIO (min. 8 karakter).
- `FTTH_CORS_ORIGINS` → `https://domainkamu` (atau `http://<IP>` kalau mode `:80`).

> `.env` ini cuma ada di VPS dan tidak pernah masuk Git. Jaga baik-baik.

---

## Bagian E — Domain & HTTPS (boleh dilewati dulu)

**Punya domain?** Di panel DNS domain kamu, bikin **A record**:
`app.contoh.com  →  20.11.22.33` (IP VPS). Tunggu beberapa menit sampai nyambung.
Caddy bakal otomatis bikin sertifikat HTTPS begitu stack nyala. Pastikan
`FTTH_SITE_ADDRESS=app.contoh.com` di `.env`.

**Belum punya domain?** Set `FTTH_SITE_ADDRESS=:80` di `.env`. Nanti akses lewat
`http://20.11.22.33` (tanpa gembok HTTPS). Bisa ganti ke domain kapan aja: edit
`.env`, lalu `docker compose -f docker-compose.prod.yml up -d`.

---

## Bagian F — Daftarkan GitHub Secrets (kunci buat robot CI/CD)

Di GitHub: repo **ftth** → **Settings** → **Secrets and variables** → **Actions** →
**New repository secret**. Bikin 5 secret ini:

| Nama secret | Isinya |
|---|---|
| `VPS_HOST` | IP publik VPS, mis. `20.11.22.33` |
| `VPS_USER` | user SSH, mis. `azureuser` |
| `VPS_SSH_KEY` | **private key** SSH buat masuk VPS (lihat di bawah) |
| `GHCR_USER` | username GitHub kamu, mis. `fajarxfce` |
| `GHCR_PAT` | Personal Access Token buat narik image (lihat di bawah) |

### Bikin SSH key khusus buat robot deploy

Di **laptop**:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/ftth_deploy -N ""
# tampilkan public key, lalu tambahkan ke VPS:
ssh-copy-id -i ~/.ssh/ftth_deploy.pub azureuser@20.11.22.33
# tampilkan PRIVATE key -> salin SELURUHnya jadi isi secret VPS_SSH_KEY:
cat ~/.ssh/ftth_deploy
```

`VPS_SSH_KEY` = isi file `ftth_deploy` (private, termasuk baris `-----BEGIN...` s/d `-----END...`).

### Bikin GHCR_PAT (biar VPS boleh narik image privat)

GitHub → foto profil → **Settings** → **Developer settings** →
**Personal access tokens** → **Tokens (classic)** → **Generate new token (classic)** →
centang scope **`read:packages`** → generate → salin token → jadikan isi `GHCR_PAT`.

---

## Bagian G — Deploy pertama

Tinggal push. Dari laptop, di repo:

```bash
git push origin main
```

Buka GitHub → tab **Actions** → lihat workflow **deploy** jalan (test → build → deploy).
Sekitar 5–10 menit pertama (build image dari nol). Kalau semua hijau, selesai.

**Mau nyalain manual dulu tanpa push?** Bisa juga langsung di VPS setelah image ke-build
minimal sekali (push dulu biar image ada di GHCR), lalu di VPS:

```bash
cd /opt/ftth
echo "<GHCR_PAT>" | docker login ghcr.io -u <username-github> --password-stdin
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

---

## Bagian H — Cek hasilnya

- Buka `https://app.contoh.com` (atau `http://<IP>`). Harusnya muncul halaman login.
- Login pakai `FTTH_PLATFORM_ADMIN_EMAIL` + `FTTH_PLATFORM_ADMIN_PASSWORD` dari `.env`.
- API/dokumen: `https://app.contoh.com/swagger-ui`.

Kalau blank/error, lihat log (di VPS):

```bash
cd /opt/ftth
docker compose -f docker-compose.prod.yml ps          # status semua container
docker compose -f docker-compose.prod.yml logs server  # log backend
docker compose -f docker-compose.prod.yml logs caddy   # log proxy/HTTPS
```

---

## Bagian I — (Opsional) Aktifkan fitur VPN (remote Mikrotik tanpa IP publik)

Fitur ini menjadikan **VPS ini sekaligus hub OpenVPN**. Tenant tinggal klik "Generate
akun VPN" di dashboard → dapat `ip:port` + user/pass → tempel di Mikrotik. Hub-nya
dikelola HANYA oleh admin platform.

1. **Buka port di NSG Azure** — tambah inbound rule `1194/UDP` (atau port/protokol
   yang kamu pilih saat bikin hub) **plus** rentang `20000-40000/TCP` untuk remote Winbox
   (tiap akun VPN dapat satu port unik di rentang ini; sesuaikan bila kamu ubah
   `FTTH_VPN_REMOTE_PORT_MIN/MAX`).

2. **Kasih tahu aplikasi URL publiknya** — di `/opt/ftth/.env`, tambah baris:
   ```bash
   FTTH_VPN_PUBLIC_BASE_URL=https://app.contoh.com   # samakan dgn domain aplikasi
   ```
   Lalu **salin ulang compose terbaru** dari repo (karena file compose di-copy manual):
   ```bash
   # dari laptop, di root repo
   scp deploy/docker-compose.prod.yml azureuser@20.11.22.33:/opt/ftth/
   ```
   Di VPS terapkan:
   ```bash
   cd /opt/ftth && docker compose -f docker-compose.prod.yml up -d
   ```

3. **Bikin hub di dashboard** — login sebagai admin platform → menu **Server VPN** →
   isi Host = **IP publik VPS (mentah, bukan domain di balik Cloudflare)**, Port `1194`,
   Protokol UDP, Subnet overlay mis. `10.8.0.0/24` → simpan. Aplikasi menerbitkan CA +
   sertifikat server otomatis dan menampilkan **perintah pasang satu-baris (sekali tampil)**.

4. **Jalankan perintah pasang itu di VPS** (bukan di laptop). Installer memasang OpenVPN
   + skrip callback, lalu `systemctl enable --now openvpn-server@server`. Verifikasi:
   ```bash
   sudo systemctl status openvpn-server@server   # harus active (running)
   sudo ls -la /etc/openvpn/server/              # harus terisi ca/server.crt/conf/skrip
   ```

5. **Tenant generate akun & pasang di Mikrotik** — dari akun tenant, menu **Akun VPN** →
   Generate → salin kredensial → tempel di Mikrotik (unduh skrip RouterOS bila perlu).
   Kartu kredensial memuat **Winbox (remote)** = `IP_VPS:port` — begitu Mikrotik terhubung
   ke hub, buka alamat itu di Winbox/browser untuk meremote perangkatnya langsung, tanpa
   ikut men-dial tunnel. Hub men-DNAT `IP_VPS:port` → `overlay:8291` otomatis.

> **Sudah pernah pasang hub sebelum fitur remote-port ini?** Jalankan ulang perintah pasang
> hub dari dashboard (menu Server VPN → salin ulang perintah / rotasi token) agar skrip
> `ftth-connect.sh`/`ftth-disconnect.sh` + aturan iptables baru ikut terpasang. Akun lama
> otomatis dapat port remote lewat migrasi DB; Mikrotik-nya cukup reconnect ke hub.

> Domain di balik Cloudflare (orange-cloud) hanya mem-proxy HTTP/HTTPS, **bukan** UDP
> 1194 maupun rentang TCP Winbox — makanya Host hub wajib IP publik VPS mentah. Callback
> aplikasi (lewat HTTPS domain) tetap jalan normal.

---

## Bagian J — Deploy manual (kalau GitHub Actions kena limit)

Kuota **GitHub Actions** (menit compute buat test + build image) bisa habis. Kalau
itu terjadi, `git push main` **tidak lagi otomatis nge-deploy**. Tenang — yang habis
cuma robotnya; **GHCR (gudang image) & VPS tetap jalan normal**. Jadi kita tinggal
kerjain manual apa yang tadinya dikerjain robot: **build image di laptop → dorong ke
GHCR → VPS narik**. Alur di VPS (compose, `.env`, nama image) tidak berubah sama sekali.

### Prasyarat (sekali)

- **PAT `write:packages`.** Yang di VPS (`GHCR_PAT`) cuma `read:packages` — itu buat
  narik. Buat push dari laptop butuh scope **`write:packages`**. Bikin di GitHub →
  Settings → Developer settings → Tokens (classic) → centang `write:packages`
  (+ `read:packages`). Akun kamu juga harus punya akses tulis ke package org (kalau
  `IMAGE_PREFIX` mengarah ke org, mis. `ghcr.io/karuhun-developer`).
- **Samakan `IMAGE_PREFIX`.** Tag yang kamu build WAJIB sama dengan yang di VPS:
  ```bash
  grep IMAGE_PREFIX /opt/ftth/.env      # jalankan di VPS, catat nilainya
  ```

### Langkah 1 — di LAPTOP (build + push)

```bash
cd <root-repo-ftth>

# login GHCR pakai PAT ber-scope write:packages
echo '<PAT-write-packages>' | docker login ghcr.io -u <username-github> --password-stdin

# SAMAKAN dengan IMAGE_PREFIX di /opt/ftth/.env
PREFIX=ghcr.io/karuhun-developer

# gerbang test (gantiin job "test" di CI)
./gradlew :server:test

# build 2 image — context & Dockerfile persis seperti CI
docker build -f server/Dockerfile -t $PREFIX/ftth-server:latest .
docker build -f web/Dockerfile    -t $PREFIX/ftth-web:latest    ./web

# dorong ke GHCR
docker push $PREFIX/ftth-server:latest
docker push $PREFIX/ftth-web:latest
```

> Laptop x86_64 → VPS Azure amd64: arch cocok, aman. Kalau build dari mesin ARM,
> tambahkan `--platform linux/amd64` di tiap `docker build`.

### Langkah 2 — di VPS (deploy = ini yang bikin live)

```bash
cd /opt/ftth
echo '<PAT-read-packages>' | docker login ghcr.io -u <username-github> --password-stdin
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
docker image prune -f
docker compose -f docker-compose.prod.yml logs -f server   # pantau migrasi + startup
```

Migrasi DB (Flyway) jalan **otomatis** saat server start — tidak ada langkah DB manual.

### Alternatif tanpa registry — `docker save` → `scp` → `load`

Kalau ogah urus GHCR: bungkus image jadi file, kirim langsung ke VPS.

```bash
# LAPTOP (image di-tag dengan prefix yang sama supaya compose langsung nemu)
PREFIX=ghcr.io/karuhun-developer
docker build -f server/Dockerfile -t $PREFIX/ftth-server:latest .
docker build -f web/Dockerfile    -t $PREFIX/ftth-web:latest    ./web
docker save $PREFIX/ftth-server:latest $PREFIX/ftth-web:latest | gzip | \
  ssh <user>@<ip-vps> 'gunzip | docker load'

# VPS (image sudah ke-load, tak perlu pull)
cd /opt/ftth && docker compose -f docker-compose.prod.yml up -d
```

Trade-off: transfer tiap deploy gede (server ~300–500 MB). Lebih lambat dari GHCR,
tapi nol-registry.

### Troubleshooting login/pull GHCR

| Gejala | Sebab & solusi |
|---|---|
| `docker login` → `denied: denied` | Kamu menempel placeholder `<PAT-...>` mentah, bukan token asli. Ganti dengan token beneran (biasanya diawali `ghp_`). |
| `pull` → `403 Forbidden ... manifests/latest` | Image belum pernah dipush ke namespace itu (mis. repo baru pindah org, CI tak sempat jalan) **atau** akun tak punya akses baca package org. Build+push dari laptop dulu (Langkah 1). Kalau tetap 403 setelah kepush → atur akses di `github.com/orgs/<org>/packages` → package → *Package settings* → beri akses / set visibility. |
| `pull` → `not found` | `IMAGE_PREFIX` di `.env` beda dengan tag yang kamu push. Samakan. |

### Balik ke CI otomatis (jangka panjang)

- **Self-hosted runner** — daftarkan VPS/laptop sebagai runner GitHub → menit Actions
  jadi tak terbatas & gratis. Paling worth kalau sering deploy.
- Atau tetap manual seperti di atas — tak butuh Actions sama sekali.

---

## Bagian K — Server RADIUS (FreeRADIUS) di stack — RADIUS-as-a-service

Stack prod sudah menyertakan **FreeRADIUS + Postgres RADIUS** (service `freeradius`
& `radius-db`) — jalan otomatis bareng yang lain begitu `docker compose up -d`. Ini
server RADIUS **beneran** yang platform sediakan sebagai layanan: Mikrotik tiap tenant
auth PPPoE ke sini, accounting masuk `radacct`, dan **server aplikasi** yang menulis
otorisasi (password + kecepatan), membaca sesi, dan mendaftarkan klien BRAS.

**Yang berubah dari model lama:** tak ada lagi client BRAS di `.env` maupun URL JDBC di
form. Tenant **daftarkan BRAS sendiri di UI** (nama, IP, shared secret) → server menulis
baris tabel `nas` → FreeRADIUS memuat klien dari situ (`read_clients=yes`). Username PPPoE
di-key `{kodeTenant}:{username}` otomatis (`sql_user_name`), jadi username boleh kembar
antar-tenant. Server terhubung ke `radius-db` internal lewat env (`FTTH_RADIUS_DB_*`,
sudah di-wire otomatis di compose dari blok `RADIUS_DB_*`).

### K.1 Isi `.env` + buka firewall

Di `/opt/ftth/.env` cukup isi **satu** rahasia RADIUS: `RADIUS_DB_PASSWORD` (lihat
`.env.example`; `RADIUS_DB_NAME`/`RADIUS_DB_USER` biarkan default `radius`). Tak ada
lagi `RADIUS_CLIENT_*` — klien BRAS datang dari UI.

Buka di NSG/firewall VPS, **batasi ke IP Mikrotik tenant**:
- `1812/udp` (auth) · `1813/udp` (accounting)

Terapkan:
```bash
cd /opt/ftth
docker compose -f docker-compose.prod.yml up -d radius-db freeradius
docker compose -f docker-compose.prod.yml logs -f freeradius   # -X: tiap auth kelihatan
```

### K.2 Daftarkan BRAS di UI app **dulu** (form Tambah BRAS)

Klien BRAS dibaca dari tabel `nas`, jadi **daftarkan di UI sebelum** Mikrotik menembak —
kalau tidak, FreeRADIUS menolak request dari IP yang tak dikenalnya.

| Field | Isi |
|---|---|
| Nama | `BRAS Produksi` (bebas) |
| Vendor | `MIKROTIK` |
| Alamat manajemen | **IP Mikrotik** (jadi `nasname` klien **dan** sasaran CoA/Disconnect :3799) |
| NAS-Identifier | opsional; identitas NAS = `radacct.nasidentifier` |
| Secret CoA | **shared secret BRAS ini** — dipakai ganda: secret klien RADIUS (auth/acct) **dan** secret DAE (:3799). Simpan; nanti diketik sama di Mikrotik. |
| Kredensial REST API | (opsional) untuk kontrol sesi via collector on-prem RouterOS v7 |
| Aktif | ✅ |

> Begitu disimpan, server menulis baris `nas` (`nasname`=IP, `secret`=Secret CoA,
> `shortname`=kode tenant). Cek: `docker compose -f docker-compose.prod.yml exec
> radius-db psql -U radius -d radius -c "SELECT nasname,shortname FROM nas;"`.

> **Reload setelah tambah/ubah BRAS.** `read_clients=yes` memuat daftar klien saat
> FreeRADIUS **start**, jadi BRAS yang baru didaftarkan belum dikenali sampai di-reload:
> `docker compose -f docker-compose.prod.yml restart freeradius`. Dampaknya kecil — sesi
> PPPoE yang sedang hidup ada di Mikrotik (bukan di FreeRADIUS), reload cuma menjeda
> auth/acct **request baru** beberapa detik. (Klien dinamis tanpa-reload = penyempurnaan
> lanjutan; belum di stack ini.)

### K.3 Arahkan Mikrotik ke RADIUS ini (RouterOS v7)

Secret di bawah = **shared secret** yang kamu isi di UI untuk BRAS ini (identik dua sisi).

```rsc
# <IP-VPS> = IP publik VPS; <SECRET-BRAS> = shared secret dari form UI
/radius add service=ppp address=<IP-VPS> secret=<SECRET-BRAS> \
    authentication-port=1812 accounting-port=1813
/radius incoming set accept=yes port=3799
/ppp aaa set use-radius=yes accounting=yes interim-update=5m
```

> **Muncul otomatis di UI tenant.** Set `FTTH_RADIUS_PUBLIC_HOST` di `.env` (= IP publik
> VPS ini) → halaman **BRAS & RADIUS** menampilkan kartu "Arahkan router ke RADIUS ini"
> berisi host + port + skrip di atas **siap-salin**, dengan secret terisi otomatis saat
> operator menekan **Generate**. Tanpa env itu, kartu menandai "belum dikonfigurasi" dan
> skrip memakai placeholder `<IP-RADIUS>` (auth tetap jalan; hanya panduan yang kosong).

### K.4 Provisi akun PPPoE lewat app (bukan SQL manual)

Cara benar: buat pelanggan + langganan (pilih Paket) → provisi akun PPPoE di tab **Akses**.
Server menulis `radcheck`/`radusergroup` dengan kunci `{kodeTenant}:{username}` yang cocok
dengan `sql_user_name`. Dial akun itu dari klien PPPoE → log `freeradius` menampilkan
`Access-Accept` dan `radacct` dapat baris sesi baru.

> Mau tes mentah tanpa app? Ingat prefiks tenant: username di `radcheck` **wajib**
> `{kodeTenant}:{username}` (mis. `acme:test@isp.net`), sedangkan CPE tetap mengetik
> `test@isp.net` saja. Tanpa prefiks yang benar, auth NAK.

### K.5 Catatan arah koneksi (penting)

- **Auth/acct**: Mikrotik → VPS (1812/1813). Selama Mikrotik bisa keluar ke internet, jalan
  tanpa VPN — walau Mikrotik di belakang NAT penuh.
- **CoA/Disconnect**: **SERVER → Mikrotik** :3799 (jalur-tulis DAE server-side). Server harus
  bisa **menjangkau balik** alamat manajemen BRAS. Tiga jalur (per-NAS): IP publik → tembak
  langsung (buka 3799/udp di Mikrotik ke IP VPS); di balik NAT + join **VPN hub** (Bagian I)
  → lewat overlay; tak terjangkau → degradasi anggun (perubahan berlaku saat login ulang).
- **Server → radius-db**: internal compose (`radius-db:5432`), otomatis lewat `FTTH_RADIUS_DB_*`.
  Tak ada port DB yang perlu dibuka ke luar.

---

## Operasional harian

| Mau apa | Perintah (di `/opt/ftth` pada VPS) |
|---|---|
| Update ke versi terbaru | cukup `git push main` dari laptop — otomatis (kalau Actions limit → **Bagian J**) |
| Restart semua | `docker compose -f docker-compose.prod.yml restart` |
| Lihat log realtime | `docker compose -f docker-compose.prod.yml logs -f server` |
| Backup database | `docker compose -f docker-compose.prod.yml exec postgres pg_dump -U postgres ftth > backup_$(date +%F).sql` |
| Matikan sementara | `docker compose -f docker-compose.prod.yml down` (data aman di volume) |

---

## Catatan penting

- **Ganti `FTTH_ENCRYPTION_SECRET` = kredensial SNMP lama tak terbaca.** Set sekali, jangan diubah.
- **`FTTH_SEED_DEMO=false` di produksi** — biar tenant demo gak kebawa.
- **Redis & RabbitMQ tidak dipakai** versi ini (belum ada di kode), makanya gak ada di stack.
- **GenieACS (fitur CPE/TR-069) belum termasuk** di deploy ini. Selama belum dipasang,
  akan ada warning sinkronisasi CPE di log — aman diabaikan. Nanti ditambah terpisah.
- **Test job di CI** butuh Postgres+Timescale; kalau rewel, bisa longgarin dengan hapus
  `needs: test` di job `build-and-push` (`.github/workflows/deploy.yml`).
