# Deploy DEMO peniru-protokol ke server online (`fajar@20.6.72.13`)

Panduan menaikkan **demo end-to-end** (app + web + **simulator** OLT/SNMP & BRAS/RADIUS +
GenieACS/CPE) supaya bisa dicoba lewat browser di
**`https://simulator-ftth.karuhundeveloper.com`** TANPA perangkat nyata. Setelah setup
sekali, tiap `git push` ke `main` otomatis mem-deploy ulang.

Beda dengan `DEPLOY.md` (stack **produksi** tanpa simulator, di `ftth.karuhundeveloper.com`).

> **PENTING — co-located dengan produksi.** Server `20.6.72.13` ini **juga** menjalankan
> stack produksi (`ftth.karuhundeveloper.com`) yang Caddy-nya sudah memegang port **80/443**.
> Karena satu host cuma boleh punya satu proses di 443, stack demo **TIDAK punya Caddy
> sendiri**. Caddy **produksi** (`ftth-caddy-1`) yang menjadi pintu untuk domain demo:
> disambungkan ke network project demo, lalu me-reverse-proxy domain demo ke container demo.

```
  push main ─▶ GitHub Actions:
                1. build image ftth-server + ftth-web + ftth-simulator → GHCR
                2. rsync file repo → server demo
                3. SSH: compose pull + up -d --build → sambung caddy prod → reload → seed
                       ▼
        ┌──────────────── Server 20.6.72.13 (satu host) ────────────────┐
        │  ┌─ Caddy PROD (ftth-caddy-1) — satu-satunya pemilik 80/443 ─┐ │
Browser▶│  │  ftth.karuhundeveloper.com            ─▶ prod server/web  │ │
        │  │  simulator-ftth.karuhundeveloper.com  ─▶ ftth-demo-web-1  │ │
        │  └────────────────────────────────────────┬─────────────────┘ │
        │  Stack ftth (prod)          Stack ftth-demo (network dihubung) │
        │  ─ server/web/freeradius…   ─ server · web · simulator ·       │
        │                               radius-db · genieacs(mongo/…)     │
        └─────────────────────────────────────────────────────────────────┘
```

Image JVM kita (`ftth-server`/`ftth-web`/`ftth-simulator`) **ditarik dari GHCR**; image
GenieACS **di-build lokal** dari file repo di server (Node ringan). Server memegang salinan
file repo di `/opt/ftth-demo` (bukan git checkout — di-**rsync** dari runner tiap deploy);
compose me-mount `deploy/postgres-init`, `docker/radius/initdb` dan seeding pakai
`docker/lab/seed-lab.sh`.

---

## Bagian A — Bootstrap server (sekali saja)

SSH ke server, lalu:

```bash
# 1) Docker Engine + plugin compose + tool seed (jq/curl) — kalau belum ada
sudo apt-get update && sudo apt-get install -y ca-certificates curl git jq rsync
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER        # logout-login lagi setelah ini
```

> `jq` + `curl` wajib ada di host — dipakai `seed-lab.sh`. `rsync` dipakai CI mengirim file.

```bash
# 2) Siapkan direktori demo + .env (file akan diisi via rsync dari CI)
sudo mkdir -p /opt/ftth-demo && sudo chown $USER:$USER /opt/ftth-demo
cd /opt/ftth-demo
# rsync manual pertama dari laptop (atau tunggu deploy CI pertama):
#   rsync -az --exclude .git --exclude '**/build' ./ fajar@20.6.72.13:/opt/ftth-demo/
cp deploy/.env.demo.example .env     # IMAGE_PREFIX + FTTH_CORS_ORIGINS (default domain demo)
```

**Firewall / security group** — cukup buka inbound **22** (SSH). Port **80/443** sudah
dibuka & dipegang Caddy prod (dipakai bersama). Simulator (SNMP/DAE) hanya internal.

```bash
# 3) Login GHCR (PAT ber-scope read:packages) agar bisa pull image
echo '<GHCR_PAT>' | docker login ghcr.io -u <username-github> --password-stdin

# 4) Nyalakan pertama kali (tanpa caddy — stack demo tak punya caddy)
docker compose -f docker-compose.demo.yml pull
docker compose -f docker-compose.demo.yml up -d --build
```

### Wiring ingress ke Caddy prod (sekali; permanen)

Sambungkan Caddy prod ke network demo dan tambah site-block domain demo di
`/opt/ftth/Caddyfile` (Caddyfile prod TIDAK ditimpa oleh CI `deploy` prod — aman permanen):

