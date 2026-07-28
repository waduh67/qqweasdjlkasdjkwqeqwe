# Modul `catalog` — paket internet & provisioning RADIUS-pusat

Sumber **tunggal** definisi paket internet: harga, kecepatan, QoS, FUP, dan
override siklus billing dalam satu agregat `Plan`. Langganan (`customer`) merujuk
`planId` dan men-**snapshot** sisi komersialnya; `bng` membaca **live** sisi
jaringannya lalu menuliskannya ke RADIUS.

Sebelum modul ini, "paket" tercecer di tiga tempat tak bertaut (`RateProfile` di
bng tanpa harga & tak pernah dipush, teks bebas `packageName/monthlyFee/bandwidth`
di subscription, dan `rateProfileName` yang diketik manual). Akibatnya aplikasi
**tak pernah benar-benar mem-provision** user PPPoE — auth & kecepatan diasumsikan
disiapkan manual di luar sistem. Modul `catalog` + jalur-tulis RADIUS di `bng`
menutup lubang itu.

Boundary Spring Modulith ditegakkan `ModularityTests`. `catalog` adalah **sink**:
tak pernah memanggil balik `customer`/`bng`. Arah dependency: `customer → catalog`,
`bng → catalog` (acyclic).

---

## Model domain — `Plan`

```
Plan (agregat)
├── komersial   name · description · price (BigDecimal, scale 2)
├── jaringan    downMbps · upMbps
│               downBurstMbps? · upBurstMbps? · downThresholdMbps? · upThresholdMbps?
│               burstTimeSec? · downMinMbps? · upMinMbps? · priority(1-8, default 8)
│               connectionLimit?               (Simultaneous-Use per akun)
├── FUP         fupEnabled · fupQuotaMb? · fupDownMbps? · fupUpMbps?
├── ketersediaan serviceTypes: Set<PPPOE|STATIC|HOTSPOT|DHCP>   (metadata; enforce PPPoE dulu)
├── override    prorateOnActivation? · billingDayOfMonth? · dueDays? · graceDays? · autoIsolir?
│  billing      (null = ikut kebijakan global billing)
└── active
```

Validasi ketat di domain: harga ≥ 0 (di-`setScale(2, HALF_UP)`), burst ≥ rate &
dua arah bersamaan, threshold hanya bila ada burst, priority 1-8, `serviceTypes`
tak boleh kosong, dan bila `fupEnabled` maka `fupQuotaMb`/`fupDownMbps`/`fupUpMbps`
wajib ada dengan fup-speed ≤ speed normal.

Dua pandangan dieskpos lintas-modul lewat `CatalogApi` (enum internal tak bocor):

- **`PlanCommercialRef`** — dipakai `customer` men-snapshot harga + override siklus
  ke langganan saat create/aktivasi. Invoice tetap stabil walau harga plan berubah.
- **`PlanNetworkRef`** — dipakai `bng` membaca **live** kecepatan + atribut RADIUS
  (`rateLimit` siap tulis, `fupRateLimit`, versi numerik untuk CoA). Ubah kecepatan
  plan → langsung menyebar ke sesi hidup tanpa menyentuh akun.

---

## RADIUS jadi pusat

Tiap paket = **satu grup** RADIUS. Router cukup jadi RADIUS client; membuat atau
mengubah paket = **0 sentuh router**, skala ke ribuan user. Nama grup diturunkan
deterministik dari `planId` (`RadiusGroups`), tak perlu pemetaan terpisah:

```
plan:{planId}        grup normal — kecepatan penuh
plan:{planId}:fup    grup throttle — kecepatan turun setelah kuota FUP habis
```

Akun (`radusergroup`) cukup diikutkan ke grupnya. Atribut kecepatan hidup di
`radgroupreply` tingkat-grup: satu baris mengubah kecepatan **semua** akun di paket.

### Generator rate-limit (ganti input manual)

`Plan.rateLimitString()` merakit atribut **Mikrotik-Rate-Limit** (VSA vendor 14988
tipe 8) dari field terstruktur. Urutan **`up/down`** (rx/tx), field opsional dipangkas
dari kanan mengikuti tata-bahasa MikroTik:

```
{up}M/{down}M  {upBurst}M/{downBurst}M  {upThresh}M/{downThresh}M  {upTime}/{downTime}  {priority}  {upMin}M/{downMin}M
```

| Field terisi | Hasil |
|---|---|
| rate saja (50↓/10↑) | `10M/50M` |
| + burst | `10M/50M 20M/100M` |
| + threshold + time | `10M/50M 20M/100M 15M/75M 8/8` |
| priority non-default | `10M/50M 0M/0M 0M/0M 0/0 1` (placeholder mengisi grup sebelumnya) |
| + limit-at | `10M/50M 0M/0M 0M/0M 0/0 8 5M/25M` |

`fupRateLimitString()` merakit rate throttle grup FUP (`"{fupUp}M/{fupDown}M"`),
null bila FUP nonaktif. UI menampilkan **preview live** string ini saat operator
mengisi form — tak ada lagi ketik nama profil.

---

## Jalur-tulis RADIUS (`bng` → collector)

`bng` mengubah maksud (provision, ganti paket, hapus, FUP) menjadi baris `bng_action`
yang diklaim collector lewat denyut, lalu ditulis ke RADIUS via JDBC. Password
**tidak** disimpan di `bng_action` — diresolusi+dekripsi dari `SubscriberAccess.secret`
saat klaim, diangkut lewat kanal TLS (tak ada cleartext at-rest baru).

