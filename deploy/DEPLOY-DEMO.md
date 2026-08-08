# Simulator peniru-protokol online untuk testing produksi (`fajar@20.6.72.13`)

Panduan menaikkan **simulator OLT/SNMP + BRAS/RADIUS** di server publik supaya bisa
di-poll & ditembak DAE oleh **aplikasi PRODUKSI** (`ftth.karuhundeveloper.com`) yang
berjalan di host yang sama — untuk **menguji SNMP monitoring & isolir/reset-login tanpa
perangkat nyata**. Setelah setup sekali, tiap `git push` ke `main` otomatis mem-deploy
ulang image simulator dan menyambungkan ulang app prod.

Beda dengan `DEPLOY.md` (stack **produksi** penuh). Ini **bukan** demo end-to-end
terpisah — tak ada app/web/GenieACS/Caddy kedua; yang mengakses simulator adalah app
prod itu sendiri.

> **Kenapa dipangkas jadi simulator saja.** Tujuannya hanya "perangkat palsu" yang bisa
> disentuh app prod. Menjalankan app/web kedua cuma buang RAM. Jadi stack ini = **hanya
> `simulator` + `radius-db`**.

```
  push main ─▶ GitHub Actions:
                1. build image ftth-simulator → GHCR
                2. rsync file repo → server (/opt/ftth-demo)
                3. SSH: pull simulator → up -d simulator radius-db
                        → sambung ULANG ftth-server-1 ke network simulator
                       ▼
        ┌──────────────── Server 20.6.72.13 (satu host) ────────────────┐
        │  Stack ftth (PROD, project ftth)      Stack ftth-demo (simulator) │
        │  ┌─ ftth-server-1 (app prod) ─┐        ┌─ simulator 172.30.0.10 ─┐ │
        │  │  net: ftth_default          │  poll  │  SNMP 1161-1165 public  │ │
        │  │  net: ftth-demo_default ────┼───────▶│  DAE :3799 testing123   │ │
        │  └─────────────────────────────┘        └───────────┬────────────┘ │
        │  ftth-caddy-1 (80/443, prod) — TAK disentuh          │ radius-db     │
        │                                          (accounting simulator      │
        │                                           TERPISAH dari DB prod)     │
        └────────────────────────────────────────────────────────────────────┘
```

**Zero-downtime, tanpa polusi accounting:** app prod cukup disambungkan ke network
simulator (`docker network connect`) — hot-attach interface, tanpa restart. Slice
BRAS/RADIUS simulator menulis ke `radius-db` terpisah, jadi accounting tenant prod
tetap bersih.

---

## Bagian A — Bootstrap server (sekali saja)

SSH ke server, lalu:

```bash
# 1) Docker Engine + plugin compose + rsync (kalau belum ada — biasanya sudah, karena prod jalan)
sudo apt-get update && sudo apt-get install -y ca-certificates curl git rsync
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER        # logout-login lagi setelah ini

# 2) Siapkan direktori simulator + .env (file akan diisi via rsync dari CI)
sudo mkdir -p /opt/ftth-demo && sudo chown $USER:$USER /opt/ftth-demo
cd /opt/ftth-demo
# rsync manual pertama dari laptop (atau tunggu deploy CI pertama):
#   rsync -az --exclude .git --exclude '**/build' ./ fajar@20.6.72.13:/opt/ftth-demo/
cp deploy/.env.demo.example .env     # cukup IMAGE_PREFIX + IMAGE_TAG

# 3) Login GHCR (PAT ber-scope read:packages) agar bisa pull image simulator
echo '<GHCR_PAT>' | docker login ghcr.io -u <username-github> --password-stdin

# 4) Nyalakan simulator + radius-db
docker compose -f docker-compose.demo.yml pull simulator
docker compose -f docker-compose.demo.yml up -d radius-db simulator

# 5) Sambungkan app PRODUKSI ke network simulator (idempoten, zero-downtime)
docker network connect ftth-demo_default ftth-server-1
```

**Firewall / security group** — tak perlu buka port apa pun untuk ini. Simulator
(SNMP/DAE) hanya diakses internal lewat network Docker `ftth-demo_default`; tak ada port
yang diekspos ke host.

### Verifikasi simulator terjangkau app prod

```bash
# Dari container app prod, cek simulator menjawab SNMP:
docker run --rm --network ftth-demo_default alpine sh -c \
  'apk add --no-cache net-snmp-tools >/dev/null && snmpget -v2c -c public 172.30.0.10:1161 sysDescr.0'
# → SNMPv2-MIB::sysDescr.0 = STRING: HSGQ-E04I EPON OLT-1 (ftth lab)
```

---

## Bagian B — Daftarkan simulator di aplikasi PRODUKSI (sekali)

