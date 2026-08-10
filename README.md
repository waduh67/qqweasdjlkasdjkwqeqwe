<img src="web/public/logo-netops.svg" alt="NetOps Console" height="48">

# FTTH OSS

> Nama tampilan produknya **NetOps Console**; `ftth` adalah codename internal
> (repo, package, module) yang tak pernah tampil ke pengguna. Panduan lambang &
> palet: [`docs/brand.md`](docs/brand.md).

Platform SaaS multi-tenant untuk manajemen infrastruktur FTTH: inventory jaringan
(OLT → ODC → ODP → pelanggan), peta GIS jalur kabel, monitoring OLT/ONU, incident
management dengan alarm correlation, work order teknisi, manajemen router
pelanggan (GenieACS/TR-069), BRAS/RADIUS (sesi PPPoE), tagihan + pembayaran,
portal pelanggan + helpdesk, akses remote perangkat via VPN, dan RBAC
super-dinamis.

**Status: Phase 0–7 + billing + langganan SaaS + VPN + portal pelanggan berjalan
end-to-end** — multi-tenancy, IAM/RBAC dinamis, audit, inventory jaringan,
pelanggan + ONU, peta vector-tile, polling SNMP sisi server + metrik TimescaleDB
+ mesin alarm, incident + korelasi + notifikasi proaktif (WhatsApp & email), work
order + bukti lapangan, fitur advanced (what-if, heatmap, predictive, OTDR,
auto-provisioning ONU), CPE via GenieACS, BNG (BRAS/RADIUS + RADIUS-as-a-service),
katalog paket, billing (tagihan + auto-isolir + payment gateway per-tenant),
penagihan platform → tenant, portal pelanggan + helpdesk, subscriber-360,
back-haul OpenVPN, serta pengerasan operasional: rem anti-tebak + 2FA operator,
backup terjadwal, dan pemantauan job latar. Hanya adapter SNMP **GPON** vendor
yang menunggu verifikasi terhadap perangkat fisik (lihat [Roadmap](#roadmap)).

---

## Stack

| Bagian | Teknologi |
|---|---|
| Backend | Spring Boot 4.1 + Kotlin, JDK 21, Spring Modulith (modular monolith) |
| Database | PostgreSQL + PostGIS + TimescaleDB (metrik deret waktu) |
| Frontend | React 19 + TypeScript + Vite + MapLibre GL (vector tiles) |
| Auth | JWT HS256 (access) + refresh token opaque ber-rotasi |
| Monitoring | Polling SNMP dari server (bawaan) · agent collector on-prem (opsional) |

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

Module inti: `common` (shared kernel, OPEN), `tenancy`, `iam`, `audit`,
`network`, `customer`, `gis`, `monitoring`. Di atasnya bertumpuk module layanan:
`incident`, `workorder`, `helpdesk` (keluhan yang dilaporkan pelanggan sendiri),
`notification`, `cpe` (router pelanggan via GenieACS/TR-069), `bng` (BRAS/RADIUS,
sesi PPPoE), `catalog` (paket internet), `billing` (tagihan + pembayaran),
`platformbilling` (langganan SaaS platform → tenant), `portal` (portal pelanggan),
`onboarding` (PSB ekspres + impor massal), `subscriber360` (satu layar riwayat
pelanggan), `reporting` (laporan lintas-module), `vpn` (back-haul OpenVPN).

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

### Module layanan di atas inti

Module layanan berkomunikasi **hanya lewat tipe `*Api` publik & event domain**,
tak pernah menyentuh tabel module lain — batas ini ditegakkan `ModularityTests`:

- **incident** — mengorelasikan banjir alarm `monitoring` menjadi satu insiden
  ber-akar-masalah alih-alih puluhan tiket.
- **workorder** — tiket lapangan (PSB/perbaikan/migrasi/dismantle), bukti foto +
  tanda tangan disimpan di MinIO/S3.
- **helpdesk** — keluhan yang **dilaporkan pelanggan sendiri** dari portal jadi
  tiket ber-SLA; operator membalas di utas yang sama dan bisa mengeskalasi satu
  tiket menjadi work order perbaikan. Bedanya dengan `incident`: yang di sini
  lahir dari manusia, yang di sana lahir dari alarm.
- **notification** — broadcast proaktif ke pelanggan terdampak, dua kanal nyata:
  WhatsApp & email (SMTP). Template per-jenis-kejadian bisa disunting operator.
- **cpe** — kelola router/ONT pelanggan lewat GenieACS (TR-069): WiFi, reboot,
  diagnostik ping/speedtest, firmware, factory-reset.
- **bng** — BRAS/RADIUS: paket, registri BRAS, akun PPPoE. Bereaksi atas event
  langganan `customer` untuk memutus/memulihkan sesi PPPoE (adapter Mikrotik REST
  v7 + FreeRADIUS).
- **catalog** — paket internet (kecepatan, harga, siklus, kuota FUP) sebagai satu
  sumber kebenaran: dipakai `customer` saat berlangganan, `billing` saat menagih,
  dan `bng` saat menerjemahkannya jadi profil rate-limit RADIUS.
- **billing** — menerbitkan tagihan atas langganan lalu menggerakkan
  isolir/aktivasi `customer` (yang mengalir ke `bng`) saat jatuh tempo/lunas;
  **payment gateway per-tenant** (Xendit BYO & PLATFORM/xenPlatform; Pivot & Paywuz
  BYO) lewat webhook, kredensial terenkripsi; plus **pembayaran manual** (instruksi
  transfer bank + gambar QRIS di MinIO/S3) saat gateway nonaktif (lihat
  [`docs/payment-gateway.md`](docs/payment-gateway.md)).
- **platformbilling** — penagihan **platform → tenant** (langganan SaaS): harga bulanan
  flat + override khusus saat onboarding, perpanjangan mandiri lewat gateway (masa aktif
  bertambah saat **LUNAS**), auto-suspend/pulih tenant saat menunggak. Level platform
  (tanpa RLS); memakai ulang mesin gateway `billing` lewat named interface `gateway`
  (lihat [`docs/saas-subscription.md`](docs/saas-subscription.md)).
- **portal** — portal pelanggan: identitas & sesi **terpisah** dari operator
  (login pakai email/nomor HP, tanpa perlu tahu kode ISP; lupa password lewat
  email), lihat tagihan & bayar, riwayat pemakaian, status sambungan, dan lapor
  gangguan yang mendarat di `helpdesk`.
- **onboarding** — mempercepat ISP pindah ke sini dan menerima pelanggan baru:
  wizard **PSB ekspres** (pelanggan → ODP → akun akses → work order dalam satu
  formulir) plus impor massal akun PPPoE & pelanggan dari CSV.
- **subscriber360** — satu layar yang menyatukan riwayat seorang pelanggan dari
  semua module (tagihan, sesi, tiket, work order, perangkat) tanpa membuat
  operator berpindah-pindah halaman. Module baca-saja.
- **reporting** — ringkasan lintas-module, dua sudut pandang. **Keuangan**:
  pendapatan & tren, ARPU, **umur piutang** (0/30/60/90+ hari, termasuk yang
  belum jatuh tempo), **pendapatan per paket & per wilayah**, dan **churn**
  (masuk vs berhenti). **Operasional**: **MTTR** gangguan, kecepatan mulai
  dikerjakan, **produktivitas per teknisi**, serta respons & kepatuhan SLA meja
  bantuan. Module baca-saja tanpa tabel sendiri — ia menjahit angka dari kontrak
  publik `billing` + `customer` + `workorder` + `helpdesk` + `iam`, karena tak
  satu pun module boleh tahu uang, paket, dan wilayah sekaligus.
- **vpn** — **swasembada** (tanpa taut lintas-module): VPN-as-a-service. Hub
  OpenVPN adalah infrastruktur **platform** (jalan di VPS kita, IP publik kita,
  app jadi CA-nya sendiri + installer satu-perintah). **Tenant tinggal generate
  akun** yang di-auto-assign ke hub tersedia → kredensial siap tempel di Mikrotik.

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

Di frontend, Platform admin punya **area terpisah** dari operator tenant: shell &
dashboard SaaS sendiri di namespace `/platform/*` (tenant, billing langganan, server
VPN, plus pengguna/role/audit platform). Login sebagai platform admin mendarat di
`/platform`; ia tetap boleh membuka halaman operasional tenant lewat deep-link untuk
inspeksi. Detail: [`docs/saas-subscription.md`](docs/saas-subscription.md).

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
panjang dihitung dari geometri. Jalur yang sudah ada bisa diedit: **seret pegangan
titik-tengah** (titik samar di tengah tiap segmen) untuk membelokkan kabel dalam
satu gerakan — pola editor peta yang lazim, tanpa perlu paham dulu "klik garis
untuk menyisip titik". Titik belok yang ada bisa digeser, dan diklik-ganda untuk
dihapus; kedua ujung terkunci karena harus tetap menempel ke perangkat.
Logikanya ada di `web/src/map/cableTool.ts` (imperatif, terpisah dari React).

**Peta gaya NOC & status hidup.** Basemap gelap, kabel ramping dengan halo glow
dan dash beranimasi (kesan "mengalir"), aset sebagai lingkaran bercahaya. **OLT
adalah marker kelas satu** (koordinat sendiri, biru; kosong = mewarisi lokasi
site-nya) — bukan lagi tersembunyi di dalam site — sehingga perangkat inti terlihat
dan bisa diklik untuk detail vendor/model/IP/SNMP. Warna ONU pelanggan ikut status
hidup dari tile (online hijau / LOS merah / offline kuning). Kabel yang **hilirnya
bermasalah disorot merah berdenyut**, dan **marker perangkat yang terdampak ikut
merah** (OLT modar → marker OLT + seluruh jalur di hilirnya menyala merah): endpoint
`GET /api/gis/impacted` menyusun dari alarm hidup (monitoring) → pelanggan/ODP/OLT
terdampak (customer/network) → geometri kabel yang menyentuhnya (network) plus daftar
`nodes` (id simpul terdampak + keparahan), lalu klien menggambar kabel sebagai overlay
dan mewarnai ulang marker yang id-nya cocok, menyegarkannya tiap 30 detik. Komposisi
lintas module lewat kontrak publik — `gis` tidak menyentuh tabel milik module lain.

### Monitoring: dua jalan masuk metrik

```
A. server-side (bawaan — yang dipakai produksi)
   ┌── jaringan ISP ──┐                   ┌──────── cloud ────────┐
   │ OLT              ├◀── SNMP walk ─────┤ ServerSideOltPoller   │
   └──────────────────┘  (IP publik / VPN)│  tiap 5 mnt, /tenant  │
                                          │                       │
B. collector on-prem (opsional)           │                       │
   ┌── jaringan ISP ──┐                   │                       │
   │ OLT ◀─SNMP─ agent├── HTTPS outbound ─┤ /api/collector        │
   └──────────────────┘  (tanpa buka port)└───────────┬───────────┘
                                                      ▼
                              onu_metric (hypertable) → mesin alarm → alarm
```

**Jalur bawaan hari ini adalah A**: server men-*walk* SNMP OLT langsung tiap 5
menit (`ftth.monitoring.poll-interval`, kill-switch
`ftth.monitoring.server-poll-enabled`), lintas tenant, dengan tenant context
dipasang per tenant supaya RLS tetap menyaring. Alasannya sederhana: ISP tak perlu
memasang apa pun, dan OLT-nya toh sudah terjangkau lewat IP publik atau terowongan
VPN kita. Sudah diadu dengan perangkat sungguhan (HSGQ EPON — perhatikan port SNMP
non-standarnya, 1161).

Jalur **B tetap ada di repo dan tetap diuji, tapi tidak di-deploy** — disiapkan
untuk ISP yang OLT-nya sama sekali tak boleh dijangkau dari luar. Bedanya cuma
siapa yang menjalankan walk-nya: adapter SNMP-nya satu dan sama (module `:snmp`),
jadi tak ada penafsiran MIB yang digandakan. Pada jalur B, collector selalu
menyambung **keluar** — ISP tidak perlu membuka port atau port-forwarding, dan
server tak pernah perlu tahu alamat jaringan pelanggan.

Sisa bagian ini menjelaskan jalur B.

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

> 💡 Ingin seluruh stack (Postgres + MinIO + server + web + RADIUS) dalam satu
> perintah? Lihat [`docs/lab-fullstack.md`](docs/lab-fullstack.md) —
> `docker compose -f docker-compose.lab.yml up -d --build`. Bagian di bawah ini
> untuk pengembangan lokal langkah-demi-langkah.

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
| `POST /api/signup` | publik (daftar tenant sendiri) |
| `GET /api/me` | terautentikasi |
| `GET /api/me/2fa` · `POST /setup` · `/enable` · `/disable` · `/recovery-codes` | terautentikasi (akun sendiri) |
| `GET/POST/PUT/DELETE /api/users` | `iam.user.*` |
| `PUT /api/users/{id}/access` | `iam.user.assign` |
| `POST /api/users/{id}/2fa/reset` | `iam.user.update` |
| `GET/POST/PUT/DELETE /api/roles` | `iam.role.*` |
| `GET /api/permissions` · `/catalog` | `iam.permission.view` |
| `GET/POST/PUT/DELETE /api/areas` | `iam.area.*` |
| `GET /api/audit-logs` | `audit.log.view` |
| `GET /api/platform/tenants` · `POST` · `/{id}/suspend` | `platform.tenant.*` |
| `GET /api/platform/jobs` (kesehatan job terjadwal) | `platform.ops.view` |
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
| `GET/POST /api/monitoring/discovered-onus` · `/auto-provision-policy` | `monitoring.provisioning.*` |
| `POST /api/collector/heartbeat` · `/metrics` | API key collector (bukan RBAC) |
| `GET /api/cables/{id}/otdr` · `POST` · `DELETE` | `network.otdr.*` |
| `GET /api/incidents` · `/{id}` · `POST /{id}/acknowledge` · `/resolve` | `incident.ticket.*` |
| `GET/POST/PUT/DELETE /api/work-orders` · `/dashboard` · `/{id}/assign` · `/start` · `/complete` · `/approve` | `workorder.order.*` / `.dashboard.view` |
| `GET/POST/DELETE /api/work-orders/{id}/evidence` · `/signature` | `workorder.evidence.*` |
| `GET /api/helpdesk/tickets` · `/summary` · `/{id}` | `helpdesk.ticket.view` |
| `POST /api/helpdesk/tickets/{id}/replies` · `/status` · `/escalate` | `helpdesk.ticket.reply` / `.manage` |
| `GET/POST/PUT /api/catalog/plans` | `catalog.plan.view` / `.manage` |
| `GET /api/reports/overview` · `/operations` | `reporting.report.view` |
| `GET /api/subscriber-360/{customerId}` | `customer.customer.view` |
| `POST /api/onboarding/psb` · `/import/pppoe` · `/import/customers` · `GET /export/customers` | `customer.customer.create` (+ `bng.access.*` untuk PPPoE) |
| `POST /api/portal/auth/{login,forgot-password,reset-password,refresh,logout}` | publik (sesi pelanggan, terpisah dari operator) |
| `GET/PUT /api/portal/me` · `/password` · `/billing` · `/invoices/{id}/pay` · `/connection` · `/tickets` | sesi portal pelanggan |
| `GET/POST/DELETE /api/portal-admin/customers/{id}/credential` | `portal.credential.view` / `.manage` |
| `GET/POST /api/notifications/broadcasts` | `notification.broadcast.view` / `.send` |
| `GET/PUT /api/notifications/settings` | `notification.settings.view` / `.manage` |
| `GET/POST/PUT/DELETE /api/notifications/templates` | `notification.template.view` / `.manage` |
| `GET /api/cpe/devices` · `/{id}/live` · `POST /{id}/{reboot,wifi,firmware,factory-reset,refresh}` · `/diagnostics/{ping,speedtest}` | `cpe.*` |
| `GET/POST/PUT/DELETE /api/bng/plans` · `/nas` · `/access` | `bng.plan.*` / `bng.nas.*` / `bng.access.*` |
| `POST /api/bng/access/{id}/isolate` · `/restore` · `/reset-login` · `GET /session` · `/traffic` | `bng.access.isolate` / `bng.session.*` |
| `GET/POST /api/billing/invoices` · `/generate` · `/{id}/void` · `/pay` · `GET /payments` | `billing.invoice.*` / `billing.payment.manage` |
| `GET /api/billing/refunds` · `POST /invoices/{id}/refund` · `/refunds/{id}/settle` | `billing.invoice.view` / `billing.refund.manage` |
| `GET/PUT /api/billing/gateway-settings` · `POST/DELETE/GET /gateway-settings/qris` · `POST /platform/gateway/{tenantId}/xendit-subaccount` | `billing.gateway.view`/`.manage` / `.provision` (platform) |
| `GET /api/billing/manual-payment-instructions` | `billing.invoice.view` |
| `POST /api/billing/webhooks/{tenantSlug}/{provider}` | publik (tanda tangan gateway) |
| `GET/POST/PUT/DELETE /api/vpn/servers` · `/{id}/credentials` · `/regenerate-token` · `/config` (hub platform) | `vpn.server.*` (platform-only) / `vpn.config.view` |
| `GET /api/vpn/accounts` · `POST /generate` · `/{id}/{enable,disable,rotate-password}` · `DELETE` · `/ovpn` · `/routeros` (akun tenant) | `vpn.peer.*` / `vpn.config.view` |
| `GET/POST /api/vpn/provision/{install.sh,authenticate,client-connect}` | token node (tanpa bearer) |

---

## Roadmap

- **Phase 0 — Fondasi** ✅ tenancy + IAM/RBAC + audit
- **Phase 1 — Inventory + pelanggan + GIS** ✅ OLT→ODC→ODP, kabel bergeometri,
  ONU pada port ODP, peta vector-tile, panel ODP, telusur jalur + anggaran redaman
- **Phase 2a — Monitoring** ✅ collector agent (outbound, API key), protokol
  ber-versi, metrik TimescaleDB, mesin alarm anti-banjir, watchdog collector
  membisu, simulator OLT
- **Blast radius di peta** ✅ (potongan fitur unggulan yang dikerjakan lebih awal
  bersama GIS): perangkat mati menyorot merah seluruh kabel hilirnya **beserta marker
  perangkat terdampak** (OLT kini marker kelas satu di peta), klik kabel merah
  menampilkan alarm penyebabnya, dan panel ODC mendaftar pelanggan terdampak
- **Phase 2b — server-side SNMP polling** ✅ server polling OLT langsung (tanpa
  agen on-prem), adapter di modul `:snmp` dipakai bareng collector. Jalur **HSGQ
  EPON** (identitas MAC, tabel enterprise `.50224.3`) **sudah divalidasi terhadap
  HSGQ-E04I sungguhan** end-to-end (poll → kotak masuk). Adapter **GPON**
  (`MibProfiles`: ZTE/HUAWEI/FIBERHOME) OID-nya dari dokumentasi MIB publik dan
  **belum diuji terhadap perangkat GPON nyata** — firmware berbeda kerap menggeser
  sub-tree; simulator OLT menutupi pengujian. Lihat [`docs/monitoring.md`](docs/monitoring.md).
- **Phase 3 — Incident + korelasi + notifikasi** ✅ banjir alarm sejenis (mis. 30
  ONU di bawah satu ODC) menjadi satu insiden ber-akar-masalah, lalu broadcast
  proaktif ke pelanggan terdampak
- **Phase 4 — Work order** ✅ tiket lapangan (PSB/perbaikan/migrasi/dismantle),
  alur assign→mulai→selesai→approve, bukti foto + tanda tangan di MinIO/S3.
  Aplikasi teknisi (Compose Multiplatform) menyusul paling akhir.
- **Phase 5 — Fitur advanced** ✅ what-if/blast-radius, heatmap utilisasi port,
  predictive maintenance, OTDR plotting, auto-provisioning ONU (server polling OLT
  via SNMP → kotak masuk ONU terdeteksi + kebijakan zero-touch, lihat
  [`docs/monitoring.md`](docs/monitoring.md))
- **Phase 6 — CPE** ✅ kelola router/ONT pelanggan via GenieACS (TR-069): WiFi,
  reboot, diagnostik ping/speedtest, firmware, factory-reset & refresh ACS
  (lihat [`docs/cpe.md`](docs/cpe.md); GenieACS ikut di stack prod — `DEPLOY.md` Bagian L)
- **Phase 7 — BNG (BRAS/RADIUS)** ✅ paket, registri BRAS, akun PPPoE,
  isolir/pulih & reset-login sesi; adapter Mikrotik REST v7 + FreeRADIUS, lab
  docker RADIUS (lihat [`docs/lab-bras-radius.md`](docs/lab-bras-radius.md))
- **Billing** ✅ mesin tagihan (invoice ber-periode, jatuh tempo + grace),
  payment gateway **per-tenant** (Xendit BYO & PLATFORM/xenPlatform + auto-provision
  sub-account; Pivot & Paywuz BYO) lewat webhook, **pembayaran manual** (transfer bank
  + gambar QRIS di MinIO/S3) saat gateway nonaktif, auto-isolir/auto-pulih yang
  menggerakkan `customer` → `bng` (lihat [`docs/billing.md`](docs/billing.md) &
  [`docs/payment-gateway.md`](docs/payment-gateway.md))
- **Langganan SaaS** ✅ penagihan platform → tenant: harga bulanan flat + **override
  khusus** saat onboarding, halaman *Langganan Aplikasi* sisi tenant (masa aktif, riwayat
  tagihan, pemakaian kosmetik) + **perpanjangan mandiri** lewat gateway aktif (masa aktif
  bertambah saat **LUNAS**, bukan saat terbit), scheduler auto-suspend/pulih (lihat
  [`docs/saas-subscription.md`](docs/saas-subscription.md))
- **VPN** ✅ VPN-as-a-service untuk remote perangkat tanpa IP publik: hub OpenVPN
  platform (app jadi CA + installer satu-perintah + verifikasi via callback), tenant
  tinggal generate akun (auto-assign) → kredensial siap tempel di Mikrotik, IP overlay
  tetap, unduh `.ovpn`/RouterOS, rahasia terenkripsi (lihat [`docs/vpn.md`](docs/vpn.md))
- **RADIUS-as-a-service** ✅ satu FreeRADIUS pusat melayani banyak tenant & banyak
  router: username kembar antar-tenant boleh (dipisah kode tenant), server yang
  memegang data-plane, client dinamis, CoA lewat overlay VPN (lihat
  [`docs/radius-as-a-service.md`](docs/radius-as-a-service.md) & `DEPLOY.md` Bagian K)
- **Katalog paket** ✅ paket internet sebagai satu sumber kebenaran (kecepatan,
  harga, siklus, kuota FUP) yang dipakai langganan, tagihan, dan profil rate-limit
  RADIUS sekaligus (lihat [`docs/catalog.md`](docs/catalog.md))
- **Portal pelanggan** ✅ identitas & sesi terpisah dari operator: masuk pakai
  email/nomor HP tanpa perlu tahu kode ISP, lupa password lewat email, lihat &
  bayar tagihan, riwayat pemakaian, status sambungan
- **Helpdesk** ✅ pelanggan melaporkan gangguan dari portal → tiket ber-SLA →
  balasan operator di utas yang sama → eskalasi jadi work order perbaikan
- **Subscriber-360** ✅ satu layar riwayat pelanggan: langganan + tagihan + tiket +
  CPE + sesi PPPoE, tanpa berpindah halaman
- **Ops onboarding** ✅ mempercepat ISP pindah & menerima pelanggan: wizard **PSB
  ekspres** (pelanggan → ODP → akun akses → work order dalam satu formulir), akun
  akses lahir `PENDING` dan baru di-provision saat WO PSB selesai, plus impor massal
  PPPoE & pelanggan dari CSV
- **Pengerasan operasional** ✅ rem anti-tebak login (throttle per identitas & per IP,
  operator maupun portal), **2FA operator (TOTP)** dengan kode pemulihan + reset oleh
  admin, email jadi kanal notifikasi kelas satu (WhatsApp & email; kanal yang tak
  pernah punya dispatcher dihapus), cadangan database terjadwal + prosedur pulih yang
  sudah diuji (`DEPLOY.md` Bagian M), dan pemantauan pekerjaan latar yang diam-diam
  berhenti (metrik + watchdog + alert email, `DEPLOY.md` Bagian N)
- **Berikutnya** — drill-down PON/FAT di monitoring, lalu aplikasi teknisi Compose
  Multiplatform. Yang masih menunggu perangkat fisik: adapter SNMP **GPON**
  (ZTE/Huawei/Fiberhome) — jalur EPON sudah divalidasi lapangan.
