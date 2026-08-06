# Payment gateway multi-tenant — Pivot (master + sub-account) + fallback manual

Bagian dari modul [`billing`](billing.md). Halaman ini merangkum **setelan payment gateway
per-tenant**: metode aktif (Pivot otomatis vs manual) dan instruksi pembayaran manual
(transfer/QRIS). Arsitektur Pivot lengkap (auth, fee split, payout) dipecah ke doc terpisah:

| Topik | Doc |
|---|---|
| Arsitektur master + sub-account, auth/env, callback `X-API-Key`, alur uang | [`pivot-overview.md`](pivot-overview.md) |
| Sub-account tenant (provisioning, NON_KYC/KYC, lifecycle, rekening payout) | [`pivot-sub-account.md`](pivot-sub-account.md) |
| Fee platform via split routing | [`pivot-fee-split.md`](pivot-fee-split.md) |
| Payout / withdrawal + rekonsiliasi | [`pivot-payout.md`](pivot-payout.md) |

> **Migrasi penuh ke Pivot.** Penyedia lama **Xendit**, **Midtrans**, dan **Paywuz** serta
> model **BYOK** (bring-your-own-key) per-tenant telah **DIHAPUS** (migrasi V69). Tidak ada
> lagi kredensial gateway tersimpan di baris tenant; seluruh transaksi berjalan di **satu
> akun MASTER Pivot** milik platform, tiap tenant jadi **sub-account** yang ditagih
> on-behalf. Satu-satunya alternatif adalah pembayaran **MANUAL** (transfer/QRIS) sebagai
> fallback saat Pivot nonaktif.

---

## Model penagihan

| Metode | Siapa punya akun | Kredensial | Uang mendarat di | Fee platform |
|---|---|---|---|---|
| **PIVOT** | platform | akun **MASTER** platform (`pivot_master_config`) + **sub-account** per tenant | balance master (NON_KYC) / sub-account (KYC) | via **split routing** (FIXED/PERCENTAGE) |
| **MANUAL** | tenant | — (instruksi transfer/QRIS, non-rahasia) | rekening tenant langsung (luar-band) | — |

Tak ada lagi mode BYO/PLATFORM per-penyedia: Pivot **selalu** berjalan di akun master
(secara internal `GatewayMode.PLATFORM`), MANUAL adalah `BYO` sekadar penanda.

---

## Model data — `tenant_payment_gateway` (V50, dirampingkan V69)

Satu baris per tenant (`UNIQUE (tenant_id)`), RLS dua-lapis. Setelah V69 **tak lagi
menyimpan kredensial gateway apa pun** — hanya metode aktif + konfigurasi manual.

```
tenant_payment_gateway
├── provider        PIVOT | MANUAL              (CHECK ck_tpg_provider)
├── enabled         default false
│
│   Pembayaran manual (V54) — plaintext, non-rahasia, semantik "selalu diganti":
├── manual_transfer_enabled  saklar transfer bank
├── transfer_bank_name / transfer_account_number / transfer_account_holder
├── manual_qris_enabled       saklar QRIS
├── qris_storage_key          key byte gambar QRIS di object storage (bukan byte-nya)
└── qris_content_type         MIME gambar QRIS
```

Kolom yang **dibuang V69**: `mode`, `api_key`, `secret_key`, `webhook_token`,
`sub_account_id`, `payment_method` (semuanya artefak BYOK/multi-provider lama).

- **Default aman:** `MANUAL / MATI` — perilaku lama (webhook MANUAL bersecret global) tetap
  berlaku sampai tenant mengaktifkan Pivot dengan sadar.
- **Kredensial Pivot bukan di sini** melainkan di `pivot_master_config` (platform-level).
  Sub-account tenant di `tenant_pivot_account` (lihat [`pivot-sub-account.md`](pivot-sub-account.md)).

---

## Resolusi gateway (`TenantPaymentGatewayResolver`)

Satu tempat yang memetakan setelan mentah → `ResolvedGatewayContext`, dipanggil **baik saat
charge maupun callback**.

```
resolver.resolve()
  └─ settings = repo.find()
       ?.usesPivot (enabled & provider=PIVOT)
       ├─ master = PivotMasterConfigProvider.current()            ← pivot_master_config aktif
       ├─ sub    = tenant_pivot_account (provisioned & bukan DEACTIVATED/REJECTED)
       └─ bila master & sub siap → PIVOT (mode PLATFORM):
             secretKey=master.merchantSecret, webhookToken=master.callbackApiKey,
             subAccountId=sub.uuid, apiKey=master.merchantId, sandbox,
             platformFeeMinor/Type
       ?: manualFallback()  ← MANUAL / secretKey null / webhookToken = ftth.billing.webhook-secret
```

| Kondisi | Hasil resolve |
|---|---|
| tenant PIVOT aktif **&** master aktif **&** sub-account siap | PIVOT (on-behalf + split fee) |
| tenant PIVOT tapi master nonaktif / sub-account belum siap | **fallback MANUAL** (instruksi bayar, bukan charge yang pasti gagal) |
| tenant MANUAL / nonaktif | MANUAL (shared secret global) |

Sub-account "siap" = `provisioned` dan status **bukan** `DEACTIVATED`/`REJECTED`.

---

## Charge & callback Pivot (ringkas)

Detail lengkap di [`pivot-overview.md`](pivot-overview.md).

**Charge** (`PivotPaymentGateway.createCharge` → `POST /v2/payments`, mode REDIRECT):
di akun master, `x-submerchant-id` = sub-account tenant, `splitRoutingConfigurations`
memotong fee platform. IDR zero-decimal. Idempotency `X-REQUEST-ID` dari nomor tagihan.
Butuh `ftth.billing.pivot.redirect-base-url` (URL balik `/paid`, `/failed`, `/expired`).
Hasil: `ChargeResult(provider="PIVOT", gatewayRef=data.id, payUrl=data.paymentUrl)`.