Login ke `https://ftth.karuhundeveloper.com` sebagai admin tenant, lalu daftarkan
"perangkat" simulator ini seperti perangkat nyata:

**OLT (SNMP monitoring)** — menu **Jaringan/Inventaris**:
| Field | Nilai |
|---|---|
| Management IP | `172.30.0.10` |
| SNMP port | `1161` (tersedia `1161`–`1165`, tiap port = 1 OLT, 2 PON × 8 ONU) |
| SNMP community | `public` |

Dalam ~1 siklus poll (`FTTH_MONITORING_POLL_INTERVAL` prod), armada ONU palsu akan
terpantau statusnya (online/rx-power/uptime).

**BRAS/NAS (isolir & reset-login via DAE/CoA)** — menu **BRAS/NAS**:
| Field | Nilai |
|---|---|
| Address | `172.30.0.10` |
| CoA/DAE secret | `testing123` (HARUS sama dgn `FTTH_SIM_RADIUS_DAE_SECRET`) |
| Reachability | `DIRECT` |

Uji **Isolir / Reset Login** pada pelanggan uji → app prod menembak DAE ke `172.30.0.10:3799`
→ simulator membalas SUCCESS. Sesi/akunting uji tertulis di `radius-db` simulator (terpisah
dari DB prod), jadi tak mencemari data tenant asli.

> **Catatan accounting:** simulator butuh sesi RADIUS di `radius-db`-nya agar target DAE
> ada. Untuk skenario isolir end-to-end, seed sesi contoh ke `radius-db` bila perlu (mis.
> lewat `docker/lab/seed-lab.sh` yang diarahkan ke DB simulator) — opsional, tergantung apa
> yang mau diuji.

---

## Bagian C — GitHub Secrets untuk auto-deploy

**Tak ada secret baru.** Karena rig ini **co-located di host & user yang SAMA** dengan
produksi, job `deploy-demo` memakai ulang secret SSH prod yang sudah ada:

| Secret | Dipakai untuk | Sudah ada? |
|---|---|---|
| `VPS_HOST` | host SSH (= `20.6.72.13`, sama dgn prod) | ✅ dari job `deploy` prod |
| `VPS_USER` | user SSH (= `fajar`) | ✅ |
| `VPS_SSH_KEY` | private key SSH (rsync + ssh-action) | ✅ |
| `GHCR_USER` / `GHCR_PAT` | login GHCR untuk `pull` image | ✅ |

---

## Bagian D — Deploy otomatis & operasional

Setelah Bagian A–C beres, **cukup `git push origin main`** — job `deploy-demo` di
`.github/workflows/deploy.yml`: **rsync** file ke server, `pull` + `up -d simulator
radius-db`, lalu **menyambung ulang** `ftth-server-1` ke network simulator (idempoten).
Bisa juga dipicu manual dari tab **Actions** (`workflow_dispatch`).

> **Durabilitas penting.** Koneksi `ftth-server-1 → ftth-demo_default` HILANG bila job
> `deploy` prod merecreate container app prod. Karena itu `deploy-demo` `needs: [deploy]`
> dan **selalu** menjalankan `docker network connect` lagi tiap deploy. Kalau app prod
> pernah direcreate DI LUAR CI (mis. `docker compose up -d` manual di `/opt/ftth`),
> jalankan ulang manual: `docker network connect ftth-demo_default ftth-server-1`.

| Mau apa | Perintah (di `/opt/ftth-demo`) |
|---|---|
| Status | `docker compose -f docker-compose.demo.yml ps` |
| Log simulator | `docker compose -f docker-compose.demo.yml logs -f simulator` |
| Restart simulator | `docker compose -f docker-compose.demo.yml restart simulator` |
| Sambung ulang app prod | `docker network connect ftth-demo_default ftth-server-1` |
| Reset bersih (HAPUS data uji) | `docker compose -f docker-compose.demo.yml down -v` |

---

## Catatan

- **Rig-uji, bukan produksi.** Simulator pakai default dev. Slice RADIUS-nya menulis ke
  `radius-db` TERPISAH → accounting tenant prod tak tercemar.
- **Tak menyentuh prod.** Stack ini tak punya Caddy dan tak buka port host; `down -v`/`up`
  di sini tak menjatuhkan situs prod (project & network terpisah). Yang menghubungkan cuma
  interface network yang di-hot-attach ke `ftth-server-1`.
- **IP statis wajib.** Simulator harus 172.30.0.10 (value-object `ManagementIp` menolak
  hostname); subnet tetap `172.30.0.0/24` di blok `networks` compose menjamin ini.
- **Resource:** ringan — hanya 1 JVM simulator + 1 Postgres kecil di samping stack prod.