| `BngActionType` | Tulisan RADIUS (idempoten, DELETE-lalu-INSERT) |
|---|---|
| `PROVISION` | `radcheck` (Cleartext-Password) + `radusergroup` (keanggotaan grup) |
| `DEPROVISION` | hapus `radcheck`/`radreply`/`radusergroup` by username |
| `SYNC_GROUP` | `radgroupreply` (Mikrotik-Rate-Limit) + `radgroupcheck` (Simultaneous-Use) + baris grup FUP |
| `COA` | RFC 5176 Change-of-Authorization (kecepatan sesi hidup, tanpa putus) |
| `DISCONNECT` | RFC 5176 Disconnect (isolir / Reset Login) |

**Kunci swap FUP:** karena `PROVISION` melakukan `DELETE FROM radusergroup ... THEN
INSERT`, memindahkan keanggotaan grup akun cukup dengan PROVISION ulang ke groupname
lain — swap-nya **atomik**. Itu sebabnya throttle/pulih FUP memakai ulang PROVISION,
bukan tipe aksi/wire baru.

### Hook provisioning

```
provision(akun)          → SYNC_GROUP(plan) + PROVISION(akun ke grup normal)
updateAssignment(plan²)  → SYNC_GROUP(plan²) + PROVISION(remap grup) + CoA(kecepatan²)
delete / onTerminated    → DEPROVISION(akun)
onIsolated               → DISCONNECT
listener PlanUpdated      → SYNC_GROUP(plan) + CoA ke seluruh sesi hidup paket itu
```

Ganti kecepatan sebuah paket → satu `SYNC_GROUP` (satu baris `radgroupreply`) + CoA
ke sesi hidup. Inilah nilai konkret "RADIUS-pusat".

---

## Mesin FUP (fair-usage)

`FupScheduler` (`@Scheduled`) menegakkan FUP lintas tenant berkala; kerjanya di
`FupCycleRunner` dalam transaksi `REQUIRES_NEW` per tenant (pola sama scheduler
billing/CPE).

```
FupScheduler  @Scheduled(fixedDelayString = "${ftth.bng.fup-scheduler-interval:PT1H}")
  └─ tiap tenant aktif → TenantContext.runAs → FupCycleRunner.run()
       ├─ ambil akun ACTIVE ber-BRAS pada paket ber-FUP & berkuota
       ├─ hitung pemakaian periode (usageSince) — sekali, satu query batch
       └─ bandingkan dgn kuota:
            > kuota & belum throttle → enqueueApplyFup  (PROVISION grup :fup + CoA turun) + tandai fupThrottled
            ≤ kuota & masih throttle → enqueueClearFup  (PROVISION grup normal + CoA penuh) + cabut fupThrottled
```

**Pemakaian dihitung di server** dari hypertable `accounting_record` yang sudah
di-ingest tiap poll — bukan kanal usage baru di collector. Query `usageSince`
menjumlah `in_octets + out_octets` per akun sejak awal siklus, **sadar-reset**:
penghitung kumulatif yang mundur (sesi baru menyetel ulang counter) dihitung penuh,
bukan jadi selisih negatif; titik pertama tiap akun jadi baseline (byte sebelum awal
siklus tak terhitung). Seragam lintas semua adapter BRAS, tanpa perubahan protokol.

Bendera `SubscriberAccess.fupThrottled` mencegah antre-ganda: remap sekali saat
pertama melampaui, pulih sekali saat turun/rollover. Awal siklus = hari-1 bulan
berjalan (zona sistem, selaras penerbit invoice).

Panel akun pelanggan menampilkan indikator FUP: pemakaian/kuota (MB) + status throttle.

---

## Prorate tagihan pertama

`InvoiceGenerator.generateFor` memprorata tagihan pertama bila langganan aktif di
tengah periode. Berlaku saat `prorateOnActivation` (override paket → snapshot
langganan; null = ikut global `ftth.billing.prorate-on-activation`) menyala dan
`activatedAt` berada di dalam periode:

```
amount = price × hariTersisa / lengthOfMonth
```

`hariTersisa` dihitung dari tanggal aktivasi sampai akhir periode. Invoice menyimpan
`prorated: Boolean` + `proratedDays: Int?` (satu titik `amount`, konsisten antara
invoice & charge gateway). UI menandai baris tagihan berprorata dengan badge
`prorata Nh`.

---

## Konfigurasi

| Properti | Bawaan | Guna |
|---|---|---|
| `ftth.bng.fup-scheduler-interval` | `PT1H` | selang penegakan FUP |
| `ftth.billing.prorate-on-activation` | `false` | prorata global (dioverride per paket) |

---

## API & izin

| Endpoint | Izin |
|---|---|
| `GET /api/catalog/plans` · `/{id}` | `catalog.plan.view` |
| `POST/PUT/DELETE /api/catalog/plans` | `catalog.plan.manage` |

Izin `catalog.plan.*` non-platform, jadi otomatis masuk role **Tenant Admin**
(semua izin non-platform, di-sinkron idempoten `AdminProvisioner` saat onboarding).
Hapus paket yang masih dirujuk akun/langganan ditolak (guard).

---

## Kaitan lintas-module

```
customer ──findPlanCommercial (snapshot harga)──▶ catalog ◀──findPlanNetwork (live speed)── bng
   │  snapshot mengalir ke billing lewat BillableSubscription             │
   ▼                                                                       ▼
billing (prorate, siklus per-plan)                          RADIUS (radcheck/radusergroup/radgroupreply)
```

`catalog` tak pernah menyentuh tabel modul lain; `billing` tak depend `catalog`
langsung (baca snapshot komersial via `customer`). Batas ditegakkan `ModularityTests`.
