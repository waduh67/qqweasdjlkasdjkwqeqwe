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
| `platform_setting` | **V59** (+`default_monthly_fee` di **V62**) | satu baris setelan global: hari tagih, masa tenggang, **harga bulanan default** |
| `platform_payment_gateway` | **V59** | gateway aktif tingkat platform (memakai ulang mesin [`billing`](payment-gateway.md)) |
| `tenant_subscription` | **V60** | satu baris per tenant: `monthly_fee`, `status`, periode aktif, jadwal tagih |
| `tenant_subscription_invoice` | **V61** | tagihan langganan ber-periode (ISSUED/PAID/OVERDUE/VOID) |
| `tenant_subscription_payment` | **V61** | pelunasan (dari webhook gateway atau catatan manual super-admin) |

### `tenant_subscription` — mesin keadaan

```
tenant_subscription (satu baris per tenant)
├── monthly_fee            biaya bulanan efektif (override ?: default global saat dibuat)
├── status                 ACTIVE | PAST_DUE | SUSPENDED | CANCELLED
├── billing_day / grace    null = ikut default global (platform_setting)
├── current_period_start   masa aktif — awal
├── current_period_end     masa aktif — akhir  ← "Active until"; HANYA bertambah saat LUNAS
├── next_invoice_at        kapan scheduler boleh menerbitkan tagihan berikutnya
└── activated_at           kapan pertama kali aktif
```

Perpindahan status memicu efek nyata (auto-suspend saat menunggak, auto-pulih saat lunas),
jadi dijaga mesin keadaan eksplisit di domain — bukan setter bebas.

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
tempo dan `PlatformBillingScheduler` akan menyuspendnya di siklus berikutnya sebelum sempat
membayar. Memberi 1 bulan aktif di depan (tagihan pertama terbit menjelang habis) menutup lubang itu.

---

## Perpanjangan mandiri (self-service)

`POST /api/subscription/renew?months=N` (izin `billing.subscription.renew`, `N` 1..12, default 1):

1. Bila sudah ada tagihan **tertunggak** (ISSUED/OVERDUE) → kembalikan yang itu (jangan bikin dobel;
   `months` diabaikan — bayar dulu yang tertunggak).
2. Bila tidak → terbitkan tagihan baru (`issueFor(..., force = true, months = N)`) lewat gateway
   aktif; nilai `biaya × N`, periode membentang N bulan **menyambung** dari ujung masa aktif berjalan;
   kembalikan `payUrl`.
3. Tenant membayar di tab gateway. Saat webhook **settle**, `PlatformPaymentService.applySettlement`
   → `extendOnPayment(today, N)` → masa aktif memanjang N bulan.

**Bayar di muka (1 / 3 / 6 / 12 bulan)**: tenant memilih durasi di halaman langganan sebelum
menekan *Perpanjang*. Ini murni opsional — scheduler tetap menerbitkan tagihan bulanan otomatis
menjelang masa aktif habis; tombol hanya mempercepat / memborong beberapa bulan.

Pesan UI eksplisit: *"Masa aktif bertambah setelah pembayaran LUNAS."* — tak ada perpanjangan
optimistis di sisi terbit.

---

## Scheduler penagihan (`PlatformBillingScheduler`, tiap `PT12H`)

Level platform → **tidak** `TenantContext.runAs` (beda dari `BillingScheduler` tenant). Tiap
langganan diproses dalam transaksi `REQUIRES_NEW` agar kegagalan satu tak menghentikan lainnya.

- `issueInvoices()` — terbitkan tagihan untuk langganan yang `next_invoice_at` sudah due.
- `enforceOverdue()` — tandai tagihan lewat jatuh tempo → OVERDUE; bila menunggak **melewati
  masa tenggang**, suspend langganan **dan** tenant. Auto-suspend didorong oleh **tagihan
  tertunggak**, bukan sekadar `current_period_end` lewat.

Pelunasan seluruh tunggakan memulihkan langganan + tenant ke ACTIVE (`reactivateIfCleared`).

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

`platformbilling` menagih lewat gateway yang **sama** seperti tagihan pelanggan
([`docs/payment-gateway.md`](payment-gateway.md)), jadi butuh `PaymentGatewayRegistry`,
`ChargeRequest`, `PaymentSettlement`, `ResolvedGatewayContext`, dll. — tipe **internal** billing.
Alih-alih menembus enkapsulasi, tipe-tipe itu di-expose sebagai **named interface** Spring Modulith:

```kotlin
@NamedInterface("gateway")
interface PaymentGateway { ... }          // + ChargeRequest/Result, PaymentSettlement, GatewayCallback,
@NamedInterface("gateway")                //   PaymentGatewayRegistry, ResolvedGatewayContext, GatewayMode
data class ResolvedGatewayContext(...)
```

`platformbilling → billing :: gateway` kini sah menurut ModularityTests, tanpa membuka seluruh
sub-package billing.

### 3. Suspend/aktifkan tenant → lewat `TenantApi`

`platformbilling` men-suspend/memulihkan tenant saat menunggak/lunas. Method `suspend(id)`/
`activate(id)` **dipromosikan** ke `TenantApi` (kontrak lintas-module ter-expose) alih-alih memakai
`ManageTenantUseCase` (use case web internal tenancy) — menutup satu lagi akses tipe non-exposed.

---

## Izin & endpoint

| Izin | Untuk |
|---|---|
| `billing.subscription.view` | tenant: lihat langganan sendiri (auto ke role Tenant Admin) |
| `billing.subscription.renew` | tenant: perpanjang mandiri |
| `platform.subscription.view` | super-admin: lihat langganan & tagihan semua tenant |
| `platform.subscription.manage` | super-admin: kelola biaya, tagihan, pembayaran |
| `platform.billing.view` / `platform.billing.manage` | super-admin: setelan + gateway platform |

| Endpoint | Izin |
|---|---|
| `GET /api/subscription` · `POST /api/subscription/renew?months=N` | `billing.subscription.*` (tenant) |
| `GET/PUT /api/platform/billing/settings` | `platform.billing.*` |
| `.../tenants/{id}/subscription` · `/invoices` · `/invoices/{id}/pay`·`/void` · `/cancel` | `platform.subscription.*` |
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
  kartu pemakaian kosmetik ("N / Unlimited" — `limit` selalu `null`), riwayat tagihan dengan tautan
  bayar, dan panel penjelas "cara perpanjangan".
- **Onboarding** (`TenantsPage.tsx`): input *Harga bulanan khusus* (kosong = default global,
  di-load dari setelan platform bila punya `platform.billing.view`).
- **Setelan platform** (`PlatformBillingSettingsPage.tsx`): input *Harga bulanan default*.

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