```bash
# a) sambungkan caddy prod ke network project demo (nama unik ftth-demo-* jadi resolvable)
docker network connect ftth-demo_default ftth-caddy-1

# b) tambah blok ini ke /opt/ftth/Caddyfile (setelah blok {$FTTH_SITE_ADDRESS} yang ada):
#
#   simulator-ftth.karuhundeveloper.com {
#       encode zstd gzip
#       @backend path /api/* /swagger-ui* /v3/api-docs*
#       handle @backend { reverse_proxy ftth-demo-server-1:8080 }
#       handle          { reverse_proxy ftth-demo-web-1:80 }
#   }
#
# c) validasi + reload graceful (situs prod tak putus):
docker exec ftth-caddy-1 caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker exec ftth-caddy-1 caddy reload   --config /etc/caddy/Caddyfile --adapter caddyfile
```

Caddy menerbitkan sertifikat Let's Encrypt untuk domain demo otomatis (ACME HTTP-01 lewat
Cloudflare). Set **Cloudflare SSL/TLS ke "Full (strict)"** untuk domain ini.

```bash
# d) seed OLT/BRAS/CPE ke simulator (idempoten) — BASE = domain demo, bukan localhost!
COMPOSE="docker compose -f docker-compose.demo.yml" \
  BASE=https://simulator-ftth.karuhundeveloper.com bash docker/lab/seed-lab.sh
```

> **Jangan** pakai `BASE=http://localhost` di host ini — port 80 milik Caddy prod, seed
> akan nyasar ke stack produksi. Selalu seed lewat domain demo.

Buka `https://simulator-ftth.karuhundeveloper.com` → login **`admin@demo.ftth` / `admin12345`**.

---

## Bagian B — GitHub Secrets untuk auto-deploy

**Tak ada secret baru.** Karena demo **co-located di host & user yang SAMA** dengan produksi,
job `deploy-demo` memakai ulang secret SSH prod yang sudah ada:

| Secret | Dipakai untuk | Sudah ada? |
|---|---|---|
| `VPS_HOST` | host SSH (= `20.6.72.13`, sama dgn prod) | ✅ dari job `deploy` prod |
| `VPS_USER` | user SSH (= `fajar`) | ✅ |
| `VPS_SSH_KEY` | private key SSH (rsync + ssh-action) | ✅ |
| `GHCR_USER` / `GHCR_PAT` | login GHCR untuk `pull` image | ✅ |

Jadi selama stack prod sudah auto-deploy (secret `VPS_*` terisi), `deploy-demo` langsung
ikut jalan tanpa konfigurasi tambahan.

---

## Bagian C — Deploy otomatis & operasional

Setelah Bagian A & B beres, **cukup `git push origin main`** — job `deploy-demo` di
`.github/workflows/deploy.yml`: **rsync** file repo ke server, `compose pull + up -d --build`,
menyambung Caddy prod ke network demo + reload (idempoten), tunggu server sehat, lalu
`seed-lab.sh`. Bisa juga dipicu manual dari tab **Actions** (`workflow_dispatch`).

| Mau apa | Perintah (di `/opt/ftth-demo`) |
|---|---|
| Status service | `docker compose -f docker-compose.demo.yml ps` |
| Log realtime | `docker compose -f docker-compose.demo.yml logs -f server` |
| Seed ulang (idempoten) | `COMPOSE="docker compose -f docker-compose.demo.yml" BASE=https://simulator-ftth.karuhundeveloper.com bash docker/lab/seed-lab.sh` |
| Restart | `docker compose -f docker-compose.demo.yml restart` |
| Reset bersih (HAPUS data demo) | `docker compose -f docker-compose.demo.yml down -v` |
| Cek wiring caddy | `docker exec ftth-caddy-1 caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile` |

**Yang muncul setelah seed** (login `admin@demo.ftth`): armada OLT palsu terpantau di menu
**Jaringan** (SNMP ~30 dtk); sesi PPPoE + trafik hidup di detail pelanggan **Budi Lab**
(BRAS/RADIUS, ~1 menit); ONT palsu serial `000000` di menu **CPE** (GenieACS TR-069). Uji
**Isolir/Reset Login** → server tembak DAE ke simulator → SUCCESS.

---

## Catatan

- **Demo, bukan produksi.** Secret app pakai default dev; `FTTH_SEED_DEMO=true`. Jangan
  taruh data sungguhan.
- **Co-located dgn prod di satu host** — hati-hati: `down -v` / `up` demo TIDAK menyentuh
  prod (project & volume terpisah), tapi keduanya berbagi CPU/RAM & Caddy. Reload Caddy
  bersifat graceful (validasi dulu), jadi salah config demo tak menjatuhkan situs prod.
- **Resource:** dua stack penuh dalam satu host → sediakan minimal ~4 vCPU / 8 GB RAM.
- **Domain & HTTPS:** `simulator-ftth.karuhundeveloper.com` (DNS via Cloudflare, proxied) →
  origin = Caddy prod, sertifikat Let's Encrypt otomatis. Ganti domain? edit site-block di
  `/opt/ftth/Caddyfile` + `FTTH_CORS_ORIGINS` di `.env` demo, lalu reload caddy + `up -d`.
```
