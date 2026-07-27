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

## 4. Lab BNG (BRAS/RADIUS) di atas stack ini

Service `radius-db` sudah membawa skema FreeRADIUS + satu sesi PPPoE contoh. Untuk mencoba alur
isolir → pulih end-to-end, jalankan skrip helper (butuh `curl` + `jq` di host):

**a. Auto-wire** — buat paket, pelanggan, langganan aktif, collector, NAS FreeRADIUS, dan akun
PPPoE `budi@isp.net`, semuanya lewat API:

```bash
bash docker/lab/wire-lab.sh
```

Skrip menyimpan API key collector & accessId ke `/tmp/lab_*`.

**b. Nyalakan collector** dengan API key dari langkah a:

```bash
FTTH_COLLECTOR_KEY=$(cat /tmp/lab_api_key) \
  docker compose -f docker-compose.lab.yml --profile collector up -d collector
```

Collector akan poll `radius-db` (JDBC) tiap interval dan mengeksekusi aksi `bng_action` yang
mengantre (Disconnect/CoA).

**c. Demo isolir → pulih:**

```bash
bash docker/lab/demo-isolir.sh
```

> **Catatan jujur:** tanpa BRAS nyata yang mendengarkan DAE di UDP 3799, perintah `DISCONNECT`
> tidak benar-benar menjatuhkan sesi (aksi berakhir `FAILED`) dan sesi contoh tetap `online`
> karena baris `radacct`-nya statis. Yang **berubah nyata** adalah state-machine access
> (`ACTIVE → ISOLATED → ACTIVE`) beserta antrean `bng_action` dan audit. Untuk uji drop sesi
> beneran, pakai **profil `freeradius`** atau Mikrotik CHR — lihat [`docs/lab-bras-radius.md`](lab-bras-radius.md).

Menyalakan FreeRADIUS nyata (opsional):

```bash
docker compose -f docker-compose.lab.yml --profile freeradius up -d freeradius
```

---

## 5. Perintah berguna

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
