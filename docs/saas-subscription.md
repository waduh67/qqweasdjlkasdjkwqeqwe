# Langganan SaaS multi-tenant (flat + override + self-service)

Model penagihan **platform ke tenant** (bukan tenant ke pelanggannya — itu
[`billing`](billing.md)). Satu tenant = satu langganan aplikasi berbiaya bulanan **flat**,
dengan **override harga khusus** saat onboarding dan **perpanjangan mandiri** lewat gateway
pembayaran yang sama seperti tagihan pelanggan.

Tiga keputusan produk yang membentuk desain:

1. **Flat + override** — satu harga default global untuk semua tenant; super-admin bisa
   menimpanya dengan harga khusus saat membuat tenant baru.
2. **Masa aktif bertambah saat LUNAS** — tenant klik "Perpanjang" → tagihan terbit → bayar
   lewat gateway aktif → masa aktif memanjang **saat pembayaran settle**, bukan saat tagihan
   terbit.
3. **Halaman "Langganan Aplikasi" sisi tenant** — masa aktif + status, riwayat tagihan,
   tombol perpanjang, dan pemakaian **kosmetik** ("OLT 10 / Unlimited" — tanpa batas nyata).

Modul: **`platformbilling`**. Level PLATFORM — semua tabelnya **tanpa RLS** (tenant `platform`
adalah rumah super-admin, bukan pelanggan aplikasi, jadi dikecualikan dari penagihan).

---

## Model data

Semua di level platform (tanpa RLS, tanpa `tenant_id` sebagai GUC filter — `tenant_id` di sini
hanya kolom penunjuk pemilik langganan).

| Tabel | Migrasi | Isi |
|---|---|---|
| `platform_setting` | **V59** (+`default_monthly_fee` di **V62**; `active_payment_provider` dibuang **V69**) | satu baris setelan global: hari tagih, masa tenggang, **harga bulanan default** |
| ~~`platform_payment_gateway`~~ | **V59**, **dibuang V69** | *(usang)* kredensial gateway platform — diganti singleton `pivot_master_config` (V70), lihat [`pivot-overview.md`](pivot-overview.md) |
| `tenant_subscription` | **V60** | satu baris per tenant: `monthly_fee`, `status`, periode aktif, jadwal tagih |
| `tenant_subscription_invoice` | **V61** | tagihan langganan ber-periode (ISSUED/PAID/OVERDUE/VOID) |
| `tenant_subscription_payment` | **V61** | pelunasan (dari webhook gateway atau catatan manual super-admin) |

### `tenant_subscription` — mesin keadaan

```
tenant_subscription (satu baris per tenant)
├── monthly_fee            biaya bulanan efektif (override ?: default global saat dibuat)
├── status                 ACTIVE | PAST_DUE | SUSPENDED | CANCELLED
│                          SUSPENDED = konsol tenant BACA-SAJA (bukan tenant dikunci)
├── billing_day / grace    null = ikut default global (platform_setting)
├── current_period_start   masa aktif — awal
├── current_period_end     masa aktif — akhir  ← "Active until"; HANYA bertambah saat LUNAS
├── next_invoice_at        kapan scheduler boleh menerbitkan tagihan berikutnya
└── activated_at           kapan pertama kali aktif
```

