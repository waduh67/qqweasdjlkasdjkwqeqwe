# Modul `billing` — tagihan & pembayaran

Mesin penagihan langganan: menerbitkan invoice bulanan per langganan aktif,
menerima pembayaran (manual maupun lewat payment gateway), dan **menggerakkan
isolir/aktivasi langganan** saat jatuh tempo atau lunas. Isolir mengalir ke
`customer` → `bng` sehingga sesi PPPoE pelanggan ikut terputus/pulih.

Module ini **tidak menyimpan** data langganan; ia hanya membacanya lewat
`CustomerApi` dan memerintah isolir/aktivasi lewat API yang sama. Boundary Spring
Modulith ditegakkan `ModularityTests`.

---

## Model domain

```
Invoice (agregat)                         Payment (agregat, append-only)
├── number   INV-YYYYMM-####              ├── invoiceId
├── customerId · subscriptionId           ├── amount · provider
├── periodStart · periodEnd               ├── gatewayRef · paidAt
├── amount (BigDecimal, scale 2)          └── (tak pernah diubah setelah dicatat)
├── refundedAmount (≤ amount)
├── issuedAt · dueAt                      Refund (agregat, satu baris = satu PERCOBAAN)
├── status  ISSUED → PAID / OVERDUE       ├── invoiceId · customerId · paymentId?
│            / VOID / REFUNDED            ├── amount · reason · provider
└── charge  (provider, gatewayRef,        ├── status PENDING → PROCESSING → SUCCESS/FAILED
             payUrl)                      └── gatewayRef · failureReason · completedAt
     ↑ ditempel saat createCharge gateway
```

**Status invoice** dan transisinya (dijaga di domain, bukan di service):

| Dari | Aksi | Ke | Catatan |
|---|---|---|---|
| ISSUED | `markPaid` | PAID | idempoten; menolak dari VOID |
| ISSUED | `markOverdue` | OVERDUE | hanya dari ISSUED |
| OVERDUE | `markPaid` | PAID | pembayaran telat tetap diterima |
| ISSUED/OVERDUE | `void` | VOID | menolak bila sudah PAID |
| PAID | `applyRefund` (sebagian) | PAID | `refundedAmount` naik; sisa masih bisa dikembalikan |
| PAID | `applyRefund` (penuh) | REFUNDED | lunas lalu uangnya kembali PENUH — beda dari VOID yang tak pernah menghasilkan |

Nominal negatif ditolak; semua nominal di-`setScale(2, HALF_UP)`.

Tagihan REFUNDED bersifat final: tak bisa di-`markPaid` maupun di-`void` lagi.

---

## Siklus penagihan (`BillingScheduler` + `BillingCycleRunner`)

Terpisah sesuai aturan proxy-transaction: **scheduler** mengiterasi tenant,
**runner** melakukan kerja dalam transaksi `REQUIRES_NEW` per tenant.

```
BillingScheduler  @Scheduled(fixedDelayString = "${ftth.billing.scheduler-interval:PT12H}")
  └─ untuk tiap tenant aktif → TenantContext.runAs(tenantId) → BillingCycleRunner
       ├─ issue()    : terbitkan invoice untuk langganan yang belum ditagih periode ini
       └─ enforce()  : invoice lewat jatuh tempo + grace → markOverdue()
                        └─ bila ftth.billing.auto-isolir=true → CustomerApi.isolateForBilling
```

- **`issue`** memanggil `InvoiceGenerator.generateFor(tenantId, today)` yang menarik
  `customerApi.findBillableSubscriptions()`, menyaring `monthlyFee > 0` &
  `!existsForPeriod`, membuat nomor `INV-YYYYMM-####`, lalu memanggil `createCharge`
  lewat gateway hasil resolusi tenant (`TenantPaymentGatewayResolver`, di-resolve sekali
  per ronde) untuk menempelkan `payUrl`. Tiap charge dibungkus `runCatching` — satu gagal
  tak membatalkan ronde.
- **`enforce`** menandai invoice yang lewat `dueAt + graceDays` menjadi OVERDUE
  dan (opsional) mengisolir langganannya.
