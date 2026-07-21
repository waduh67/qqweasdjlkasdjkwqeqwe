# FTTH OSS

Platform SaaS multi-tenant untuk manajemen infrastruktur FTTH: inventory jaringan
(OLT → ODC → ODP → pelanggan), peta GIS jalur kabel, monitoring OLT/ONU,
incident management dengan alarm correlation, work order teknisi, dan RBAC
super-dinamis.

**Status: Phase 0, 1 & 2a selesai** — multi-tenancy, IAM/RBAC dinamis, audit,
inventory jaringan (OLT→ODC→ODP), pelanggan + ONU, peta vector-tile, serta
collector agent + metrik TimescaleDB + mesin alarm sudah berjalan end-to-end.
Adapter vendor sungguhan (Phase 2b) menunggu verifikasi terhadap perangkat
fisik (lihat [Roadmap](#roadmap)).

---

## Stack

| Bagian | Teknologi |
|---|---|
| Backend | Spring Boot 4.1 + Kotlin, JDK 21, Spring Modulith (modular monolith) |
| Database | PostgreSQL + PostGIS + TimescaleDB (metrik deret waktu) |
| Frontend | React 19 + TypeScript + Vite + MapLibre GL (vector tiles) |
| Auth | JWT HS256 (access) + refresh token opaque ber-rotasi |
| Collector | Kotlin agent tanpa Spring — SNMP ke OLT, push outbound |

Monorepo: `server/` (API), `web/` (SPA), `collector/` (agent), `contract/`
(tipe wire collector↔server, Kotlin murni tanpa framework).

---

## Arsitektur

Setiap module Spring Modulith disusun **Clean Architecture / Hexagonal
(ports & adapters)**:

```
com.duluin.ftth.<module>/
├── <Module>Api.kt, *Ref, events     ← API publik lintas-module (base package)
├── domain/                          ← MURNI Kotlin: entity, value object, invariant
│   ├── model/
│   └── catalog/ · service/
├── application/
│   ├── port/inbound/                ← use case interface (dipakai lapisan web)
│   ├── port/outbound/               ← port repository/gateway (istilah domain)
│   └── service/                     ← implementasi use case (@Service @Transactional)
└── adapter/
    ├── inbound/web/                 ← controller + request/response DTO
    └── outbound/persistence/        ← JPA entity + Spring Data repo + adapter + mapper
```

Aturan dependency: **adapter → application → domain**. Lapisan domain tidak tahu
apa pun soal Spring/JPA/HTTP. JPA entity **bukan** model domain — keduanya
dipetakan di adapter. Batas antar-module ditegakkan otomatis oleh test
`ModularityTests`.

Module: `common` (shared kernel, OPEN), `tenancy`, `iam`, `audit`, `network`,
`customer`, `gis`, `monitoring`.

### Ketergantungan antar-module

```
        monitoring ──▶ network ◀── customer
              │           ▲            │
              └───────────┴────────────┘
                     gis ──▶ (network, customer)
```

Aturannya satu arah: `customer → network`, `gis → {network, customer}`,
`monitoring → {network, customer}`. Tiga kasus yang menggoda untuk melanggarnya
diselesaikan tanpa siklus:

- **Aturan port ODP.** Kapasitas & status ODP dimiliki `network`, tapi yang tahu
  port mana terpakai adalah `customer`. Jadi `customer` menyuplai daftar port
  terisi dan `network` yang menegakkan aturannya
  (`NetworkApi.assertOdpPortAssignable`).
- **Larangan menghapus ODP yang masih dipakai.** `network` tidak boleh bertanya
  ke `customer`, jadi arahnya dibalik: `network` mendeklarasikan port
  `OdpUsageProbe` dan `customer` yang mengimplementasikannya. Tanpa ini,
  menghapus ODP berhasil diam-diam dan menyisakan ONU menggantung — pelanggan
  tetap tersambung di lapangan tapi lenyap dari peta.
- **Status ONU dari jaringan.** `monitoring` tahu status sebenarnya dari OLT,
  tapi agregat ONU dimiliki `customer`. Jadi monitoring melapor lewat
  `CustomerApi.recordObservedOnuStatuses` dan customer yang memutuskan — hanya
  baris yang benar-benar berubah yang ditulis.

### Multi-tenancy (dua lapis)

1. **Hibernate `@TenantId`** — mengisi & memfilter `tenant_id` otomatis dari
   `TenantContext`.
2. **Postgres Row-Level Security (FORCE)** — connection provider men-set GUC
   `app.tenant_id` tiap connection dipinjam dari pool. Kalau kode lupa men-set
   tenant, DB tetap tidak membocorkan data tenant lain.

> ⚠️ `spring.jpa.open-in-view` **wajib false**. Bila aktif, sesi Hibernate dibuka
> sebelum filter autentikasi memasang tenant context sehingga seluruh query
> ter-scope ke tenant yang salah — dan gagalnya senyap. `MultiTenancySafetyCheck`
> menggagalkan startup bila setelan ini aktif.

### RBAC dinamis — 3 dimensi

```
Permission = module.resource.action   → iam.role.create, network.odp.update
Role       = kumpulan permission, dibuat/diedit dari UI, per tenant
Scope      = pembatasan data per area/wilayah (mis. teknisi Bekasi)
```

Katalog izin dideklarasikan **di kode** (`PermissionCatalog`, type-safe) lalu
di-seed ke DB saat startup. Karena kodenya terstruktur, UI role-builder merender
matriks *resource × action* secara otomatis — menambah izin baru cukup menambah
satu baris di katalog.

Penegakan: `@PreAuthorize("@authz.can('iam.role.create')")` di controller;
platform admin melewati semua pengecekan.

Scope area diterjemahkan ke satu nilai lewat `AuthenticatedUser.areaScope()`:
`null` = tanpa batas, set berisi = hanya area itu, **set kosong = nol data**
(pengguna yang dibatasi area tapi belum diberi area tidak boleh melihat seluruh
tenant). Query dinamisnya memakai Criteria API, bukan JPQL dengan parameter
nullable — parameter null tanpa tipe membuat Postgres menolak dengan galat
menyesatkan seperti `function lower(bytea) does not exist`.

### Inventory jaringan & GIS

```
SITE ──▶ OLT ──▶ PON PORT ──▶ ODC ──▶ ODP ──▶ ONU ──▶ CUSTOMER
             └ kabel FEEDER ─┘ └ DISTRIBUSI ┘ └ DROP ┘
```

- **Port ODP tidak disimpan sebagai baris.** Kapasitas ada di `odp.capacity`,
  okupansi diturunkan dari ONU yang menempatinya — menghemat ratusan ribu baris
  kosong dan menghilangkan sinkronisasi ganda. "Satu port satu ONU" dijaga
  agregat domain plus indeks unik parsial di database.
- **Panjang kabel diturunkan dari geometri** (termasuk 5% slack), tidak pernah
  diinput manual, agar laporan material selalu cocok dengan jalur yang tergambar.
- **Pasangan ujung kabel divalidasi enum** `CableType` — kabel DROP tidak bisa
  menghubungkan dua OLT.
- **Rugi splitter melekat di `SplitterRatio`** (1:8 = 10,5 dB, dst) sehingga
  telusur jalur bisa menghitung anggaran redaman OLT→pelanggan.
- **Kredensial SNMP dienkripsi AES-256-GCM** (`SecretCipher`) memakai kunci
  terpisah dari kunci JWT; API hanya mengembalikan `snmpConfigured: true/false`,
  tidak pernah nilainya.

**Vector tiles.** Peta dirender penuh di Postgres lewat `ST_AsMVT` — menarik
puluhan ribu geometri ke JVM hanya untuk diserialkan ulang adalah cara paling
pasti membuat peta melambat. Tiap module merender layernya sendiri (`network`:
site/odc/odp/cable, `customer`: customer) dan `gis` menyambung byte-nya; `layers`
adalah repeated field protobuf sehingga hasil sambungan tetap tile yang sah.

> ⚠️ Query native untuk tile **wajib** lewat `EntityManager`, bukan
> `JdbcTemplate`. GUC `app.tenant_id` yang mengaktifkan RLS hanya dipasang pada
> connection yang dipinjam Hibernate (`TenantConnectionProvider`); connection
> yang diambil langsung dari pool tidak punya GUC itu, sehingga RLS menolak semua
> baris dan peta kosong tanpa penjelasan.

**Menggambar kabel di peta.** Jalur kabel digambar langsung di peta, bukan
diketik koordinatnya: klik perangkat sumber → klik titik belok mengikuti lapangan
→ klik perangkat tujuan. Ujung **di-snap** ke perangkat nyata (`queryRenderedFeatures`)
sehingga grafik selalu tersambung — telusur jalur & "siapa terdampak kalau putus"
bergantung padanya. Tipe kabel ditebak dari pasangan ujung (server tetap penjaga),
panjang dihitung dari geometri. Jalur bisa diedit dengan menggeser titik belok.
Logikanya ada di `web/src/map/cableTool.ts` (imperatif, terpisah dari React).

**Peta gaya NOC & status hidup.** Basemap gelap, kabel ramping dengan halo glow
dan dash beranimasi (kesan "mengalir"), aset sebagai lingkaran bercahaya. Warna
ONU pelanggan ikut status hidup dari tile (online hijau / LOS merah / offline
kuning). Kabel yang **hilirnya bermasalah disorot merah berdenyut**: endpoint
`GET /api/gis/impacted` menyusun dari alarm hidup (monitoring) → pelanggan/ODP
terdampak (customer) → geometri kabel yang menyentuhnya (network), lalu klien
menggambarnya sebagai overlay dan menyegarkannya tiap 30 detik. Komposisi lintas
module lewat kontrak publik — `gis` tidak menyentuh tabel milik module lain.

### Monitoring & collector

```
┌── jaringan ISP ──┐                    ┌──── cloud ────┐
│ OLT ◀─SNMP─ agent├──HTTPS outbound───▶│ /api/collector│
└──────────────────┘  (tanpa buka port) └───────┬───────┘
                                                ▼
                              onu_metric (hypertable) → mesin alarm → alarm
```

Collector selalu menyambung **keluar**. ISP tidak perlu membuka port atau
port-forwarding, dan server tak pernah perlu tahu alamat jaringan pelanggan.

- **Konfigurasi datang dari server**, dikirim balik pada tiap denyut. Operator
  menambah OLT atau mengubah interval dari UI dan collector menyesuaikan pada
  siklus berikutnya — tanpa siapa pun masuk ke mesin collector.
- **Autentikasi API key** di rantai keamanan terpisah (`CollectorSecurityConfig`).
  Kuncinya hanya disimpan sebagai SHA-256 dan ditampilkan sekali saat dibuat.
  Tabel `collector` sengaja **tanpa RLS** — barisnya dicari sebelum tenant
  diketahui, persis pola `refresh_token`; pemeriksaan tenant dilakukan eksplisit
  di `CollectorService`.
- **Permukaan collector hanya dua endpoint dan keduanya menulis**, jadi API key
  yang bocor pun tidak bisa membaca data pelanggan.
- **Batch punya id.** Koneksi ISP yang putus-nyambung membuat collector mengirim
  ulang; `ingest_batch` + `ON CONFLICT DO NOTHING` membuat pemeriksaan dan
  penulisan jadi satu operasi atomik, sehingga metrik tidak terhitung ganda.
- **Vendor = data, bukan kelas.** `MibProfile` berisi OID dan skala satuan per
  vendor (ZTE 0,001 dBm; Huawei/Fiberhome 0,01 dBm); alurnya sama untuk semua.
  Menambah vendor = menambah satu profil.
- **Simulator OLT** (`FTTH_COLLECTOR_SIMULATOR=true`) menggantikan seluruh adapter
  SNMP dan menghasilkan populasi ONU yang stabil antar siklus, termasuk satu LOS,
  satu offline, dan beberapa yang redamannya memburuk — supaya mesin alarm benar-
  benar teruji tanpa perangkat fisik.

**Alarm** dirancang agar tidak diabaikan orang: satu entitas hanya punya satu
alarm terbuka per jenis (indeks unik parsial `WHERE status <> 'CLEARED'`), kondisi
berulang menaikkan `occurrenceCount` alih-alih menambah baris, dan kondisi yang
pulih menutup alarmnya sendiri. `SilentCollectorWatchdog` menutup lubang paling
mudah luput: kalau collector-nya sendiri mati, tidak ada data masuk, tidak ada
alarm terpicu, dan dashboard tampak tenang justru saat pemantauan sedang buta.

> ⚠️ **TimescaleDB: RLS mengecualikan kompresi dan continuous aggregate.** Sudah
> diuji, dan larangannya dua arah:
>
> ```
> RLS dulu lalu columnstore : "columnstore cannot be used on table with row security"
> columnstore dulu lalu RLS : "operation not supported on hypertables that have
>                              columnstore enabled"
> CREATE MATERIALIZED VIEW  : "cannot create continuous aggregate on hypertable
>                              with row security"
> ```
>
> Dipilih **isolasi tenant**, karena itu properti keamanan inti sistem ini
> sementara kompresi hanya soal biaya disk yang sudah dibatasi retensi 90 hari.
> Yang tetap didapat dari TimescaleDB: partisi chunk otomatis, chunk exclusion,
> dan kebijakan retensi. Rollup jangka panjang, bila perlu, dikerjakan aplikasi
> per tenant.

Metrik ditulis lewat **JDBC batch di koneksi Hibernate** (`Session.doWork`), bukan
JPA: metrik bukan agregat, dan melacak ribuan objek yang tak satu pun akan diubah
hanya menghabiskan memori. Memakai `DataSource` langsung akan melewatkan GUC
tenant dan RLS menolak seluruh INSERT.

---

## Menjalankan

### 1. Database

Pakai docker-compose (Postgres + Redis + RabbitMQ):

```bash
docker compose up -d
```

Atau Postgres lokal — buat role & database:

```sql
CREATE ROLE ftth LOGIN PASSWORD 'ftth' NOSUPERUSER NOCREATEROLE NOBYPASSRLS;
CREATE DATABASE ftth      OWNER ftth;
CREATE DATABASE ftth_test OWNER ftth;
```

> Role aplikasi **tidak boleh** superuser/`BYPASSRLS`, kalau tidak RLS-nya
> dilewati begitu saja.

PostGIS harus dipasang oleh superuser — justru karena role aplikasi sengaja
bukan superuser, ia tidak bisa membuat extension-nya sendiri:

```bash
sudo pacman -S postgis          # atau: apt install postgresql-17-postgis-3
sudo -u postgres psql -d ftth      -c 'CREATE EXTENSION IF NOT EXISTS postgis;'
sudo -u postgres psql -d ftth_test -c 'CREATE EXTENSION IF NOT EXISTS postgis;'
```

TimescaleDB juga, dan ia perlu di-preload sebelum Postgres start:

```bash
sudo pacman -S timescaledb       # atau: apt install timescaledb-2-postgresql-17
sudo -u postgres psql -c "ALTER SYSTEM SET shared_preload_libraries = 'timescaledb';"
sudo systemctl restart postgresql
sudo -u postgres psql -d ftth      -c 'CREATE EXTENSION IF NOT EXISTS timescaledb;'
sudo -u postgres psql -d ftth_test -c 'CREATE EXTENSION IF NOT EXISTS timescaledb;'
```

Migrasi `V2`/`V3` gagal dengan pesan jelas bila extension-nya belum ada — jauh
lebih baik daripada gagal saat query peta atau ingestion pertama.

### 2. Backend

```bash
./gradlew :server:bootRun
```

Flyway memigrasi skema, lalu bootstrap men-seed katalog izin, tenant `platform`
+ platform admin, dan tenant demo. Swagger: <http://localhost:8080/swagger-ui>

### 3. Frontend

```bash
cd web && npm install && npm run dev
```

Buka <http://localhost:5173> (request `/api` di-proxy ke `:8080`).

### 4. Collector (opsional)

Buat collector di UI **Monitoring → Buat**, salin API key-nya (hanya muncul
sekali), lalu:

```bash
./gradlew :collector:installDist
export FTTH_SERVER_URL=http://localhost:8080
export FTTH_COLLECTOR_KEY=ftthc_xxx        # dari UI
export FTTH_COLLECTOR_SIMULATOR=true       # OLT tiruan, tanpa perangkat fisik
./collector/build/install/collector/bin/collector
```

Tambahkan `FTTH_COLLECTOR_ONCE=true` untuk sekali jalan (berguna untuk pengujian
atau pemasangan bergaya systemd timer). Tanpa `FTTH_COLLECTOR_SIMULATOR`, agent
memakai SNMP sungguhan ke `managementIp` tiap OLT.

### Akun bawaan (dev)

| Peran | Tenant | Email | Password |
|---|---|---|---|
| Platform admin | `platform` | `root@ftth.local` | `rootadmin123` |
| Admin tenant | `demo` | `admin@demo.ftth` | `admin12345` |

Override lewat env: `FTTH_PLATFORM_ADMIN_EMAIL`, `FTTH_PLATFORM_ADMIN_PASSWORD`,
`FTTH_SEED_DEMO=false`. **Di produksi wajib set `FTTH_JWT_SECRET` dan
`FTTH_ENCRYPTION_SECRET`** (masing-masing ≥32 byte, dan harus berbeda).
Mengganti `FTTH_ENCRYPTION_SECRET` membuat kredensial SNMP yang tersimpan tidak
terbaca — OLT-nya tetap tampil, hanya ditandai "belum termonitor" dan perlu diisi
ulang.

> Basemap peta memakai raster tile OpenStreetMap publik — cukup untuk
> pengembangan, tapi kebijakan pemakaian OSM tidak mengizinkan trafik aplikasi
> produksi. Ganti ke penyedia tile berlangganan atau server tile sendiri di
> `MapPage.tsx` sebelum dipakai sungguhan.

---

## Test

```bash
./gradlew :server:test
```

- `ModularityTests` — menegakkan batas module & bebas siklus (statis, tanpa DB).
- `IamEndToEndIT` — lewat HTTP sungguhan (MockMvc) di atas Postgres `ftth_test`:
  isolasi tenant, penegakan RBAC (200 vs 403 vs 401), dan isolasi izin platform.
- `NetworkEndToEndIT` — rantai inventory penuh, aturan port ODP (duplikat → 409,
  di luar kapasitas → 400), penolakan hapus ODP yang masih dipakai, panel ODP &
  telusur jalur lintas-module, kerahasiaan community string SNMP, validasi
  pasangan ujung kabel, isolasi tenant untuk aset jaringan, dan isi vector tile.
- `MonitoringEndToEndIT` — gerbang collector (tanpa key / key salah / JWT
  pengguna semuanya 401, versi protokol beda 426), API key tak pernah
  dikembalikan lagi, deteksi ONU liar, deduplikasi batch, LOS → alarm kritis,
  ambang redaman, peredaman banjir alarm, penutupan otomatis saat pulih, riwayat
  redaman, dan isolasi tenant untuk metrik & alarm.

Test integrasi memakai Postgres lokal (`application-test.yml`), bukan
Testcontainers, karena mesin pengembangan ini tidak punya Docker.

---

## API utama

| Endpoint | Izin |
|---|---|
| `POST /api/auth/login` · `/refresh` · `/logout` | publik |
| `GET /api/me` | terautentikasi |
| `GET/POST/PUT/DELETE /api/users` | `iam.user.*` |
| `PUT /api/users/{id}/access` | `iam.user.assign` |
| `GET/POST/PUT/DELETE /api/roles` | `iam.role.*` |
| `GET /api/permissions` · `/catalog` | `iam.permission.view` |
| `GET/POST/PUT/DELETE /api/areas` | `iam.area.*` |
| `GET /api/audit-logs` | `audit.log.view` |
| `GET /api/platform/tenants` · `POST` · `/{id}/suspend` | `platform.tenant.*` |
| `GET/POST/PUT/DELETE /api/sites` | `network.site.*` |
| `GET/POST/PUT/DELETE /api/olts` · `/{id}/pon-ports` | `network.olt.*` |
| `GET/POST/PUT/DELETE /api/odcs` · `PUT /{id}/uplink` | `network.odc.*` |
| `GET/POST/PUT/DELETE /api/odps` · `PUT /{id}/uplink` | `network.odp.*` |
| `GET/POST/PUT/DELETE /api/cables` | `network.cable.*` |
| `GET/POST/PUT/DELETE /api/customers` | `customer.customer.*` |
| `POST /api/customers/{id}/subscriptions` · `/activate` · `/isolate` | `customer.subscription.update` |
| `POST /api/customers/{id}/onus` · `/onus/{id}/attach` · `/detach` | `customer.onu.assign` |
| `GET /api/gis/tiles/{z}/{x}/{y}.mvt` | `gis.map.view` |
| `GET /api/gis/odps/{id}` | `gis.map.view` + `network.odp.view` |
| `GET /api/gis/trace/customers/{id}` | `gis.map.view` + `customer.customer.view` |
| `GET /api/monitoring/dashboard` | `monitoring.dashboard.view` |
| `GET/POST/PUT/DELETE /api/monitoring/collectors` | `monitoring.collector.*` |
| `GET /api/monitoring/alarms` · `/{id}/acknowledge` · `/clear` | `monitoring.alarm.view` / `.ack` |
| `GET /api/monitoring/onus/{id}/history` | `monitoring.metric.view` |
| `POST /api/collector/heartbeat` · `/metrics` | API key collector (bukan RBAC) |

---

## Roadmap

- **Phase 0 — Fondasi** ✅ tenancy + IAM/RBAC + audit
- **Phase 1 — Inventory + pelanggan + GIS** ✅ OLT→ODC→ODP, kabel bergeometri,
  ONU pada port ODP, peta vector-tile, panel ODP, telusur jalur + anggaran redaman
- **Phase 2a — Monitoring** ✅ collector agent (outbound, API key), protokol
  ber-versi, metrik TimescaleDB, mesin alarm anti-banjir, watchdog collector
  membisu, simulator OLT
- **Phase 2b** — Verifikasi adapter SNMP terhadap OLT sungguhan: OID di
  `MibProfiles` disusun dari dokumentasi MIB publik dan **belum diuji terhadap
  perangkat fisik**; firmware berbeda kerap menggeser sub-tree
- **Phase 3** — Incident + alarm correlation + notifikasi proaktif ke pelanggan
- **Phase 4** — Work order + PWA teknisi (offline, GPS, foto bukti)
- **Phase 5** — What-if simulation, predictive maintenance, OTDR plotting,
  heatmap utilisasi port
