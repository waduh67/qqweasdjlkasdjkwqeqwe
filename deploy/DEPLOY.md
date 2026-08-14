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
   operator         │                └─ /*     ─▶ web (Nginx+SPA)│
                    │ Postgres(+PostGIS+Timescale) · MinIO        │
   Mikrotik ─1812─▶ │ FreeRADIUS + radius-db                      │
   ONT plgn ─7547─▶ │ GenieACS (cwmp/nbi/fs) + Mongo              │
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
     Stack lengkap (termasuk GenieACS + Mongo di Bagian L) makan ~3 GB; 4 GB cukup tapi
     pas-pasan — kalau mau lega, ambil 8 GB.
   - **Authentication**: SSH public key (Azure bisa generate, atau pakai key kamu).
   - **Username**: `azureuser` (default; catat ini).
3. **Networking / NSG (firewall Azure)** — buka **inbound port**:
   - `22` (SSH), `80` (HTTP), `443` (HTTPS). Sisanya biarin ketutup.
   - **Mau pakai fitur VPN** (remote Mikrotik tanpa IP publik)? buka juga port hub
     OpenVPN — default `1194/TCP` (samakan dengan Port/Protokol saat bikin hub di
     dashboard) **dan** rentang port remote `20000-40000/TCP` (Winbox/API/SSH tiap akun,
     satu port publik per pintu). Lihat Bagian I.
     TCP, bukan UDP: klien OpenVPN RouterOS v6 tak mengenal UDP.
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

1. **Buka port di NSG Azure** — tambah inbound rule `1194/TCP` (atau port/protokol
   yang kamu pilih saat bikin hub) **plus** rentang `20000-40000/TCP` untuk port remote
   (tiap PINTU akun VPN — Winbox, API, SSH, … — dapat satu port unik di rentang ini;
   sesuaikan bila kamu ubah `FTTH_VPN_REMOTE_PORT_MIN/MAX`). Tambah juga rentang yang
   sama `/UDP` bila kamu memakai pintu UDP seperti SNMP.

   > **Kenapa TCP, bukan UDP?** Klien OpenVPN RouterOS **v6 hanya bisa TCP**, dan perangkat
   > v6 tak bisa di-upgrade (hAP lite/RB941 ber-CPU smips tak akan pernah dapat RouterOS 7).
   > Hub UDP menutup pintu buat seluruh armada itu — interfacenya diam di "connecting..."
   > tanpa pesan apa pun. Isi terowongan ini trafik manajemen bervolume kecil, jadi ongkos
   > TCP tak terasa. Pilih UDP hanya bila SEMUA perangkat dipastikan v7.
   >
   > Lewat Azure CLI: `az network nsg rule create -g <rg> --nsg-name <nsg> -n ovpn-tcp \
   > --priority 1100 --access Allow --protocol Tcp --destination-port-ranges 1194`

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
   Protokol TCP, Subnet overlay mis. `10.8.0.0/24` → simpan. Aplikasi menerbitkan CA +
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

6. **Port perangkatnya bukan bawaan? Butuh API/SSH juga?** Di menu **Akun VPN**, aksi
   baris **Port remote** membuka daftar pintu akun itu: ubah "Port di perangkat" bila
   Winbox digeser (mis. ke 9291), atau tambah pintu baru (API 8728, SSH 22, WebFig,
   SNMP/UDP, …). Alamat publiknya sengaja **tak ikut berubah**. VPS menyelaraskan
   iptables tiap menit lewat timer `ftth-vpn-sync`, jadi perubahan berlaku sendiri
   tanpa perangkat perlu reconnect:
   ```bash
   sudo systemctl status ftth-vpn-sync.timer     # harus active (waiting)
   sudo iptables -t nat -S PREROUTING | grep ftth-vpn
   ```

> **Sudah pernah pasang hub sebelum fitur port remote ini?** Jalankan ulang perintah pasang
> hub dari dashboard (menu Server VPN → salin ulang perintah / rotasi token) agar skrip
> `ftth-sync.sh` + timer `ftth-vpn-sync` ikut terpasang — tanpa itu, penerusan port yang
> kamu ubah di dashboard tak pernah sampai ke iptables VPS. Installer sekaligus menyapu
> aturan DNAT lama yang tak bertanda (aturan lawas duduk lebih awal di `PREROUTING` dan
> akan mengalahkan yang baru). Akun lama otomatis dapat pintu Winbox lewat migrasi DB.

> Domain di balik Cloudflare (orange-cloud) hanya mem-proxy HTTP/HTTPS, **bukan** port
> 1194 maupun rentang TCP port remote — makanya Host hub wajib IP publik VPS mentah. Callback
> aplikasi (lewat HTTPS domain) tetap jalan normal.

---

## Pembayaran (gateway Pivot)

Penagihan langganan (dan tagihan pelanggan) memakai gateway **Pivot** mode REDIRECT: pelanggan
diarahkan ke halaman bayar Pivot, lalu **dikembalikan** ke aplikasi di `<base>/paid`,
`<base>/failed`, atau `<base>/expired`. URL balik itu WAJIB, jadi aplikasi harus tahu URL
publiknya.

1. **Set URL publik aplikasi** — di `/opt/ftth/.env`, tambah baris:
   ```bash
   FTTH_BILLING_PIVOT_REDIRECT_BASE_URL=https://app.contoh.com   # samakan dgn domain aplikasi
   ```
   Kosong/tidak diisi → charge Pivot **gagal** dan tautan bayar tak terbit — di UI muncul
   *"Tagihan terbit. Tautan bayar belum siap — hubungi admin platform."* (tagihannya sendiri
   tetap terbit, hanya tanpa link bayar).

2. **Salin ulang compose terbaru** (file compose di-copy manual) lalu terapkan:
   ```bash
   scp deploy/docker-compose.prod.yml <user>@<vps>:/opt/ftth/     # dari laptop, root repo
   cd /opt/ftth && docker compose -f docker-compose.prod.yml up -d
   ```

3. **Isi kredensial master Pivot** di dashboard admin platform (Merchant ID / Secret / Callback
   API Key). Halaman balik `/paid`, `/failed`, `/expired` dilayani SPA — tak perlu konfigurasi
   Caddy tambahan (semua non-`/api` sudah diarahkan ke web).

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

### K.5 Skema radius-db yang sudah terlanjur jalan (grafik trafik kosong / poller error)

`radius/initdb/01-schema.sql` hanya dijalankan Postgres saat **volume masih kosong**.
Stack yang sudah berjalan sejak sebelum sebuah kolom ada tak pernah mendapatnya — dan
gejalanya bukan error di layar melainkan `RadiusAccountingPoller` gagal tiap 30 detik di
log server (`column "acctinputgigawords" does not exist`), grafik trafik pelanggan mati,
FUP tak pernah menghitung. Berkas skema ditulis idempoten, jadi cukup jalankan ulang:

```bash
cd /opt/ftth
docker compose -f docker-compose.prod.yml exec -T radius-db \
  psql -U radius -d radius < radius/initdb/01-schema.sql
```

Aman diulang: semua `CREATE ... IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`. Lakukan tiap
kali menarik versi baru yang menyentuh berkas itu.

### K.6 Catatan arah koneksi (penting)

- **Auth/acct**: Mikrotik → VPS (1812/1813). Selama Mikrotik bisa keluar ke internet, jalan
  tanpa VPN — walau Mikrotik di belakang NAT penuh.
- **CoA/Disconnect**: **SERVER → Mikrotik** :3799 (jalur-tulis DAE server-side). Server harus
  bisa **menjangkau balik** alamat manajemen BRAS. Tiga jalur (per-NAS): IP publik → tembak
  langsung (buka 3799/udp di Mikrotik ke IP VPS); di balik NAT + join **VPN hub** (Bagian I)
  → lewat overlay; tak terjangkau → degradasi anggun (perubahan berlaku saat login ulang).
- **Server → radius-db**: internal compose (`radius-db:5432`), otomatis lewat `FTTH_RADIUS_DB_*`.
  Tak ada port DB yang perlu dibuka ke luar.

---

## Bagian L — CPE / TR-069 (GenieACS) di stack

Stack prod sekarang **sudah menyertakan GenieACS** — ACS (Auto Configuration Server)
yang dipakai modul `cpe` untuk mengelola router/ONT pelanggan: lihat status, ubah WiFi,
reboot, factory reset, diagnostik Ping/Speed, dan upgrade firmware. Sebelumnya bagian
ini absen, jadi menu CPE selalu kosong walau kodenya sudah lengkap.

Empat container baru naik otomatis: `genieacs-mongo` (basis datanya sendiri) plus tiga
proses GenieACS dari satu image — `genieacs-cwmp`, `genieacs-nbi`, `genieacs-fs`.

### L.1 Siapa menghubungi siapa (ini yang menentukan port)

```
  ONT/router pelanggan ──7547──▶ genieacs-cwmp   (Inform berkala + connection request)
  ONT/router pelanggan ──7567──▶ genieacs-fs     (mengunduh berkas firmware)
  server (aplikasi)    ──7557──▶ genieacs-nbi    (INTERNAL — perintah dari dashboard)
```

- **7547 & 7567 wajib terbuka** di NSG/firewall VPS. Perangkat pelanggan yang menelepon
  masuk; kalau ketutup, tak akan pernah ada satu pun CPE muncul di dashboard.
- **7557 (NBI) HARAM dibuka.** Itu API admin **tanpa otentikasi sama sekali** — siapa pun
  yang bisa menjangkaunya boleh me-reboot dan mengubah konfigurasi seluruh router
  pelanggan semua tenant. Di compose ia sengaja tak punya `ports:`, jadi cuma hidup di
  jaringan internal. Jangan pernah ditambahkan.

> **Batasi 7547/7567 kalau bisa.** Idealnya rentang IP jaringan akses pelangganmu saja,
> bukan `0.0.0.0/0`. CWMP polos memang lazim di ISP, tapi makin sempit makin baik.

### L.2 Langkah pasang

1. **Buka port di NSG Azure** — inbound `7547/TCP` dan (kalau mau fitur upgrade firmware)
   `7567/TCP`.

2. **Isi `.env`** di `/opt/ftth` — lihat blok "CPE / TR-069" di `.env.example`. Yang
   penting satu:
   ```bash
   FTTH_CPE_PUBLIC_HOST=20.11.22.33   # IP/host publik VPS ini
   ```
   Dipakai merakit URL unduh firmware yang dikirim ke perangkat
   (`http://<host>:7567/<berkas>`). Kosong → URL memakai hostname container yang tak
   berarti apa-apa bagi ONT, jadi **upgrade firmware gagal**. Fitur lain (WiFi, reboot,
   diagnostik) tetap jalan tanpa ini.

3. **Salin ulang compose + naikkan** (file compose disalin manual, ingat):
   ```bash
   scp deploy/docker-compose.prod.yml <user>@<vps>:/opt/ftth/     # dari laptop, root repo
   cd /opt/ftth
   docker compose -f docker-compose.prod.yml pull
   docker compose -f docker-compose.prod.yml up -d
   ```

4. **Cek naik semua**:
   ```bash
   docker compose -f docker-compose.prod.yml ps genieacs-mongo genieacs-cwmp genieacs-nbi genieacs-fs
   docker compose -f docker-compose.prod.yml logs server | grep -i acs   # harus sepi
   ```
   Kalau ACS mati, server cuma menulis satu baris `WARN Tak bisa menarik daftar device
   dari ACS` tiap ronde sinkron — tidak mengganggu fitur lain.

### L.3 Arahkan ONT pelanggan ke ACS ini

Di sisi ONT/router (atau lewat template konfigurasi OLT/vendor), set alamat ACS:

| Parameter TR-069 | Isi |
|---|---|
| ACS URL | `http://<IP-VPS>:7547/` |
| ACS Username / Password | `FTTH_CPE_ONT_ACS_USERNAME` / `..._PASSWORD` (kosongkan kalau tak diisi) |
| Periodic Inform | aktif, interval `300` detik |
| Connection Request Username / Password | `FTTH_CPE_ONT_CR_USERNAME` / `..._PASSWORD` |

**Nilai persisnya tak perlu dihafal atau dikirim lewat chat**: aplikasi memajangnya di
menu **ACS / TR-069** (kartu "Setelan ONT") dan di tab **Ringkasan** detail pelanggan,
lengkap dengan tombol "Salin semua". Yang dipajang di sana adalah env di atas apa adanya,
jadi isi `.env` dulu baru suruh teknisi menyalin — kalau `FTTH_CPE_PUBLIC_HOST` kosong,
kartunya menandai "belum dikonfigurasi" alih-alih memajang URL yang salah.

Interval `300` bukan angka bebas: bawaan pabrik kebanyakan ONT adalah `3600`, dan dengan
itu perangkat akan tampak "offline" di konsol selama berjam-jam meski jaringannya sehat
(ambang basi bawaan `FTTH_CPE_ONLINE_STALE_AFTER` = 15 menit).

> Kredensial ONT di atas terlihat oleh **setiap** pengguna yang punya izin `cpe.acs.view`
> — termasuk role Teknisi. Itu disengaja (nilainya global, bukan rahasia per-pelanggan),
> tapi jangan pakai password yang sama dengan apa pun yang lain.

Setelah Inform pertama masuk, perangkat muncul di GenieACS. **Penautan ke pelanggan
memakai kecocokan serial**: `CpeSyncScheduler` mencocokkan `Device.DeviceInfo.SerialNumber`
dengan `serialNumber` ONU pelanggan di aplikasi. Kalau serialnya beda, perangkatnya
tercatat di ACS tapi tak nempel ke pelanggan mana pun. Sinkron jalan tiap
`FTTH_CPE_SYNC_INTERVAL` (default 5 menit), jadi tunggu sebentar.

### L.4 Unggah firmware (buat fitur upgrade)

Aplikasi hanya **membaca** daftar firmware dari ACS lalu memerintahkan Download RPC —
berkasnya sendiri diunggah ke GenieACS. GenieACS UI sengaja **tidak** dipasang (satu
service + satu secret lagi, padahal dashboard kita sudah jadi UI-nya), jadi unggahnya
lewat NBI dari dalam VPS:

```bash
cd /opt/ftth
docker compose -f docker-compose.prod.yml cp firmware.bin genieacs-nbi:/tmp/firmware.bin
docker compose -f docker-compose.prod.yml exec genieacs-nbi \
  curl -X PUT --data-binary @/tmp/firmware.bin \
    -H 'fileType: 1 Firmware Upgrade Image' \
    -H 'oui: 002E44' \
    -H 'productClass: HG8546M' \
    -H 'version: V5R020C10S115' \
    http://localhost:7557/files/HG8546M-V5R020C10S115.bin
```

- `fileType` **harus persis** `1 Firmware Upgrade Image` — itu yang disaring aplikasi.
- `oui` + `productClass` menentukan firmware ini muncul untuk model apa. Ambil nilainya
  dari halaman CPE perangkat bersangkutan. Dikosongkan = berlaku untuk semua model
  (berbahaya, jangan).
- Cek hasilnya: `curl 'http://localhost:7557/files/?query=%7B%7D'` dari dalam container
  yang sama. Hapus: `curl -X DELETE http://localhost:7557/files/<nama-berkas>`.

### L.5 Uji Kecepatan (TR-143)

Tombol "Uji Kecepatan" menyuruh **perangkat** mengunduh sebuah berkas uji, bukan server.
Default bawaannya `http://speedtest.tele2.net/10MB.zip` — layanan itu **sudah dimatikan
Tele2**, jadi selama tak diganti, uji kecepatan akan selalu gagal. Arahkan ke berkas
milikmu sendiri (mis. file besar di web server ISP-mu):

```bash
FTTH_CPE_DIAGNOSTICS_DOWNLOAD_URL=http://cdn.contoh.com/10MB.bin
```

---

## Bagian M — Cadangan & pemulihan database

Stack ini mencadangkan dirinya sendiri: dua service (`backup` dan `backup-radius`) tidur
sampai jam yang kamu tentukan, men-dump satu database masing-masing, memverifikasi
hasilnya, lalu membuang cadangan yang kedaluwarsa. Tak ada cron di host, tak ada yang
perlu dipasang — begitu `docker compose up -d`, keduanya ikut jalan.

### M.1 Apa yang dicadangkan (dan apa yang tidak)

| Isi | Dicadangkan? | Keterangan |
|---|---|---|
| DB aplikasi (`ftth`) | ✅ tiap hari | pelanggan, langganan, tagihan, WO, tiket, metrik ONU |
| DB RADIUS (`radius`) | ✅ tiap hari | `nas`, `radcheck`, `radgroupreply`, riwayat `radacct` |
| Bukti foto WO (MinIO) | ❌ | volume `miniodata` — salin sendiri bila dianggap penting |
| Data GenieACS (Mongo) | ❌ | proyeksi; terbentuk lagi sendiri saat perangkat Inform |
| `.env` | ❌ | **berisi semua secret — simpan salinannya di luar VPS, sekarang** |

Kehilangan `.env` sama gawatnya dengan kehilangan database: tanpa
`FTTH_ENCRYPTION_SECRET` yang sama, kredensial SNMP di dalam cadangan tak bisa dibaca
lagi.

### M.2 Setelan

Empat baris di `.env` (semuanya punya nilai bawaan, boleh dibiarkan):

```bash
BACKUP_TZ=Asia/Jakarta      # zona waktu jam di bawah
BACKUP_AT=02:30             # cadangan DB aplikasi
BACKUP_RADIUS_AT=03:00      # cadangan DB radius (digeser agar tak rebutan I/O)
BACKUP_RETENTION_DAYS=14    # lebih tua dari ini dibuang
```

Hasilnya menumpuk di `/opt/ftth/backups/app/` dan `/opt/ftth/backups/radius/`, mode
`600` milik root (isinya seluruh basis data — perlakukan seperti secret).

Kalau container ikut restart tiap deploy, cadangan tak akan terlewat: saat start ia
memeriksa umur cadangan terakhir, dan langsung menjalankan satu ronde bila sudah lewat
26 jam.

> **Kenapa cadangan berjalan sebagai superuser `postgres`, bukan sebagai `ftth`?**
> Semua tabel ber-tenant memakai `FORCE ROW LEVEL SECURITY`, yang berlaku bahkan untuk
> pemilik tabel. Sesi `pg_dump` tak pernah menyetel `app.tenant_id`, jadi dump oleh role
> `ftth` menghasilkan file yang tampak wajar, punya skema lengkap — dan **nol baris**.
> `backup.sh` menolak jalan kalau role-nya tunduk RLS, supaya kesalahan itu tak pernah
> bisa terjadi diam-diam.

### M.3 Memeriksa cadangan masih hidup

```bash
cd /opt/ftth
docker compose -f docker-compose.prod.yml logs --tail 20 backup
sudo cat backups/app/last-backup.txt      # status=OK, ukuran, jumlah yang disimpan
sudo ls -lh backups/app backups/radius
```

Setiap dump diverifikasi dengan `pg_restore --list` sebelum diterima, dan khusus DB
aplikasi dicek harus memuat data tabel `tenant` — file terpotong atau cadangan hampa
ditolak dan dilaporkan `status=GAGAL`, bukan disimpan diam-diam.

### M.4 Latihan pemulihan (lakukan sekali, lalu ulangi tiap kuartal)

Cadangan yang belum pernah dipulihkan bukan cadangan, cuma harapan. Mode latihan
memulihkan ke **database baru** di server yang sama, jadi aman dijalankan di produksi
yang sedang melayani pelanggan (yang dibutuhkan cuma ruang disk sebesar databasenya).

```bash
cd /opt/ftth
C="docker compose -f docker-compose.prod.yml run --rm --entrypoint sh"

$C backup /opt/backup/restore.sh              # lihat daftar cadangan
$C backup /opt/backup/restore.sh latest       # pulihkan yang terbaru ke DB latihan
```

Yang harus kamu lihat di ujungnya — angka-angka ini yang membuktikan cadangannya
berisi, termasuk `onu_metric` (hypertable TimescaleDB, bagian yang paling gampang
diam-diam hilang):

```
[restore:app] isi database 'ftth_drill_20260810_093850':
    tenant           2 baris
    customer         318 baris
    invoice          1204 baris
    onu_metric       412880 baris
```

Kalau ada baris yang 0 padahal produksi jelas punya isinya, **berhenti dan benahi
cadangannya** — jangan tunggu hari kamu benar-benar membutuhkannya. Selesai memeriksa,
buang DB latihannya:

```bash
docker compose -f docker-compose.prod.yml exec postgres \
  psql -U postgres -c 'DROP DATABASE "ftth_drill_20260810_093850"'
```

Untuk DB radius sama persis, tinggal ganti servicenya:

```bash
$C backup-radius /opt/backup/restore.sh latest
```

### M.5 Pemulihan sungguhan (data produksi rusak/hilang)

Ini menghapus database yang sekarang. Hentikan aplikasinya dulu supaya tak ada yang
menulis di tengah proses:

```bash
cd /opt/ftth
docker compose -f docker-compose.prod.yml stop server
docker compose -f docker-compose.prod.yml run --rm --entrypoint sh \
  backup /opt/backup/restore.sh latest --replace
# ketik nama database ('ftth') saat diminta konfirmasi
docker compose -f docker-compose.prod.yml start server
```

Skrip memasang `postgis`+`timescaledb`, memanggil `timescaledb_pre_restore()` sebelum
dan `timescaledb_post_restore()` sesudah `pg_restore` (tanpa itu hypertable-nya kacau),
lalu `ANALYZE` dan menghitung isi tiap tabel penting. Kalau image aplikasi lebih baru
daripada cadangannya, Flyway menjalankan migrasi yang kurang saat server nyala lagi —
tak ada langkah tambahan.

Butuh memulihkan **satu tenant** saja? Pulihkan ke DB latihan (M.4), lalu salin
baris-barisnya dengan tangan. Cadangan ini se-database, bukan per-tenant.

### M.6 Membangun ulang di VPS baru (VPS lama hilang total)

1. Ikuti **Bagian B & C** (Docker + folder deploy).
2. Kembalikan `.env` dari salinan luar-VPS-mu — nilainya harus **persis sama**,
   terutama `FTTH_ENCRYPTION_SECRET`.
3. Salin folder `backups/` ke `/opt/ftth/backups/`.
4. `docker compose -f docker-compose.prod.yml up -d postgres radius-db` — boot pertama
   membuat role, database, dan extension-nya dari `.env`.
5. Pulihkan keduanya:
   ```bash
   C="docker compose -f docker-compose.prod.yml run --rm --entrypoint sh"
   $C backup        /opt/backup/restore.sh latest --replace
   $C backup-radius /opt/backup/restore.sh latest --replace
   ```
6. `docker compose -f docker-compose.prod.yml up -d`.

Berkas `globals-*.sql` di samping tiap dump berisi definisi role level-cluster —
jaring pengaman kalau di kemudian hari ada role yang dibuat dengan tangan, di luar
`.env`.

### M.7 Salin cadangan ke luar VPS (jangan dilewat)

Cadangan yang tinggal di mesin yang sama tak menolong saat mesinnya yang hilang —
disk rusak, akun ditutup, VPS terhapus. Tarik berkalanya dari laptop/NAS-mu:

```bash
rsync -avz --rsync-path='sudo rsync' \
  -e 'ssh -i ~/.ssh/kunci-vps' \
  fajar@IP-VPS:/opt/ftth/backups/ ~/cadangan-ftth/
```

(atau `rclone` ke object storage mana pun). Isinya seluruh data pelanggan — simpan
terenkripsi dan jangan di folder yang tersinkron sembarangan.

---

## Bagian N — Pemantauan (pekerjaan latar yang diam-diam berhenti)

Aplikasi ini menjalankan belasan pekerjaan latar: menerbitkan tagihan, menegakkan
tunggakan, memoll OLT, menarik sesi RADIUS, menyinkron CPE, mengeksekusi provisioning.
Semuanya berjalan tanpa ditonton siapa pun — dan itulah masalahnya. Kalau salah satu
berhenti, **tak ada layar yang berubah merah**. Tagihan sekadar tak terbit bulan itu.
Sesi PPPoE sekadar tak tercatat. Biasanya baru ketahuan seminggu kemudian, lewat
keluhan pelanggan, dan saat itu kerusakannya sudah harus dibereskan dengan tangan.

Karena itu setiap metode terjadwal mendaftarkan dirinya sendiri saat aplikasi hidup —
tak ada daftar yang harus dirawat manual, job baru ikut terpantau otomatis — lalu
denyutnya (mulai, sukses, gagal, lama ronde) dicatat tiap kali berjalan.

### N.1 Tiga lapis, dan yang mana yang benar-benar perlu

| Lapis | Perlu setelan? | Untuk apa |
|---|---|---|
| Halaman **Pekerjaan Latar** di app | tidak, sudah jalan | melihat kondisi saat ini |
| **Email peringatan** | `FTTH_ALERT_EMAIL` (1 baris) | diberi tahu saat ada yang macet |
| **Prometheus + Grafana** | profil `monitoring` (opsional) | grafik & riwayat |

Yang wajib cuma satu: isi `FTTH_ALERT_EMAIL`. Dua lainnya pelengkap. Sebuah halaman
hanya menolong kalau ada yang membukanya, dan tak ada yang membuka halaman kesehatan
server di hari yang tenang — justru hari yang tenang itulah job-nya diam-diam mati.

### N.2 Halaman "Pekerjaan Latar"

Masuk sebagai admin **platform** → menu **Infrastruktur → Pekerjaan Latar**
(`/platform/jobs`, izin `platform.ops.view`). Isinya seluruh job beserta interval,
sukses terakhir, jumlah ronde & kegagalan, lama ronde terakhir, dan pesan galat
terakhir bila ada. Halaman menyegarkan dirinya tiap 15 detik.

Ini urusan lintas-tenant — kesehatan proses server kita sendiri, bukan urusan ISP —
jadi admin tenant tak melihatnya sama sekali.

Sengaja **tidak ada tombol "jalankan sekarang"**. Menyuntik ronde tagihan dengan
tangan dari halaman diagnosa adalah cara termudah menerbitkan tagihan ganda.

### N.3 Email peringatan (bagian yang penting)

```bash
FTTH_ALERT_EMAIL=ops@contoh.com
```

Butuh SMTP platform yang sudah terisi (`FTTH_MAIL_*`, blok "Email keluar" di `.env`) —
tanpa itu peringatan cuma jatuh ke log server.

Penjaganya memeriksa seluruh job tiap 5 menit dan menyatakan sebuah job **macet** bila
sukses terakhirnya lebih tua dari `interval × 3`, dengan tenggang minimum 10 menit
(supaya job berinterval 30 detik tak berteriak hanya karena satu ronde tersendat).
Contoh: penerbit tagihan berjalan tiap 12 jam → diperingatkan setelah 36 jam tanpa
sukses.

Yang dikirim:

| Kejadian | Subjek |
|---|---|
| pertama kali macet | `[NetOps Console] MACET: BillingScheduler.issueInvoices` |
| masih macet (tiap 6 jam) | `[NetOps Console] MASIH MACET: …` |
| jalan lagi | `[NetOps Console] Pulih: …` |

Isinya menyebut modul, interval, berapa lama sejak sukses terakhir, jumlah ronde &
kegagalan, dan galat terakhir. Pengingatnya ditahan 6 jam dengan sengaja: job yang mati
semalaman akan mengirim ratusan email, dan banjir peringatan selalu berakhir jadi aturan
filter di inbox — sesudah itu peringatan berikutnya tak pernah sampai ke siapa pun.

Peringatan ini memakai SMTP **platform**, bukan kanal notifikasi tenant. Kesehatan
server kita bukan sesuatu yang boleh dimatikan dari setelan notifikasi sebuah ISP.

> **Job macet, lalu apa?** Buka halaman Pekerjaan Latar, lihat kolom galat terakhir,
> cocokkan dengan log: `docker compose -f docker-compose.prod.yml logs --tail 300 server
> | grep -i <NamaJob>`. Sebagian besar kemacetan bermuara pada satu ronde yang
> menggantung (radius-db/ACS tak menjawab, tanpa timeout) dan hilang setelah
> `restart server`. Kalau antrean penjadwal ikut menumpuk, naikkan
> `FTTH_SCHEDULER_POOL_SIZE` — bawaannya 4 utas untuk **semua** job.

### N.4 (Opsional) Prometheus + Grafana untuk grafik & riwayat

Halaman dan email menjawab "sekarang sehat?". Untuk "sejak kapan melambat?" ada profil
`monitoring` — dua container tambahan yang **tidak** ikut `up -d` biasa.

Setel dulu di `.env`:

```bash
FTTH_METRICS_TOKEN=$(openssl rand -base64 32)   # tanpa ini endpoint metrik tertutup
GRAFANA_ADMIN_PASSWORD=sandi-grafana-yang-kuat
PROMETHEUS_RETENTION=30d
```

Lalu:

```bash
cd /opt/ftth
docker compose -f docker-compose.prod.yml up -d server            # muat token metrik
docker compose -f docker-compose.prod.yml --profile monitoring up -d
```

**Keduanya tidak terbuka ke internet** — Caddy hanya meneruskan `/api/*`,
`/swagger-ui*`, dan `/v3/api-docs*`, sedangkan Prometheus & Grafana terikat di
`127.0.0.1` VPS saja. Aksesnya lewat terowongan SSH dari laptop:

```bash
ssh -i ~/.ssh/kunci-vps -L 3000:localhost:3000 -L 9090:localhost:9090 fajar@IP-VPS
# lalu buka http://localhost:3000  (admin / GRAFANA_ADMIN_PASSWORD)
```

Grafana sudah terisi sendiri: sumber data Prometheus + dasbor **ftth — Pekerjaan
Latar** (pekerjaan macet, kegagalan 1 jam terakhir, umur sukses relatif terhadap ambang
macet, ronde per menit, lama ronde, antrean penjadwal). Empat aturan alert ikut
dimuat — server tak terjangkau, pekerjaan latar macet, sering gagal, dan penjadwal
kehabisan utas — semuanya terlihat di `http://localhost:9090/alerts`. Prometheus di
sini tidak mengirim notifikasi ke mana-mana (itu tugas email di N.3); ia menyimpan
riwayat dan memperlihatkan alert yang menyala.

Mematikannya lagi: `docker compose -f docker-compose.prod.yml --profile monitoring down`
(aplikasinya tak tersentuh).

> **Menulis kueri sendiri?** Nama job ada di label **`job_name`**, bukan `job`.
> Prometheus memakai `job` untuk nama scrape-config-nya sendiri, dan label aplikasi yang
> bentrok diganti diam-diam jadi `exported_job` saat diserap — aturan yang menyebut
> `job="…"` tak akan pernah menyala, tanpa pesan galat apa pun.

Sudah punya Prometheus sendiri? Lewati profil ini, tapi ingat `/actuator` tak
diteruskan Caddy — jadi `https://app.contoh.com/actuator/prometheus` tak akan pernah
menjawab. Scrape-nya harus lewat jalur lain: buka port `8080` server ke jaringan
pemantauanmu (dibatasi firewall) atau lewat terowongan SSH, dengan header
`Authorization: Bearer $FTTH_METRICS_TOKEN`.

---

## Operasional harian

| Mau apa | Perintah (di `/opt/ftth` pada VPS) |
|---|---|
| Update ke versi terbaru | cukup `git push main` dari laptop — otomatis (kalau Actions limit → **Bagian J**) |
| Restart semua | `docker compose -f docker-compose.prod.yml restart` |
| Lihat log realtime | `docker compose -f docker-compose.prod.yml logs -f server` |
| Cek cadangan semalam | `sudo cat backups/app/last-backup.txt` (otomatis tiap malam — **Bagian M**) |
| Cadangkan sekarang juga | `docker compose -f docker-compose.prod.yml exec backup sh /opt/backup/backup.sh` |
| Latihan pemulihan | `docker compose -f docker-compose.prod.yml run --rm --entrypoint sh backup /opt/backup/restore.sh latest` |
| Cek pekerjaan latar sehat | buka **Infrastruktur → Pekerjaan Latar** di app (**Bagian N**) |
| Nyalakan grafik Prometheus/Grafana | `docker compose -f docker-compose.prod.yml --profile monitoring up -d` |
| Matikan sementara | `docker compose -f docker-compose.prod.yml down` (data aman di volume) |

---

## Catatan penting

- **Ganti `FTTH_ENCRYPTION_SECRET` = kredensial SNMP lama tak terbaca.** Set sekali, jangan diubah.
- **`FTTH_SEED_DEMO=false` di produksi** — biar tenant demo gak kebawa.
- **Redis & RabbitMQ tidak dipakai** versi ini (belum ada di kode), makanya gak ada di stack.
- **GenieACS (fitur CPE/TR-069) sudah termasuk** di stack — lihat **Bagian L**. Perlu
  membuka port `7547` (dan `7567` bila pakai upgrade firmware); port NBI `7557` jangan
  pernah dibuka ke internet.
- **Cadangan jalan otomatis** (Bagian M), tapi dua hal tetap tugasmu: menyalin
  `backups/` + `.env` ke luar VPS, dan sekali-sekali betul-betul mencoba memulihkannya.
- **Isi `FTTH_ALERT_EMAIL`** (Bagian N). Pekerjaan latar gagal dengan cara paling jahat:
  diam. Tanpa alamat ini, tagihan yang berhenti terbit baru ketahuan dari keluhan
  pelanggan, berhari-hari kemudian.
- **Test job di CI** butuh Postgres+Timescale; kalau rewel, bisa longgarin dengan hapus
  `needs: test` di job `build-and-push` (`.github/workflows/deploy.yml`).