- **`remindDueSoon`** menerbitkan `InvoiceDueSoon`; `enforce` menerbitkan `InvoiceOverdue`.
  Keduanya membawa **`payUrl`** — tautan [halaman bayar publik](#halaman-bayar-publik-bayartenantsluginvoiceid)
  yang dirangkai di sini (`pivot.redirect-base-url` + slug tenant + UUID tagihan), supaya module
  `notification` tak perlu tahu konfigurasi apa pun. Basis URL kosong atau tenant tak terbaca →
  `payUrl = null` dan pesan tetap terkirim, hanya tanpa tautan.

> **Bayar ikut penyedia aktif.** `payUrl` sebuah invoice dibuat **sekali** saat terbit,
> lewat penyedia yang aktif saat itu. Bila operator mengganti penyedia setelahnya, tagihan
> lama masih menyimpan tautan penyedia lama. `POST /invoices/{id}/recharge`
> (`InvoiceGenerator.refreshCharge` → `ManageInvoiceUseCase.refreshPaymentLink`) me-resolve
> penyedia **aktif sekarang** dan membuat charge baru bila berbeda (idempoten bila sama;
> tanpa tautan bila MANUAL; ditolak untuk PAID/VOID). Endpoint ini kini **tak lagi dipanggil UI**:
> pelanggan membayar lewat halaman bayar publik yang selalu me-resolve gateway aktif saat itu,
> jadi `recharge` tinggal jadi alat rekonsiliasi manual.

---

## Pembayaran & gateway per-tenant

`PaymentGateway` adalah port keluar yang **provider-agnostik** — tidak ada vendor
yang di-hardcode. Adapter tetap singleton stateless; kredensial per-tenant disuntikkan
lewat `ResolvedGatewayContext` tiap panggilan (satu adapter melayani banyak tenant):

```kotlin
interface PaymentGateway {
    val provider: String                                                       // "PIVOT", "MANUAL"
    fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult
    fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement?
}
```

- `TenantPaymentGatewayResolver` membaca metode aktif tenant (via RLS) + setelan MASTER
  Pivot + sub-account tenant, lalu menghasilkan `ResolvedGatewayContext`. Jatuh ke fallback
  **MANUAL** (+ shared secret global) bila tenant belum/nonaktif memakai Pivot atau
  sub-account belum siap.
- `PaymentGatewayRegistry` mengindeks semua bean `PaymentGateway` per `provider.uppercase()`;
  pemanggil memilih adapter via `forProvider(ctx.provider)`. Kini hanya ada `PivotPaymentGateway`
  + `ManualPaymentGateway`.
- Bawaan `ManualPaymentGateway` (`provider = "MANUAL"`) memverifikasi header
  `X-Billing-Signature` sama dengan `ctx.webhookToken ?: ftth.billing.webhook-secret`.

> **Pivot-only.** Sejak migrasi penuh, penyedia lama (Xendit/Midtrans/Paywuz) & model BYOK
> **dihapus**. Seluruh transaksi berjalan di **satu akun MASTER Pivot** milik platform, tiap
> tenant jadi **sub-account** yang ditagih on-behalf (+ split fee platform); alternatifnya
> pembayaran **MANUAL** (transfer/QRIS). Detail: [**docs/pivot-overview.md**](pivot-overview.md)
> · [**docs/payment-gateway.md**](payment-gateway.md).

### Alur settle (lunas → auto-pulih)

```
POST /api/billing/webhooks/{tenantSlug}/{provider}   (TANPA bearer — publik)
  └─ tenantApi.findBySlug(tenantSlug) → TenantContext.runAs
       ├─ ctx = resolver.resolve() → gateway = registry.forProvider(ctx.provider)
       └─ gateway.parseCallback(GatewayCallback(headers, body), ctx) → PaymentSettlement?
            └─ PaymentService.settle():
                 ├─ invoice.markPaid(at)
                 ├─ PaymentRepository.save(Payment)   (append-only)
                 └─ bila !hasOverdueForSubscription → CustomerApi.reactivateForBilling
```

Pembayaran manual juga bisa lewat `POST /api/billing/invoices/{id}/pay`
(izin `billing.payment.manage`).

---

## Pengembalian dana (refund)

Uang **keluar** dari rekening tenant, jadi izinnya sendiri: `billing.refund.manage` — kasir yang
boleh mencatat pembayaran masuk (`billing.payment.manage`) belum tentu boleh mengembalikan.

```
POST /api/billing/invoices/{id}/refund   {amount?, reason?, note?}
  └─ RefundService.request():
       ├─ invoice harus PAID; nominal kosong = seluruh SISA (amount − refundedAmount)
       ├─ tolak bila melebihi sisa — kuota memperhitungkan refund yang MASIH BERJALAN,
       │  jadi dua permintaan penuh tak bisa lolos berbarengan
       ├─ provider DIBEKUKAN dari cara tagihan itu dulu dibayar (PIVOT / MANUAL)
       └─ PIVOT  → gateway.refund() → PROCESSING, tunggu callback REFUND.*
          MANUAL → berhenti di PENDING sampai operator menyatakan transfernya
```

- **Penutupan otomatis** — callback `REFUND.SUCCESS`/`REFUND.FAILED` masuk lewat
  `POST /api/platform/pivot/callbacks/refund`, dicocokkan via `data.id` (ref Pivot) **atau**
  `data.clientReferenceId` (id baris kita, untuk callback yang mendahului respons `POST /v1/refunds`).
  Idempoten: baris yang sudah final tak berubah lagi, termasuk `FAILED` telat setelah `SUCCESS`.
- **Penutupan manual** — `POST /api/billing/refunds/{id}/settle` `{success, reason?}`; hanya untuk
  baris berpenyedia `MANUAL`. Gagal mengembalikan kuotanya, jadi pengajuan ulang tetap mungkin.
- **Efek ke tagihan** — hanya refund `SUCCESS` yang menaikkan `refundedAmount`; setelah menutupi
  seluruh nominal, tagihan jadi `REFUNDED`.
- **Efek ke laporan** — `revenueCollected` tetap **bruto**; `refundedAmount`/`refundCount`/
  `netRevenue` jadi kolom tersendiri di `BillingFinancialReport`, dihitung menurut **kapan uangnya
  keluar** (`completedAt`), bukan kapan tagihannya lunas atau refundnya diajukan.

---

## Halaman bayar publik (`/bayar/{tenantSlug}/{invoiceId}`)

Satu-satunya jalur bayar untuk pelanggan: **satu halaman, satu tautan, bisa dibagikan**.
Modal bayar di konsol operator, portal pelanggan, dan halaman langganan SaaS sudah **dihapus** —
ketiganya kini hanya membuka/menyalin tautan ini. Pengingat tagihan WhatsApp pun menempelkannya
di ekor pesan (lihat [Siklus penagihan](#siklus-penagihan-billingscheduler--billingcyclerunner)).

**Kenapa slug tenant ikut di URL.** Tabel `invoice` memakai `@TenantId` + **RLS FORCE**, dan
resolver memulangkan sentinel `ROOT` saat context kosong — jadi `findById(uuid)` tak mungkin
menemukan apa pun sebelum tenant terpasang, dan dari UUID saja tenant tak bisa disimpulkan.
Alternatifnya tabel direktori tanpa RLS; dipilih slug di path karena **nol migrasi** dan persis pola
yang sudah dipakai webhook Pivot (`metadata.tenantSlug` → `findBySlug` → `TenantContext.runAs`).

| Endpoint (`/api/public/invoices`) | Isi |
|---|---|
| `GET /{tenantSlug}/{invoiceId}` | tagihan + instruksi bayar; juga dipakai polling status |
| `GET /{tenantSlug}/{invoiceId}/methods` | katalog QRIS + Virtual Account |
| `POST /{tenantSlug}/{invoiceId}/pay` | `{method, channel}` → tagihan berisi instruksinya |
| `GET /{tenantSlug}/{invoiceId}/qris` | gambar QRIS statis tenant (gateway MANUAL) |

Aturan yang dijaga `PublicInvoicePaymentService` (+ `PublicInvoicePaymentServiceTest`):

- **Dua realm, satu bentuk.** Tagihan pelanggan (module `billing`, ter-RLS) dan tagihan langganan
  SaaS (`platformbilling`, level platform) dilayani endpoint yang sama; realm dipilih dari
  *keberadaan* tagihannya, bukan dari parameter klien.
- **Satu kalimat untuk semua sebab.** Slug asing, UUID asing, dan tagihan milik tenant lain
  memulangkan `404` dengan pesan yang sama persis — pemegang tautan yang menebak-nebak tak boleh
  bisa membedakan mana yang salah.
- **Instruksi hidup dipakai ulang.** `pay()` dengan metode+channel sama dan VA/QRIS belum
  kedaluwarsa memulangkan instruksi tersimpan **tanpa** memanggil gateway
  (`PaymentMethodCatalog.stillUsable`) — tautan publik dipegang siapa saja, jadi memuat ulang
  halaman tak boleh menghambur sesi bayar baru di penyedia. Jalur ter-autentikasi tak berubah:
  operator tetap punya "Perbarui pembayaran" yang benar-benar membuat charge baru.
- **Proyeksi paling sempit.** `PublicInvoiceView` tak punya bidang `gatewayRef`, id sesi bayar,
  `payUrl` penyedia, maupun penanda simulasi.

Batasnya jujur: karena kuncinya UUID tagihan (bukan token), **tautan tak bisa dicabut** — yang bocor
tetap menampilkan nominal & nama pelanggan sampai tagihannya lunas, dan belum ada pembatasan laju
untuk `pay()` dengan channel berganti-ganti.

---

## Keamanan

- **Webhook publik.** `/api/billing/webhooks/**` di-`permitAll` di `SecurityConfig`
  (gateway eksternal tak membawa JWT); keasliannya dijamin **tanda tangan** yang
  diperiksa `parseCallback` tiap provider.
- **Halaman bayar publik.** `/api/public/**` juga `permitAll` — kapabilitasnya UUID tagihan di
  path, bukan token (lihat bagian di atas). Isolasi tenant tetap ditegakkan RLS, bukan parameter.
- **`gatewayRef` tidak pernah bocor** lewat view invoice biasa.
- **Webhook secret** lewat env (`FTTH_BILLING_WEBHOOK_SECRET`); default dev-only
  wajib di-override di produksi.
- Dua-lapis RLS pada `invoice` & `payment` (per `V25__billing.sql`).

---

## Konfigurasi (`ftth.billing`)

| Properti | Bawaan | Guna |
|---|---|---|
| `billing-day-of-month` | 1 | tanggal terbit invoice |
| `due-days` | 7 | tenggat sejak terbit |
| `grace-days` | 3 | masa tenggang sebelum isolir |
| `auto-isolir` | true | isolir otomatis saat overdue |
| `number-prefix` | `INV` | awalan nomor invoice |
| `default-provider` | `MANUAL` | **usang** — digantikan resolusi gateway per-tenant (lihat di bawah) |
| `scheduler-interval` | `PT12H` | selang siklus penagihan |
| `webhook-secret` | *(dev)* | secret callback **MANUAL** (fallback); override via `FTTH_BILLING_WEBHOOK_SECRET` |
| `pivot.redirect-base-url` | `""` | Pivot: basis URL balik mode REDIRECT — lihat [docs/pivot-overview.md](pivot-overview.md) |

> Pemilihan gateway per pembuatan charge kini lewat `TenantPaymentGatewayResolver`
> (metode aktif tenant + master Pivot + sub-account), bukan `default-provider` global.
> Kredensial MASTER Pivot ada di `pivot_master_config` (setelan super-admin), bukan env.
> Setelan payment gateway selengkapnya di [**docs/payment-gateway.md**](payment-gateway.md).

---

## API

| Endpoint | Izin |
|---|---|
| `GET /api/billing/invoices` · `/{id}` | `billing.invoice.view` |
| `POST /api/billing/invoices/generate` | `billing.invoice.manage` |
| `POST /api/billing/invoices/{id}/void` | `billing.invoice.manage` |
| `POST /api/billing/invoices/{id}/recharge` | `billing.invoice.manage` |
| `POST /api/billing/invoices/{id}/pay` | `billing.payment.manage` |
| `GET /api/billing/payments` | `billing.invoice.view` |
| `GET /api/billing/refunds` | `billing.invoice.view` |
| `POST /api/billing/invoices/{id}/refund` | `billing.refund.manage` |
| `POST /api/billing/refunds/{id}/settle` | `billing.refund.manage` |
| `GET · PUT /api/billing/gateway-settings` | `billing.gateway.view` / `billing.gateway.manage` |
| `GET/POST /api/billing/pivot-account/**` (sub-account, saldo, payout) | `billing.gateway.view` / `manage` |
| `GET/PUT /api/platform/pivot-config` (setelan master Pivot) | `platform.billing.view` / `manage` |
| `POST /api/billing/webhooks/{tenantSlug}/pivot` · `/pivot-payout` | publik (`X-API-Key` master) |
| `GET · POST /api/public/invoices/{tenantSlug}/{invoiceId}/**` | publik (kapabilitas = UUID tagihan) |

---

## Kaitan lintas-module

```
billing ──findBillableSubscriptions / isolateForBilling / reactivateForBilling──▶ customer
customer ──event langganan (isolir/aktif)──▶ bng ──▶ putus/pulih sesi PPPoE
```

Billing tak pernah menyentuh tabel `customer` maupun `bng` langsung — semua lewat
`CustomerApi`.