Perpindahan status memicu efek nyata (kunci baca-saja saat menunggak, terbuka lagi saat lunas),
jadi dijaga mesin keadaan eksplisit di domain — bukan setter bebas. `isLocked` (= `SUSPENDED`)
adalah satu-satunya sumber kebenaran kunci itu; lihat
[Menunggak = baca-saja, bukan terkunci](#menunggak--baca-saja-bukan-terkunci).

---

## Harga: default global + override onboarding

- **Default global** disimpan di `platform_setting.default_monthly_fee`, disunting di halaman
  *Billing Langganan Platform* (izin `platform.billing.manage`). Satu angka, berlaku untuk
  semua tenant yang tak diberi harga khusus.
- **Override khusus** dikirim saat onboarding tenant. Form "Onboarding tenant" punya kolom
  *Harga bulanan khusus* (kosong = pakai default). `OnboardTenantCommand.monthlyFee: BigDecimal?`
  mengalir ke provisioning langganan; `null` → pakai `default_monthly_fee`.

```kotlin
// TenantSubscriptionProvisioningService.ensureForTenant
val defaultFee = (settingRepository.find() ?: PlatformSetting.default()).defaultMonthlyFee
val monthlyFee = monthlyFeeOverride ?: defaultFee   // override menang; else default global
```

**Idempotent**: tenant yang sudah punya langganan dilewati (tak menimpa harga berjalan). Ini
membuat provisioning onboarding, backfill start-up, dan onboarding-ulang aman dipanggil berkali-kali.

---

## Masa aktif bertambah saat LUNAS (bukan saat terbit)

Inti requirement #2. Penerbitan tagihan dan perpanjangan masa aktif **dipisah**:

| Peristiwa | Yang berubah | Yang TIDAK berubah |
|---|---|---|
| Langganan dibuat | `seedInitialPeriod` → masa aktif **1 bulan** + tagihan pertama dijadwalkan menjelang habis | — |
| Tagihan **terbit** (scheduler) | `scheduleNextInvoice` → hanya majukan `next_invoice_at` | `current_period_end` (masa aktif) |
| Tagihan **LUNAS** (webhook/manual) | `extendOnPayment` → `current_period_end` +1 bulan | — |

```kotlin
// Perpanjangan menumpuk bila masa aktif belum habis; mulai baru bila sudah lewat.
// [months] > 1 saat tenant bayar di muka beberapa bulan sekaligus.
fun extendOnPayment(today: LocalDate, months: Long = 1) {
    if (status == SubscriptionStatus.CANCELLED) return
    val span = months.coerceAtLeast(1)
    val end = currentPeriodEnd
    if (end == null || end.isBefore(today)) {          // lewat / belum pernah aktif
        currentPeriodStart = today
        currentPeriodEnd = today.plusMonths(span)
    } else {                                            // masih aktif → tumpuk di ujung
        currentPeriodEnd = end.plusMonths(span)
    }
    if (activatedAt == null) activatedAt = Instant.now()
}
```

**Jumlah bulan diturunkan dari periode tagihan** (tanpa kolom baru): saat LUNAS,
`months = ChronoUnit.MONTHS.between(periodStart, periodEnd + 1 hari)`. Perpanjangan multi-bulan
menerbitkan satu tagihan `biaya × N` berperiode N bulan; scheduler pun tak menagih dobel karena
`next_invoice_at` dilompatkan melewati seluruh bulan prabayar.

**Kenapa `seedInitialPeriod`?** Tanpa masa aktif awal, tenant baru langsung punya tagihan jatuh
tempo dan `PlatformBillingScheduler` akan mengunci konsolnya di siklus berikutnya sebelum sempat
membayar. Memberi 1 bulan aktif di depan (tagihan pertama terbit menjelang habis) menutup lubang itu.

---

## Perpanjangan mandiri (self-service)

`POST /api/subscription/renew?months=N` (izin `billing.subscription.renew`, `N` 1..12, default 1):

1. Bila sudah ada tagihan **tertunggak** (ISSUED/OVERDUE) → kembalikan yang itu (jangan bikin dobel;
   `months` diabaikan — bayar dulu yang tertunggak).
2. Bila tidak → terbitkan tagihan baru (`issueFor(..., force = true, months = N)`) lewat gateway
   aktif; nilai `biaya × N`, periode membentang N bulan **menyambung** dari ujung masa aktif berjalan;
   kembalikan `payUrl`.
3. Tenant membayar di **halaman bayar publik** `/bayar/{tenantSlug}/{invoiceId}` — halaman yang sama
   dengan tagihan pelanggan, dibuka di tab baru dan tautannya bisa disalin (lihat
   [`billing.md`](billing.md#halaman-bayar-publik-bayartenantsluginvoiceid)). Modal bayar di halaman
   langganan sudah dihapus; halaman publik itu realm kedua yang mencari tagihan di
   `tenant_subscription_invoice` setelah realm tagihan pelanggan tak menemukan apa pun. Saat webhook
   **settle**, `PlatformPaymentService.applySettlement` → `extendOnPayment(today, N)` → masa aktif
   memanjang N bulan.

**Bayar di muka (1 / 3 / 6 / 12 bulan)**: tenant memilih durasi di halaman langganan sebelum
menekan *Perpanjang*. Ini murni opsional — scheduler tetap menerbitkan tagihan bulanan otomatis
menjelang masa aktif habis; tombol hanya mempercepat / memborong beberapa bulan.

Pesan UI eksplisit: *"Masa aktif bertambah setelah pembayaran LUNAS."* — tak ada perpanjangan
optimistis di sisi terbit.

---

## Bonus bulan gratis (super-admin)

`POST /api/platform/tenants/{tenantId}/subscription/grant` (izin `platform.subscription.manage`,
body `{months: 1..24, reason?}`) — promo, kompensasi gangguan, atau memperpanjang masa percobaan
tanpa menagih tenant. Panelnya ada di *Langganan* pada `/platform/tenants`.

Bonus **tidak** memutasi masa aktif langsung: ia menempuh jalur LUNAS yang sama supaya tak ada
sumber kebenaran kedua atas `current_period_end`.

1. Tunggakan yang ada (ISSUED/OVERDUE) di-**VOID**. Wajib: tanpa ini `PlatformBillingRunner.enforce()`
   akan mengunci ulang konsolnya karena tagihan lama dan bonusnya percuma.
2. Terbit tagihan **`FREE-<yyyymm>-<tenant8>` senilai Rp 0** berperiode N bulan, menyambung dari ujung
   masa aktif berjalan (atau mulai hari ini bila sudah lewat). Bentrok nomor diselesaikan sufiks `-R2`,
   `-R3`, … lewat `PlatformInvoiceGenerator.grantNumber`.
3. `PlatformPaymentService.recordGrant` melunasinya dengan penyedia semu **`GRANT`** (nilai 0, `reason`
   tersimpan sebagai `note`) → `extendOnPayment` menambah masa aktif → `reactivateIfCleared` membuka
   kunci baca-saja. Tenant yang berstatus SUSPENDED **tidak** ikut dihidupkan: status itu kini hanya
   bisa dipasang tangan admin platform, dan bonus bulan gratis bukan alasan membatalkan keputusannya.
4. `deferNextInvoiceToPeriodEnd()` menggeser `next_invoice_at` ke ujung masa aktif baru → tak ditagih
   selama masa bonus.

Jejaknya terlihat dua sisi: tagihan berbadge **Bonus** (`SubscriptionInvoiceView.grant`, diturunkan
dari awalan nomor) muncul di panel super-admin dan di `/subscription` milik tenant, ditambah audit
`platform.subscription.granted`. Langganan `CANCELLED` ditolak — buat langganan baru dulu.

---

## Scheduler penagihan (`PlatformBillingScheduler`, tiap `PT12H`)

Level platform → **tidak** `TenantContext.runAs` (beda dari `BillingScheduler` tenant). Tiap
langganan diproses dalam transaksi `REQUIRES_NEW` agar kegagalan satu tak menghentikan lainnya.

- `issueInvoices()` — terbitkan tagihan untuk langganan yang `next_invoice_at` sudah due.
- `enforceOverdue()` — tandai tagihan lewat jatuh tempo → OVERDUE (langganan → PAST_DUE); bila
  menunggak **melewati masa tenggang** → langganan SUSPENDED, dan konsol tenant jadi **baca-saja**.
  Penguncian didorong oleh **tagihan tertunggak**, bukan sekadar `current_period_end` lewat.

Tenant-nya sendiri **tidak** ikut di-suspend, dan `lockGuard.invalidate(tenantId)` dipanggil di
ujung `enforce` supaya kunci baru tak menunggu TTL cache habis. Audit: tagihan →
`platform.subscription.invoice.overdue`, kunci → `platform.subscription.tenant.locked`.

Pelunasan seluruh tunggakan mengembalikan langganan ke ACTIVE (`reactivateIfCleared`) dan membuka
kuncinya seketika.

---

## Menunggak = baca-saja, bukan terkunci

Aturan lama men-`suspend()` **tenant**-nya, dan `AuthenticationService` menolak login tenant
non-ACTIVE. Hasilnya lubang tanpa jalan keluar: ISP yang telat bayar tak bisa masuk sama sekali —
termasuk untuk membayar tagihan yang membuka kuncinya — dan datanya seolah lenyap. Sekarang
tunggakan **mengunci konsol jadi baca-saja**, bukan mengunci orangnya di luar.

| | Menunggak (langganan SUSPENDED) | Suspend manual platform admin |
|---|---|---|
| Dipasang oleh | scheduler, otomatis | tangan super-admin di `/platform/tenants` |
| Login operator | ✅ berhasil | ❌ "Tenant tidak aktif" |
| Baca (`*.view`) | ✅ semua | ❌ |
| Tulis | ❌ **402** `SUBSCRIPTION_LOCKED` | ❌ |
| Bayar langganan | ✅ tetap boleh | ❌ |
| Portal pelanggan | ✅ penuh, termasuk membayar | ❌ |
| Dibuka oleh | pelunasan tunggakan (seketika) | super-admin |

### Siapa yang menegakkan

Satu titik cekik: **`AccessChecker`** (`@authz.can(...)`) — gerbang yang dilewati hampir setiap
operasi tulis di aplikasi ini, dengan konvensi penamaan izin yang konsisten (`*.view` membaca,
sisanya menulis — lihat `PermissionCatalog`). Menyaring berdasarkan method HTTP salah menuduh
endpoint POST yang sebenarnya cuma membaca (pencarian, pratinjau, ekspor) dan tak menyentuh jalur
non-HTTP sama sekali.

```
common.AccessChecker.can("customer.create")
   └─ punya izin? ──▶ izin tulis? ──▶ ReadOnlyLockGuard.isReadOnly() ──▶ SubscriptionLockedException
                                          ▲ ObjectProvider (opsional)
platformbilling.SubscriptionLockGuard ────┘  subscription.status == SUSPENDED, cache 60 detik
```

- **Antarmuka `ReadOnlyLockGuard` tinggal di `common`, implementasinya di `platformbilling`** —
  inversi dependensi, karena `common → platformbilling` menutup siklus modul. Di-inject sebagai
  `ObjectProvider` supaya konteks tanpa module platformbilling (test unit, potongan aplikasi)
  tetap berjalan dengan perilaku lama: tak ada penjaga = tak ada yang terkunci.
- **Melempar, bukan mengembalikan `false`.** Dua keadaan ini menuntut jawaban berbeda: "izinmu
  kurang" (403) berarti hubungi admin, "langgananmu menunggak" (402) berarti bayar. `false` yang
  sama untuk keduanya menyembunyikan bedanya. Exception dari dalam SpEL `@PreAuthorize` merambat
  utuh ke `GlobalExceptionHandler` → `402 Payment Required` ber-`code: "SUBSCRIPTION_LOCKED"`
  (pola yang sama dengan `TWO_FACTOR_REQUIRED`).
- **Cache 60 detik** di `SubscriptionLockGuard`: pemanggilnya adalah cek izin di **setiap**
  request yang menulis. `invalidate(tenantId)` yang membuat pelunasan terasa seketika; TTL cuma
  jaring pengaman. Kegagalan baca (tabel belum termigrasi, koneksi putus) dijawab **"terbuka"** —
  kunci ini menutup seluruh konsol, jadi ketidakpastian tak boleh menguncinya.

### Apa yang tetap hidup

| Tetap jalan | Alasan |
|---|---|
| Semua izin `*.view` | data tenant tak disandera; konsol tetap terbaca utuh |
| `billing.subscription.renew` (`ALWAYS_ALLOWED`) | tanpa ini kuncinya menelan dirinya sendiri |
| `/api/portal/**` | pelanggan tetap melihat & **membayar** — itu sumber uang yang melunasi langganan |
| `/api/public/**`, `/api/auth/**`, `/api/me/2fa` | tak melewati `@authz.can` sama sekali |
| Seluruh endpoint `platform.*` | platform admin selalu di tenant `platform`, yang tak pernah terkunci |

### Sisi web

- `GET /api/subscription/lock` → `{ locked, daysOverdue, dueDate, amountDue, currency, invoiceId }`.
  **Tanpa `@PreAuthorize`** (cukup terautentikasi) supaya teknisi/CS yang tak punya izin billing
  pun tahu kenapa aplikasinya membeku.
- `client.ts` menyalakan handler `onSubscriptionLocked` saat 402 ber-`code=SUBSCRIPTION_LOCKED`,
  lalu **tetap** melempar `ApiError` agar halaman pemanggil tetap menampilkan toast-nya.
- `useCan` mencerminkan `AccessChecker`: saat terkunci, izin non-`*.view` (kecuali
  `billing.subscription.renew`) mengembalikan `false` — satu perubahan itu mematikan ratusan
  tombol aksi tanpa menyunting satu halaman pun. Tetap kosmetik; penegakan sungguhan di server.
- Banner merah menetap di `Layout`, plus redirect **sekali** ke `/subscription` pada mount pertama
  setelah login (disimpan di `ref`) — "baca-saja", bukan "redirect paksa total".
- `/subscription` menampilkan panel penjelas (nominal, jatuh tempo, umur tunggakan). Bagi yang tak
  punya `billing.subscription.renew`, panel yang sama muncul **tanpa tombol bayar** disertai arahan
  menghubungi admin ISP — bukan halaman kosong tanpa penjelasan.

**Tenant yang terlanjur tersuspend** aturan lama dipulihkan sekali jalan oleh
`TenantSubscriptionBackfillRunner`: tenant SUSPENDED yang langganannya juga SUSPENDED (= alasannya
memang menunggak) dikembalikan ke ACTIVE, karena kuncinya kini datang dari status langganan.

---

## Batas modul (Spring Modulith)

Dua ketegangan arsitektur, diselesaikan tanpa melanggar [`ModularityTests`](../README.md)
(no-cycle + hanya akses tipe ter-expose):

### 1. `iam → platformbilling` menutup siklus → pakai **event**

Onboarding hidup di `iam`; provisioning langganan di `platformbilling`. Pemanggilan port langsung
`iam → platformbilling` menutup siklus `iam → platformbilling → billing → customer → network → iam`.
Solusinya event domain, bukan panggilan statis:

```
iam.TenantOnboardingService  ──publish──▶  iam.TenantOnboardedEvent(tenantId, monthlyFeeOverride?)
                                                     │  @TransactionalEventListener(AFTER_COMMIT)
platformbilling.TenantOnboardedListener  ◀──────────┘  → ensureForTenant(...)
```

Event `TenantOnboardedEvent` diletakkan di **base package `iam`** (permukaan publiknya) — hanya
iam yang menerbitkan, dan platformbilling boleh bergantung pada iam. Listener berjalan AFTER_COMMIT
(dengan `fallbackExecution=true`): langganan dibuat setelah baris tenant ter-commit. Kegagalan
listener tak menggagalkan onboarding — **backfill start-up** menambal langganan yang belum dibuat.

### 2. `platformbilling → billing` memakai mesin gateway → named interface `gateway`

`platformbilling` menagih lewat akun MASTER Pivot yang **sama** seperti tagihan pelanggan
([`docs/pivot-overview.md`](pivot-overview.md)), jadi butuh `PaymentGatewayRegistry`,
`ChargeRequest`, `PaymentSettlement`, `ResolvedGatewayContext`, dan `PivotMasterConfigProvider`
— tipe **internal** billing. Alih-alih menembus enkapsulasi, tipe-tipe itu di-expose sebagai
**named interface** Spring Modulith:

```kotlin
@NamedInterface("gateway")
interface PaymentGateway { ... }          // + ChargeRequest/Result, PaymentSettlement, GatewayCallback,
@NamedInterface("gateway")                //   PaymentGatewayRegistry, ResolvedGatewayContext, GatewayMode,
data class ResolvedGatewayContext(...)    //   PivotMasterContext/FeeType, PivotMasterConfigProvider
```

`platformbilling → billing :: gateway` kini sah menurut ModularityTests, tanpa membuka seluruh
sub-package billing. `PlatformGatewayResolver` menyusun konteks charge dari
`PivotMasterConfigProvider.current()`: langsung di akun master, **tanpa** `x-submerchant-id` &
**tanpa** split fee → 100% dana masuk platform (pemasukan platform, bukan tenant). Bila master
Pivot belum dikonfigurasi/aktif, charge langganan menolak dengan pesan jelas (bukan diam-diam
gagal).

### 3. Aktifkan tenant → lewat `TenantApi`

Method `suspend(id)`/`activate(id)` **dipromosikan** ke `TenantApi` (kontrak lintas-module
ter-expose) alih-alih memakai `ManageTenantUseCase` (use case web internal tenancy) — menutup satu
lagi akses tipe non-exposed. Sejak menunggak cuma mengunci konsol jadi baca-saja, `platformbilling`
tak lagi memanggil `suspend()` sama sekali; `activate()` tersisa **hanya** di
`TenantSubscriptionBackfillRunner` untuk memulihkan tenant yang terlanjur tersuspend aturan lama.

### 4. Kunci baca-saja ditegakkan `common` → port dibalik

Penjaga izin (`AccessChecker`) tinggal di `common`, tapi jawabannya ada di `platformbilling` —
sementara `common → platformbilling` menutup siklus. Diselesaikan dengan **inversi**: antarmuka
`ReadOnlyLockGuard` didefinisikan di `common`, `SubscriptionLockGuard` mengimplementasikannya di
`platformbilling`, dan `AccessChecker` menerimanya sebagai `ObjectProvider` (opsional). Detailnya
di [Menunggak = baca-saja, bukan terkunci](#menunggak--baca-saja-bukan-terkunci).

---

## Izin & endpoint

| Izin | Untuk |
|---|---|
| `billing.subscription.view` | tenant: lihat langganan sendiri (auto ke role Tenant Admin) |
| `billing.subscription.renew` | tenant: perpanjang mandiri |
| `platform.subscription.view` | super-admin: lihat langganan & tagihan semua tenant |
| `platform.subscription.manage` | super-admin: kelola biaya, tagihan, pembayaran, bonus bulan gratis |
| `platform.billing.view` / `platform.billing.manage` | super-admin: setelan + gateway platform |

| Endpoint | Izin |
|---|---|
| `GET /api/subscription` · `POST /api/subscription/renew?months=N` | `billing.subscription.*` (tenant) |
| `GET /api/subscription/lock` | **tanpa izin** (cukup terautentikasi) |
| `GET/PUT /api/platform/billing/settings` | `platform.billing.*` |
| `.../tenants/{id}/subscription` · `/invoices` · `/invoices/{id}/pay`·`/void` · `/grant` · `/cancel` | `platform.subscription.*` |
| `POST /api/platform/billing/webhooks/{provider}` | publik (verifikasi tanda tangan gateway) |

---

## Backfill & pengecualian tenant platform

`TenantSubscriptionBackfillRunner` (`ApplicationRunner`, `@Order(2)`) memastikan **setiap** tenant
lama punya langganan (harga default) supaya halaman Langganan langsung berfungsi tanpa konfigurasi
manual. Idempotent. Tenant `platform` **dikecualikan** — super-admin bukan pelanggan aplikasi:

```kotlin
val platformId = tenantApi.platformTenantId()
val tenantIds = tenantApi.findActiveTenantIds().filterNot { it == platformId }
```

---

## Sisi web

- **Halaman tenant** `/subscription` (`SubscriptionPage.tsx`, izin `billing.subscription.view`):
  tata letak lebar penuh — *hero* biaya + masa aktif (bar progres periode), pemilih durasi bayar
  di muka **1 / 3 / 6 / 12 bulan** dengan total langsung, tombol *Perpanjang*/*Bayar sekarang*,
  kartu pemakaian kosmetik ("N / Unlimited" — `limit` selalu `null`), riwayat tagihan dengan tombol
  **Bayar ↗** (buka halaman bayar publik di tab baru) + **Salin link**, dan panel penjelas
  "cara perpanjangan".
- **Onboarding** (`TenantsPage.tsx`): input *Harga bulanan khusus* (kosong = default global,
  di-load dari setelan platform bila punya `platform.billing.view`).
- **Setelan platform** (`PlatformBillingSettingsPage.tsx`): input *Harga bulanan default*.

> **Area Platform admin terpisah.** Halaman sisi platform di atas (onboarding tenant, setelan
> billing langganan, server VPN, plus pengguna/role/audit) tidak lagi bercampur dengan menu tenant.
> Platform admin (`user.platformAdmin`) punya shell & dashboard sendiri — `PlatformLayout` +
> `PlatformDashboardPage` — di namespace **`/platform/*`** (`web/src/App.tsx`, dijaga
> `RequirePlatformAdmin`). Login sebagai platform admin mendarat di `/platform`; path lama
> (`/tenants`, `/platform-billing`, `/vpn-servers`) dialihkan ke `/platform/*`. Platform admin tetap
> boleh membuka halaman operasional tenant lewat deep-link untuk inspeksi (tanpa redirect paksa).

**Pemakaian kosmetik** dihitung server-side lewat `SubscriptionUsageProbe` (count OLT/ODC/ODP/
pelanggan) memakai `EntityManager` — koneksi Hibernate membawa GUC `app.tenant_id` (RLS-aware),
beda dari `JdbcTemplate` polos. Angka nyata, **batasnya bohong** (selalu Unlimited): murni hiasan,
tak pernah menolak operasi. Di UI, tiap metrik tampil sebagai *meter* bar; karena `limit` selalu
`null`, track digambar penuh redup (accent-soft) berlabel "N / Unlimited".

---

## Rencana lanjutan — plan bertingkat (DITUNDA)

> **Status: ditunda.** Model saat ini **flat + override** (satu harga, tanpa tier). Halaman tenant
> sengaja dibuat tanpa bagian *Available Plans* / tombol *Upgrade*.

Referensi desain menampilkan **plan bertingkat** (Starter / Business / Pro, harga & kuota berbeda,
alur *Upgrade*) dengan kuota **nyata** (bukan kosmetik). Ini **belum** dibangun. Bila kelak diambil,
perubahan yang dibutuhkan (garis besar):

- **Data**: tabel `subscription_plan` (kode, harga, kuota per metrik) + `tenant_subscription.plan_id`
  (FK) menggantikan/menemani `monthly_fee` flat. Harga tak lagi satu angka global.
- **Kuota nyata**: `UsageMetricView.limit` diisi dari plan (bukan `null`); `SubscriptionUsageProbe`
  jadi sumber *enforcement*, bukan sekadar hiasan — perlu keputusan apakah menolak operasi saat
  kuota habis atau tetap kosmetik dengan peringatan.
- **Alur upgrade/downgrade**: proration, kapan berlaku (langsung vs periode berikutnya), efek ke
  `current_period_end`.
- **UI**: seksi *Available Plans* (kartu per tier + tombol Upgrade/Renew), mengganti kartu status
  tunggal saat ini.

Sampai diputuskan, halaman tenant tetap: **satu plan aktif + tombol Perpanjang**, pemakaian kosmetik.
