# Penyaluran dana Pivot — payout & withdrawal

Bagian dari [`pivot-overview.md`](pivot-overview.md). Menjelaskan bagaimana dana tenant
keluar dari Pivot: **payout** (akun NON_KYC) vs **withdrawal** (akun KYC), tabel
`tenant_payout` (V72), status lifecycle, rekonsiliasi via webhook, dan pembacaan saldo.

Fase ini melengkapi model master+sub-account: dana pelanggan yang masuk lewat charge
on-behalf ([`pivot-fee-split.md`](pivot-fee-split.md)) perlu disalurkan ke tenant.

---

## Dua jenis penyaluran

| Aspek | **PAYOUT** (NON_KYC) | **WITHDRAWAL** (KYC) |
|---|---|---|
| Dana ada di | balance **MASTER** platform | balance **sub-account** tenant |
| Disalurkan oleh | operator **platform** ke rekening tenant | **tenant** sendiri |
| Endpoint Pivot | `POST /v1/payouts` (memakai `payoutInquiryId` tenant) | `POST /v1/withdrawals` on-behalf (`x-submerchant-id`) |
| Endpoint app | `POST /api/billing/pivot-account/payouts` | `POST /api/billing/pivot-account/withdrawals` |
| Prasyarat | rekening payout tervalidasi (`payoutReady`) | sub-account KYC terprovisi |

Keduanya lewat `TenantPayoutService` di atas `PivotPayoutGateway`, memakai kredensial akun
**master** (withdrawal ditembak on-behalf sub-account). Idempotency `X-REQUEST-ID`
diturunkan dari id baris `tenant_payout` → retry perintah sama aman.

---

## Nominal eksplisit — tanpa akrual otomatis

Penyaluran memakai **nominal eksplisit** (minor-unit IDR, zero-decimal) yang diketik
operator/tenant — **tidak ada akrual otomatis** dan **belum ada scheduler** yang menyalurkan
sendiri. Baris `tenant_payout` **tidak menghitung saldo**; Pivot adalah sumber kebenaran
balance (dibaca via `GET /v1/balances`).

> **Follow-up (belum dibangun).** Payout/withdrawal terjadwal otomatis (mis. sapu saldo
> master ke tenant per periode) belum ada — saat ini murni dipicu manual per perintah.

---

## Model data — `tenant_payout` (V72)

Satu baris **per percobaan** penyaluran (tenant-scoped + RLS dua-lapis). Jejak audit
finansial yang direkonsiliasi callback Pivot — bukan buku besar saldo.

```
tenant_payout  (satu baris per percobaan, RLS)
├── kind            PAYOUT | WITHDRAWAL                        (CHECK)
├── amount_minor    nominal minor-unit IDR (> 0)              (CHECK)
├── channel_code    rekening tujuan (snapshot saat dibuat) — non-rahasia
├── account_number
├── account_name
├── status          PENDING | PROCESSING | SUCCESS | FAILED    (CHECK)
├── pivot_ref       referensi transaksi di Pivot (data.id/referenceId) — kunci rekonsiliasi
├── failure_reason
└── created_at
   indeks: (pivot_ref)  ·  (tenant_id, created_at DESC)
```

### Status lifecycle

```
create ─▶ PENDING ──dispatch diterima Pivot (markProcessing, simpan ref)──▶ PROCESSING
                                                                              │
   dispatch.settledImmediately ──────────────────────────────────────────▶ SUCCESS
                                                                              │
   callback rekonsiliasi (X-API-Key master):  success ─▶ SUCCESS  ·  gagal ─▶ FAILED
```

- `PENDING` baru dicatat lokal; `PROCESSING` sudah diterima Pivot (punya `pivot_ref`).
- Bila respons dispatch sudah final (`status` ∈ SUCCESS/COMPLETED/SETTLED/PAID), langsung
  `markSuccess()` tanpa menunggu callback.
- `SUCCESS`/`FAILED` umumnya hasil final dari **callback rekonsiliasi**.

---

## Alur payout NON_KYC

