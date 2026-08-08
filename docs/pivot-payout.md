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
balance (dibaca via `GET /v1/balances`, lihat [Saldo](#saldo--dua-dompet-yang-berbeda)).

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
POST /api/billing/pivot-account/payouts { channelCode, accountNumber, accountName,
                                          amountMinor, description? }  (billing.gateway.manage)
  └─ TenantPayoutService.dispatchPayout:
       ├─ POST /v1/inquiry-account (x-submerchant-id) → inquiryId, wajib VALID
       ├─ TenantPayout.create(PAYOUT, amount, rekening snapshot) → PENDING
       ├─ ensurePayoutBalance  ← jembatan dua dompet, lihat di bawah
       ├─ POST /v1/payouts (x-submerchant-id=<sub tenant>)
       │    { payouts: [ { referenceId, inquiryId,
       │                   amount: { value: "<rupiah utuh>", currency: "IDR" }, description? } ] }
       │      → PayoutDispatch(reference, settledImmediately)
       ├─ markProcessing(reference)  (+ markSuccess bila settledImmediately)
       └─ save + audit "billing.pivot.payout.dispatched"
```

- `amount.value` **string**, bukan angka JSON. Pernah dikirim sebagai angka dan Pivot menolak
  SEMUA payout `400 field_format_invalid` — "Make sure value format is correct".
- `description` payout jauh lebih ketat daripada withdrawal: **maks 20 karakter, alfanumerik
  saja** (withdrawal 50, bebas). Dibersihkan diam-diam di gateway — catatan kosmetik tak layak
  menggagalkan penyaluran uang.
- Ada **minimum nominal** di sisi Pivot (`amount_below_limit`); nominal receh ditolak.
- `inquiryId` hasil validasi rekening di alur yang sama (lihat
  [`pivot-sub-account.md`](pivot-sub-account.md)) — bukan `payout_inquiry_id` tersimpan, karena
  rekening tujuan payout bebas diketik per transaksi.

### `ensurePayoutBalance` — jembatan dompet PAYMENT → DISBURSEMENT

`POST /v1/payouts` **hanya** menarik dari saldo DISBURSEMENT, sedangkan uang tenant mendarat di
saldo PAYMENT. Dulu ini cuma guard yang melempar "top up dulu"; sekarang dijembatani:

```
saldo DISBURSEMENT >= amount ?  ── ya ──▶ lanjut, TAK ada pemindahan sama sekali
        │ tidak
        ▼
kurang = amount − saldo DISBURSEMENT
saldo PAYMENT >= kurang ?  ── tidak ──▶ ConflictException (nominal kurangnya disebut)
        │ ya
        ▼
POST /v1/withdrawals (x-submerchant-id)
  { referenceId: "trf-<payoutId>", withdrawType: "BALANCE_TRANSFER",
    balanceType: "PAYOUT_BALANCE", isFullAmount: false, amount: { value: "<kurang>", … } }
  └─ gagal ⇒ MELEMPAR ⇒ payout ikut batal (tak ada payout tanpa saldo)
  └─ audit "billing.pivot.payout.balance_transferred"
```

- **Cek dulu, pindah belakangan** — saldo payout yang sudah cukup tak disentuh.
- Yang dipindahkan **hanya kekurangannya**, bukan nominal penuh: dana di dompet payout tak bisa
  dipakai menagih, jadi jangan memindahkan lebih dari perlu.
- `X-REQUEST-ID` pemindahan diberi prefiks `trf`, berbeda dari payoutnya (prefiks `req`). Kalau
  sama, Pivot menganggap payout sekadar pengulangan pemindahan saldo tadi.

### Biaya payout ditagih ke saldo payout MASTER — bukan sub-account

Satu payout mendebit **dua dompet sekaligus**, dan `ensurePayoutBalance` cuma mengurus yang pertama:

| Dompet | Yang didebit |
| --- | --- |
| DISBURSEMENT **sub-account** | nominal payout (Rp 10.000 pada uji sandbox) |
| DISBURSEMENT **master/platform** | biaya payout (Rp 4.000), juga biaya `ACCOUNT_INQUIRY_FEE` Rp 450 per inquiry |

Kalau saldo payout master **tak cukup menutup biayanya**, Pivot tetap membalas `code: 00` +
`status: IN_PROGRESS` — permintaannya diterima — lalu payout menggantung `status: PENDING`,
`payouts[0].status: APPROVED`, `reason: "Insufficient Balance"`. Di dashboard ia mendarat di
**Local Payout → Need Action → Waiting for Top Up**, menunggu di-*retry* atau dibatalkan manual.
Saldo sub-account yang berlimpah **tak menolong** — dibuktikan di sandbox: sub punya 500.000, master
−5.400, payout Rp 10.000 tetap tergantung; begitu saldo payout master diisi, payout identik langsung
`DONE`/`SUCCESS`.

Konsekuensi operasional: saldo payout master wajib dijaga positif, dan **gagalnya senyap** —
tak ada exception yang bisa ditangkap `dispatchPayout`, statusnya baru ketahuan lewat webhook
atau `GET /v1/payouts/{uuid}`.

## Alur withdrawal KYC

```
POST /api/billing/pivot-account/withdrawals { amountMinor, remarks? }   (billing.gateway.manage)
  └─ TenantPayoutService.withdraw:
       ├─ syarat: account.type == KYC & provisioned (punya sub_merchant_uuid)
       ├─ guard: saldo PAYMENT sub-account >= amount  → else ConflictException
       ├─ TenantPayout.create(WITHDRAWAL, amount, …) → PENDING
       ├─ POST /v1/withdrawals (x-submerchant-id=<sub tenant>)
       │    { referenceId, withdrawType: "BANK_TRANSFER", isFullAmount: false,
       │      amount: { value: "<rupiah utuh>", currency: "IDR" }, description? }
       ├─ markProcessing(reference)  (+ markSuccess bila settledImmediately)
       └─ save + audit "billing.pivot.withdrawal.dispatched"
```

Body-nya **tidak** memuat `channelCode`/`accountNumber`/`inquiryId`: `BANK_TRANSFER` selalu
menuju rekening yang sudah melekat di sub-account (dikirim sebagai `bankAccount` saat create,
lihat [`pivot-sub-account.md`](pivot-sub-account.md)). `balanceType` hanya wajib untuk
`withdrawType = BALANCE_TRANSFER` — varian itu dipakai `ensurePayoutBalance` di alur payout.
`description` dipangkas ke 50 karakter sesuai batas spec.

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

## Saldo — dua dompet yang BERBEDA

Satu merchant Pivot punya beberapa saldo terpisah. Salah pilih dompet = saldo terbaca **nol**
padahal dananya ada, jadi `PivotBalanceUsecase` selalu ditulis eksplisit:

| Dompet | Endpoint | Isinya | Dipakai untuk |
|---|---|---|---|
| **PAYMENT** | `GET /v1/balances?usecase=PAYMENT` | hasil tagihan pelanggan (charge VA/QRIS masuk ke sini) | yang **ditampilkan** ke tenant; sumber dana `POST /v1/withdrawals` |
| **DISBURSEMENT** | `GET /v1/balances?usecase=DISBURSEMENT` | dana untuk **mengirim** uang keluar; diisi lewat top-up VA atau `BALANCE_TRANSFER` dari PAYMENT | sumber dana `POST /v1/payouts` |

Keduanya dijembatani `ensurePayoutBalance` (lihat [alur payout](#alur-payout-non_kyc)) — saldo
pembayaran **bisa** dipakai untuk payout, tapi harus dipindahkan dulu, tak bisa ditarik langsung.

Tabel di atas soal dompet **sub-account**. Dompet DISBURSEMENT **master** juga ikut terdebit tiap
payout (biaya payout & inquiry) dan wajib positif — lihat
[Biaya payout ditagih ke saldo payout MASTER](#biaya-payout-ditagih-ke-saldo-payout-master--bukan-sub-account).

> **Jangan pakai `GET /v1/payouts/balance`.** Itu alias dompet DISBURSEMENT. Sub-account
> penagih isinya `0.00` di situ sementara uangnya duduk di PAYMENT — persis bug yang bikin
> halaman Payment Gateway selalu menampilkan Rp 0.

`GET /api/billing/pivot-account/balance` (`billing.gateway.view`) membaca **PAYMENT**:

- Akun **KYC** terprovisi → dibaca dari balance **sub-account** tenant (`x-submerchant-id`),
  `subAccount=true`.
- selain itu → dibaca dari balance **master** platform (dana NON_KYC), `subAccount=false`.

`PivotBalanceView` mengembalikan `availableMinor` + `currency` (bawaan IDR). Tak ada
`pendingMinor`: API `/v1/balances` hanya mengekspos `availableBalance` — pending balance cuma
ada di dasbor Pivot. Nilainya string desimal 2-angka (`"268000.99"`) → dibulatkan **ke bawah**
jadi rupiah utuh; bentuk respons dibaca defensif karena sandbox/prod kadang beda pembungkusnya.

> **Belum dipakai.** `POST /v1/balances/sub-merchants { usecase }` mengembalikan saldo **semua**
> sub-account sekaligus (terpaginasi) — calon dasar dasbor saldo tenant tingkat platform.

---

## Endpoint ringkas

| Endpoint | Izin | Guna |
|---|---|---|
| `GET /api/billing/pivot-account/balance` | `billing.gateway.view` | saldo **PAYMENT** (master NON_KYC / sub-account KYC) |
| `GET /api/billing/pivot-account/payouts` | `billing.gateway.view` | riwayat penyaluran tenant |
| `POST /api/billing/pivot-account/payouts` | `billing.gateway.manage` | salurkan dana NON_KYC ke rekening tenant |
| `POST /api/billing/pivot-account/withdrawals` | `billing.gateway.manage` | tarik saldo sub-account KYC |
| `POST /api/platform/pivot/callbacks/payout` · `.../withdrawal` · `.../international-payout` | publik (`X-API-Key` master) | rekonsiliasi status payout/withdrawal |
</content>
