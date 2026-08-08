# Deploy DEMO peniru-protokol ke server online (`fajar@20.6.72.13`)

Panduan menaikkan **demo end-to-end** (app + web + **simulator** OLT/SNMP & BRAS/RADIUS +
GenieACS/CPE) ke sebuah server publik supaya bisa dicoba lewat browser TANPA perangkat
nyata. Setelah setup sekali, tiap `git push` ke `main` otomatis mem-deploy ulang.

Beda dengan `DEPLOY.md` (stack **produksi** tanpa simulator). Keduanya bisa hidup di
server berbeda, tak saling ganggu — job CI-nya pun terpisah (`deploy` vs `deploy-demo`).

```
  push main ─▶ GitHub Actions:
                1. build image ftth-server + ftth-web + ftth-simulator → GHCR
                2. SSH ke server demo → git pull → compose pull + up -d --build → seed
                       ▼
        ┌──────────── Server demo (Ubuntu) ────────────┐
Browser▶│ Caddy(80/443)┬ /api/* ─▶ server (Spring)      │
        │             └ /*     ─▶ web (Nginx+SPA)       │
        │ postgres · minio · radius-db · simulator ·    │
        │ genieacs(mongo/cwmp/nbi/sim)                  │
        └───────────────────────────────────────────────┘
```

Image JVM kita (`ftth-server`/`ftth-web`/`ftth-simulator`) **ditarik dari GHCR**; image
GenieACS **di-build lokal** dari checkout repo di server (Node ringan). Karena itu server
memegang **checkout repo penuh** di `/opt/ftth-demo` — compose me-mount file repo
(`deploy/postgres-init`, `docker/radius/initdb`, `deploy/Caddyfile`) dan seeding pakai
`docker/lab/seed-lab.sh`.

---

## Bagian A — Bootstrap server (sekali saja)

SSH ke server, lalu:

```bash
# 1) Docker Engine + plugin compose
sudo apt-get update && sudo apt-get install -y ca-certificates curl git jq
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER        # logout-login lagi setelah ini
```

> `jq` + `curl` wajib ada di host — dipakai `seed-lab.sh`.

```bash
# 2) Checkout repo ke /opt/ftth-demo
sudo mkdir -p /opt/ftth-demo && sudo chown $USER:$USER /opt/ftth-demo
git clone https://github.com/fajarxfce/ftth.git /opt/ftth-demo
cd /opt/ftth-demo

# 3) Buat .env dari template (default sudah di-set ke domain demo)
cp deploy/.env.demo.example .env
nano .env   # samakan IMAGE_PREFIX dgn owner GHCR. FTTH_SITE_ADDRESS &
            # FTTH_CORS_ORIGINS sudah = simulator-ftth.karuhundeveloper.com (HTTPS).
```

**Firewall / security group** — buka inbound **22** (SSH), **80** (HTTP — ACME challenge +
redirect), dan **443** (HTTPS). Domain `simulator-ftth.karuhundeveloper.com` sudah di-pointing
ke IP server, jadi Caddy langsung menerbitkan sertifikat Let's Encrypt begitu stack naik.
Simulator (SNMP/DAE) hanya diakses internal jaringan compose, tak perlu dibuka.

```bash
# 4) Login GHCR (PAT ber-scope read:packages) agar bisa pull image privat
echo '<GHCR_PAT>' | docker login ghcr.io -u <username-github> --password-stdin

# 5) Nyalakan pertama kali + seed
docker compose -f docker-compose.demo.yml pull
docker compose -f docker-compose.demo.yml up -d --build
# tunggu server sehat (~1-2 menit), lalu seed OLT/BRAS/CPE ke simulator:
COMPOSE="docker compose -f docker-compose.demo.yml" BASE=http://localhost \
  bash docker/lab/seed-lab.sh
```

Buka `https://simulator-ftth.karuhundeveloper.com` → login **`admin@demo.ftth` / `admin12345`**.

> Image belum ada di GHCR? Push `main` dulu sekali (job `build-and-push`) supaya
> `ftth-server`/`ftth-web`/`ftth-simulator` terbit, baru `pull` di sini berhasil.

---

## Bagian B — GitHub Secrets untuk auto-deploy

Repo → **Settings → Secrets and variables → Actions**. Tambah 3 secret **baru** khusus
demo (reuse `GHCR_USER`/`GHCR_PAT` yang sudah ada untuk prod):

| Secret | Isi |
|---|---|
| `DEMO_HOST` | IP server demo, mis. `20.6.72.13` |
| `DEMO_USER` | user SSH, mis. `fajar` |
| `DEMO_SSH_KEY` | **private key** SSH untuk masuk server demo |

Bikin key khusus robot (dari laptop):

```bash
ssh-keygen -t ed25519 -f ~/.ssh/ftth_demo -N ""
ssh-copy-id -i ~/.ssh/ftth_demo.pub fajar@20.6.72.13
cat ~/.ssh/ftth_demo        # SELURUH isinya (BEGIN..END) → jadikan DEMO_SSH_KEY
```

---

## Bagian C — Deploy otomatis & operasional

Setelah Bagian A & B beres, **cukup `git push origin main`** — job `deploy-demo` di
`.github/workflows/deploy.yml` SSH ke server, `git pull`, `compose pull + up -d --build`,
lalu `seed-lab.sh` (idempoten). Bisa juga dipicu manual dari tab **Actions**
(`workflow_dispatch`).

| Mau apa | Perintah (di `/opt/ftth-demo`) |
|---|---|
| Status service | `docker compose -f docker-compose.demo.yml ps` |
| Log realtime | `docker compose -f docker-compose.demo.yml logs -f server` |
| Seed ulang (idempoten) | `COMPOSE="docker compose -f docker-compose.demo.yml" BASE=http://localhost bash docker/lab/seed-lab.sh` |
| Restart | `docker compose -f docker-compose.demo.yml restart` |
| Reset bersih (HAPUS data demo) | `docker compose -f docker-compose.demo.yml down -v` |

**Yang muncul setelah seed** (login `admin@demo.ftth`): armada OLT palsu terpantau di menu
**Jaringan** (SNMP ~30 dtk); sesi PPPoE + trafik hidup di detail pelanggan **Budi Lab**
(BRAS/RADIUS, ~1 menit); ONT palsu serial `000000` di menu **CPE** (GenieACS TR-069). Uji
**Isolir/Reset Login** → server tembak DAE ke simulator → SUCCESS.

---

## Catatan

- **Demo, bukan produksi.** Secret app pakai default dev; `FTTH_SEED_DEMO=true`. Jangan
  taruh data sungguhan.
- **Resource:** stack penuh (Timescale + Mongo + JVM×2 + simulator) → server minimal
  ~2 vCPU / 4 GB RAM.
- **Domain & HTTPS:** default `.env` sudah `simulator-ftth.karuhundeveloper.com` (DNS sudah
  di-pointing) → Caddy urus sertifikat Let's Encrypt otomatis. Ganti domain? edit
  `FTTH_SITE_ADDRESS` + `FTTH_CORS_ORIGINS` di `.env` lalu `up -d`.