**Callback** (`POST /api/billing/webhooks/{tenantSlug}/pivot`): header **static `X-API-Key`**
= Callback API Key **master** dibanding **constant-time** (bukan HMAC per-tenant); hanya
status `PAID`/`SETTLED`/`SUCCESS` jadi settlement
`PaymentSettlement(clientReferenceId, id, amount.value, chargeDetails[0].paidAt ?: now)`.

---

## Pembayaran manual (transfer / QRIS) — V54

Saat gateway **nonaktif** (atau provider `MANUAL`), tenant "cuma bisa manual" — dan
`ManualPaymentGateway` tak punya tautan bayar. Fitur ini mengisi celah itu: tenant mengatur
**instruksi bayar manual** yang tampil ke pelanggan pada tagihan MANUAL. Dua metode
independen, tiap saklar membuka fieldnya:

- **Transfer bank** — `transfer_bank_name`, `transfer_account_number`, `transfer_account_holder`.
- **QRIS** — unggah **gambar QRIS**; byte disimpan di **object storage** (bukan DB), DB hanya
  simpan `qris_storage_key` + `qris_content_type`.

Semua field manual **non-rahasia** → **plaintext, tak dienkripsi, semantik "selalu diganti"**
(bukan write-only seperti kredensial). Toggle & teks disimpan lewat
`PUT /api/billing/gateway-settings` biasa; hanya byte gambar QRIS yang lewat endpoint
multipart terpisah.

**Object storage QRIS.** Port `ObjectStorage`/`StoredObject` di
`com.duluin.ftth.common.storage` (adapter S3/MinIO). Key QRIS satu per tenant:
**`"$tenantId/billing/gateway/qris"`** (unggah ulang menimpa). Validasi: `contentType` harus
`image/*`, maksimal **5 MB**. Pola "taruh byte dulu, baru simpan metadata".

**UI (halaman Payment Gateway).** Saat `!enabled || provider === 'MANUAL'`, seksi
**"Pembayaran manual"** muncul. Unggah/hapus gambar QRIS **ditunda** ke satu alur "Tinjau &
simpan" bersama edit transfer — PUT setelan dulu, baru unggah/hapus byte. Preview gambar
pakai pola `AuthedImage` (`api.blob` → `createObjectURL`).

**Tampilan ke pelanggan.** `CustomerDetailPage` memanggil
`GET /api/billing/manual-payment-instructions` untuk merender panel "Cara bayar" pada
tagihan MANUAL yang belum lunas: rekening (bila transfer aktif) + gambar QRIS (bila QRIS
aktif & tersedia).

---

## API

| Endpoint | Izin |
|---|---|
| `GET /api/billing/gateway-settings` | `billing.gateway.view` |
| `PUT /api/billing/gateway-settings` (metode aktif + manual) | `billing.gateway.manage` |
| `POST /api/billing/gateway-settings/qris` (multipart `file`) | `billing.gateway.manage` |
| `DELETE /api/billing/gateway-settings/qris` | `billing.gateway.manage` |
| `GET /api/billing/gateway-settings/qris` (byte gambar) | `billing.gateway.view` / `billing.invoice.view` |
| `GET /api/billing/manual-payment-instructions` | `billing.invoice.view` |
| `GET/POST /api/billing/pivot-account/**` (sub-account, saldo, payout) | `billing.gateway.view` / `manage` — lihat [`pivot-sub-account.md`](pivot-sub-account.md) / [`pivot-payout.md`](pivot-payout.md) |
| `GET/PUT /api/platform/pivot-config` (setelan master Pivot) | `platform.billing.view` / `manage` |
| `POST /api/billing/webhooks/{tenantSlug}/pivot` | publik (`X-API-Key` master) |
| `POST /api/billing/webhooks/{tenantSlug}/pivot-payout` | publik (`X-API-Key` master) |

`billing.gateway.view`/`manage` masuk role Tenant Admin otomatis. Web: halaman **Payment
Gateway** menampilkan metode aktif, status sub-account, dan seksi pembayaran manual.

---

## Konfigurasi (`ftth.billing`)

| Properti / env | Bawaan | Guna |
|---|---|---|
| `webhook-secret` · `FTTH_BILLING_WEBHOOK_SECRET` | *(dev)* | secret verifikasi callback **MANUAL** (fallback global) |
| `pivot.redirect-base-url` · `FTTH_BILLING_PIVOT_REDIRECT_BASE_URL` | `""` | Pivot: basis URL balik (mode REDIRECT WAJIB); kosong = charge Pivot gagal jelas |

> Kredensial & lingkungan (sandbox) Pivot **tidak** di env melainkan di `pivot_master_config`
> (setelan super-admin), agar dirotasi tanpa redeploy. `default-provider` lawas usang —
> resolver memilih adapter dari baris config tenant + master.

---

## Keamanan

- **Webhook publik.** `/api/billing/webhooks/**` di-`permitAll`; endpoint setelan tetap
  butuh bearer + `@PreAuthorize`. Keaslian callback Pivot dijamin `X-API-Key` = Callback API
  Key **master** (constant-time, `MessageDigest.isEqual`); MANUAL pakai `X-Billing-Signature`.
- **RLS dua-lapis** pada `tenant_payment_gateway` & `tenant_pivot_account` & `tenant_payout`:
  tenant B tak pernah lihat/timpa baris tenant A.
- **Kredensial master write-only**, terenkripsi di batas persistence; `sub_merchant_uuid` &
  rekening payout aman ditampilkan (non-rahasia).
</content>
