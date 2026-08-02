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
├── issuedAt · dueAt
├── status  ISSUED → PAID / OVERDUE / VOID
└── charge  (provider, gatewayRef, payUrl)   ← ditempel saat createCharge gateway
```

**Status invoice** dan transisinya (dijaga di domain, bukan di service):

| Dari | Aksi | Ke | Catatan |
|---|---|---|---|
| ISSUED | `markPaid` | PAID | idempoten; menolak dari VOID |
| ISSUED | `markOverdue` | OVERDUE | hanya dari ISSUED |
| OVERDUE | `markPaid` | PAID | pembayaran telat tetap diterima |
| ISSUED/OVERDUE | `void` | VOID | menolak bila sudah PAID |

Nominal negatif ditolak; semua nominal di-`setScale(2, HALF_UP)`.

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

---

## Pembayaran & gateway per-tenant

`PaymentGateway` adalah port keluar yang **provider-agnostik** — tidak ada vendor
yang di-hardcode. Adapter tetap singleton stateless; kredensial per-tenant disuntikkan
lewat `ResolvedGatewayContext` tiap panggilan (satu adapter melayani banyak tenant):

```kotlin
interface PaymentGateway {
    val provider: String                                                       // "MANUAL", "XENDIT", …
    fun createCharge(request: ChargeRequest, ctx: ResolvedGatewayContext): ChargeResult
    fun parseCallback(callback: GatewayCallback, ctx: ResolvedGatewayContext): PaymentSettlement?
}
```

- `TenantPaymentGatewayResolver` membaca baris config gateway tenant (via RLS),
  mendekripsi di batas persistence, dan menghasilkan `ResolvedGatewayContext`
  (provider + mode + kredensial). Jatuh ke fallback **MANUAL** (+ shared secret global)
  bila tenant belum/nonaktif mengonfigurasi.
- `PaymentGatewayRegistry` mengindeks semua bean `PaymentGateway` per `provider.uppercase()`;
  pemanggil memilih adapter via `forProvider(ctx.provider)`. Menambah provider = menambah satu bean.
- Bawaan `ManualPaymentGateway` (`provider = "MANUAL"`) memverifikasi header
  `X-Billing-Signature` sama dengan `ctx.webhookToken ?: ftth.billing.webhook-secret`.

> Tiap tenant memilih penyedia + mode (BYO/PLATFORM) + kredensialnya sendiri. Xendit
> digarap penuh (BYO **dan** PLATFORM/xenPlatform dengan auto-provision sub-account); Pivot
> digarap penuh BYO; Paywuz kerangka. Detail lengkap: [**docs/payment-gateway.md**](payment-gateway.md).

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

## Keamanan

- **Webhook publik.** `/api/billing/webhooks/**` di-`permitAll` di `SecurityConfig`
  (gateway eksternal tak membawa JWT); keasliannya dijamin **tanda tangan** yang
  diperiksa `parseCallback` tiap provider.
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
| `platform.*` | *(mati)* | kredensial MASTER agregator (mode PLATFORM) — lihat [docs/payment-gateway.md](payment-gateway.md) |

> Pemilihan gateway per pembuatan charge kini lewat `TenantPaymentGatewayResolver`
> (baris config tenant), bukan `default-provider` global. Setelan + env payment gateway
> selengkapnya di [**docs/payment-gateway.md**](payment-gateway.md).

---

## API

| Endpoint | Izin |
|---|---|
| `GET /api/billing/invoices` · `/{id}` | `billing.invoice.view` |
| `POST /api/billing/invoices/generate` | `billing.invoice.manage` |
| `POST /api/billing/invoices/{id}/void` | `billing.invoice.manage` |
| `POST /api/billing/invoices/{id}/pay` | `billing.payment.manage` |
| `GET /api/billing/payments` | `billing.invoice.view` |
| `GET · PUT /api/billing/gateway-settings` | `billing.gateway.view` / `billing.gateway.manage` |
| `POST /api/billing/platform/gateway/{tenantId}/xendit-subaccount` | `billing.gateway.provision` (platform) |
| `POST /api/billing/webhooks/{tenantSlug}/{provider}` | publik (tanda tangan gateway) |

---

## Kaitan lintas-module

```
billing ──findBillableSubscriptions / isolateForBilling / reactivateForBilling──▶ customer
customer ──event langganan (isolir/aktif)──▶ bng ──▶ putus/pulih sesi PPPoE
```

Billing tak pernah menyentuh tabel `customer` maupun `bng` langsung — semua lewat
`CustomerApi`.
