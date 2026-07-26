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
scp -r deploy/postgres-init azureuser@20.11.22.33:/opt/ftth/
```

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

## Operasional harian

| Mau apa | Perintah (di `/opt/ftth` pada VPS) |
|---|---|
| Update ke versi terbaru | cukup `git push main` dari laptop — otomatis |
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