```
POST /api/billing/pivot-account/payouts { amountMinor, remarks? }   (billing.gateway.manage)
  └─ TenantPayoutService.dispatchPayout:
       ├─ syarat: account.type == NON_KYC & account.payoutReady
       ├─ TenantPayout.create(PAYOUT, amount, rekening snapshot) → PENDING
       ├─ POST /v1/payouts { amount, channelCode, accountNumber, inquiryId, remarks }
       │      → PayoutDispatch(reference, settledImmediately)
       ├─ markProcessing(reference)  (+ markSuccess bila settledImmediately)
       └─ save + audit "billing.pivot.payout.dispatched"
```

`inquiryId` berasal dari validasi rekening (`payout_inquiry_id`, lihat
[`pivot-sub-account.md`](pivot-sub-account.md)).

## Alur withdrawal KYC

```
POST /api/billing/pivot-account/withdrawals { amountMinor, remarks? }   (billing.gateway.manage)
  └─ TenantPayoutService.withdraw:
       ├─ syarat: account.type == KYC & provisioned (punya sub_merchant_uuid)
       ├─ TenantPayout.create(WITHDRAWAL, amount, …) → PENDING
       ├─ POST /v1/withdrawals (x-submerchant-id=<sub tenant>) { amount, …, remarks }
       ├─ markProcessing(reference)  (+ markSuccess bila settledImmediately)
       └─ save + audit "billing.pivot.withdrawal.dispatched"
```

---

## Rekonsiliasi webhook

Callback status payout/withdrawal masuk lewat endpoint platform per-produk
`POST /api/platform/pivot/callbacks/payout` dan `.../withdrawal` (juga
`.../international-payout`) — bukan lagi URL per-tenant-slug. Handler menutup status baris:

```
verifikasi X-API-Key == callback_api_key master (constant-time)
  └─ reference = data.id | referenceId | reference
  └─ outcome dari event/status:
       SUCCESS/COMPLETED/SETTLED/PAID  → sukses
       FAIL/REJECT/CANCEL/EXPIRED/RETURNED → gagal
       lainnya (mis. PROCESSING) → "ignored" (tunggu callback final)
  └─ TenantContext.runAs(tenant) → reconcile(reference, outcome, reason)
        └─ findByReference(ref) → markSuccess() / markFailed(reason)
```

- Terpisah dari callback `payment` (pelunasan tagihan): payout/withdrawal **tidak**
  menyentuh invoice — hanya menutup `tenant_payout`.
- **Idempotent** (callback ganda aman); ref yang tak cocok baris mana pun diabaikan.

---

## Saldo

`GET /api/billing/pivot-account/balance` (`billing.gateway.view`) membaca **langsung** dari
Pivot (`GET /v1/balances?usecase=PAYMENT`):

- Akun **KYC** terprovisi → dibaca dari balance **sub-account** tenant (`x-submerchant-id`),
  `subAccount=true`.
- selain itu → dibaca dari balance **master** platform (dana NON_KYC), `subAccount=false`.

`PivotBalanceView` mengembalikan `availableMinor` + `pendingMinor` + `currency` (bawaan IDR).
Bentuk respons Pivot dibaca defensif (beberapa alias field & objek/array per-currency) karena
sandbox/prod kadang beda pembungkusnya.

---

## Endpoint ringkas

| Endpoint | Izin | Guna |
|---|---|---|
| `GET /api/billing/pivot-account/balance` | `billing.gateway.view` | saldo relevan (master NON_KYC / sub-account KYC) |
| `GET /api/billing/pivot-account/payouts` | `billing.gateway.view` | riwayat penyaluran tenant |
| `POST /api/billing/pivot-account/payouts` | `billing.gateway.manage` | salurkan dana NON_KYC ke rekening tenant |
| `POST /api/billing/pivot-account/withdrawals` | `billing.gateway.manage` | tarik saldo sub-account KYC |
| `POST /api/platform/pivot/callbacks/payout` · `.../withdrawal` · `.../international-payout` | publik (`X-API-Key` master) | rekonsiliasi status payout/withdrawal |
</content>
