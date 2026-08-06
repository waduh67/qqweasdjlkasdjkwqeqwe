# Payment gateway Pivot — arsitektur master + sub-account

Bagian dari modul [`billing`](billing.md). Ini adalah **satu-satunya** payment gateway
proyek: sejak migrasi penuh, seluruh penyedia lain (Xendit/Midtrans/Paywuz) **dihapus**
begitu pula model **BYOK** (bring-your-own-key) per-tenant. Yang tersisa hanya Pivot
([pivot-payment.com](https://pivot-payment.gitbook.io/pivot-docs)) dalam model
**"business as platform"**:

- **satu akun MASTER** milik platform menampung seluruh transaksi, dan
- **satu sub-account Pivot per tenant** yang dipakai untuk menagih pelanggan tenant
  atas nama platform.

Dokumen turunan:

| Topik | Doc |
|---|---|
| Sub-account tenant (provisioning, NON_KYC vs KYC, lifecycle, rekening payout) | [`pivot-sub-account.md`](pivot-sub-account.md) |
| Fee platform via split routing (FIXED/PERCENTAGE) | [`pivot-fee-split.md`](pivot-fee-split.md) |
| Payout / withdrawal + rekonsiliasi saldo | [`pivot-payout.md`](pivot-payout.md) |
| Mesin tagihan & pelunasan (invoice/payment) | [`billing.md`](billing.md) |
| Penagihan langganan SaaS platform→tenant | [`saas-subscription.md`](saas-subscription.md) |
| Setelan gateway per-tenant + pembayaran manual | [`payment-gateway.md`](payment-gateway.md) |

---

## Dua level, satu akun master

| Aliran uang | Charge di | Atas nama | Split fee | Dana mendarat di |
|---|---|---|---|---|
| **Tagihan pelanggan tenant** (modul `billing`) | akun MASTER | sub-account tenant (`x-submerchant-id`) | ya, fee platform terpotong dari hasil tenant | balance master (NON_KYC) / balance sub-account (KYC) |
| **Langganan SaaS tenant** (modul `platformbilling`) | akun MASTER | — (`subAccountId=null`) | tidak — 100% ke platform | balance master platform |

Keduanya memakai adapter Pivot yang sama (`PivotPaymentGateway`) dan kredensial master
yang sama; bedanya hanya ada/tidaknya `x-submerchant-id` + split routing. Lihat
[`pivot-fee-split.md`](pivot-fee-split.md).

---

## Setelan MASTER — `pivot_master_config` (V70)

Singleton global **PLATFORM-level, tanpa RLS** (pola `platform_setting`). Menyimpan
kredensial akun master + kebijakan fee + rekening payout platform. Kredensial
**terenkripsi** di batas persistence (AES-GCM, sama pola gateway lama) — DB tak pernah
melihat rahasia asli; view hanya menandai `*Set`.

```
pivot_master_config  (satu baris, tanpa RLS)
├── enabled                 gerbang keras: mati = seluruh Pivot dorman → fallback MANUAL
├── merchant_id             ciphertext — X-MERCHANT-ID master (juga tujuan split fee)
├── merchant_secret         ciphertext — X-MERCHANT-SECRET master
├── callback_api_key        ciphertext — Callback API Key (verifikasi X-API-Key semua webhook)
├── sandbox                 true → api-stg.pivot-payment.com, false → api.pivot-payment.com
├── platform_fee_minor      fee platform per transaksi (minor unit IDR). 0 = tanpa split
├── platform_fee_type       FIXED | PERCENTAGE                          (CHECK)
├── payout_channel_code     rekening payout platform (non-rahasia) — tujuan withdrawal master
└── payout_account_number
```

- Dikelola super-admin di **`/api/platform/pivot-config`** (`PivotMasterConfigController`,
  izin `platform.billing.view`/`manage`). Rahasia **write-only**: null/kosong saat menyunting
  = "biarkan apa adanya", agar edit fee/payout tak menghapus kredensial.
- Domain `PivotMasterConfig` meng-`resolveContext()` → `PivotMasterContext` terdekripsi,
  atau **null** bila `enabled=false` / kredensial belum lengkap (`merchantId`+`merchantSecret`).
- Di-expose ke modul `platformbilling` lewat `PivotMasterConfigProvider`
  (`@NamedInterface("gateway")`) supaya penagihan SaaS memakai akun master yang sama
  **tanpa** menembus enkapsulasi billing / menimbulkan siklus modul.

---

## Auth, lingkungan & idempotency (`PivotApiClient`)

Satu klien HTTP bersama untuk seluruh permintaan Pivot (charge, sub-account, payout,
withdrawal, balance).

| Aspek | Detail |
|---|---|
| **Base URL** | prod `https://api.pivot-payment.com` · sandbox `https://api-stg.pivot-payment.com` (dipilih dari `sandbox`) |
| **Auth** | `POST /v1/access-token` (header `X-MERCHANT-ID` + `X-MERCHANT-SECRET`) → Bearer token hidup ~900 dtk, di-**cache per merchant-id** (disegarkan ~60 dtk sebelum kedaluwarsa) |
| **On-behalf-of** | header `x-submerchant-id` menjalankan aksi atas nama sub-account tenant (charge pelanggan, withdrawal KYC) |
| **Idempotency** | header `X-REQUEST-ID` (alfanumerik 16–36 char) pada create payment/payout — deterministik dari nomor tagihan / id baris payout → retry aman |
| **Galat HTTP** | seragam jadi `ConflictException` (status + potongan body dicatat log) |

`RestClient` dibangun per-panggilan (base URL bisa beda antar akun sandbox/prod); klien
adalah singleton stateless kecuali cache token.

---

## Keamanan callback — header `X-API-Key`

**SEMUA** callback Pivot (pembayaran tenant, langganan SaaS, payout/withdrawal)
diverifikasi oleh satu mekanisme: header **`X-API-Key`** harus sama dengan **Callback API
Key master** (`pivot_master_config.callback_api_key`), dibandingkan **constant-time**
(`MessageDigest.isEqual`). Bukan HMAC per-tenant — satu key master untuk seluruh webhook.

| Callback | Endpoint | Verifikasi |
|---|---|---|
| Pelunasan tagihan pelanggan | `POST /api/billing/webhooks/{tenantSlug}/pivot` | `X-API-Key` = callback key master; status ∈ {PAID, SETTLED, SUCCESS} → settlement |
| Pelunasan langganan SaaS | `POST /api/platform/billing/webhooks/pivot` | idem (tanpa tenant di path — level platform) |
| Status payout/withdrawal | `POST /api/billing/webhooks/{tenantSlug}/pivot-payout` | idem; menutup baris `tenant_payout` (bukan invoice) |

Endpoint webhook `permitAll` (gateway eksternal tak membawa JWT); keasliannya dijamin
`X-API-Key`. Tenant di-resolve dari **slug** di path lalu dipasang ke `TenantContext` agar
tulisan patuh RLS. Key master hanya callback key kosong/tak cocok → callback ditolak 4xx
tanpa menyentuh data.

---

## Ringkasan alur uang

```
Pelanggan bayar tagihan
  └─ POST /v2/payments (MASTER, x-submerchant-id=<sub tenant>, mode REDIRECT)
       ├─ splitRoutingConfigurations → fee platform (FIXED) ke merchantId master
       └─ paymentUrl → dilekatkan ke invoice (payUrl)
  ─── pelanggan membayar di halaman hosted Pivot ───
  └─ callback POST /api/billing/webhooks/{slug}/pivot  (X-API-Key master)
       └─ status PAID → invoice.markPaid + auto-pulih langganan pelanggan

Dana tenant (NON_KYC) mengendap di balance MASTER
  └─ payout: POST /v1/payouts (master → rekening tenant tervalidasi)   [pivot-payout.md]
       └─ callback POST /api/billing/webhooks/{slug}/pivot-payout → tutup tenant_payout

Langganan SaaS tenant (pemasukan platform)
  └─ POST /v2/payments (MASTER, tanpa sub-account, tanpa split) → 100% ke platform
       └─ callback POST /api/platform/billing/webhooks/pivot
```

---

## Konfigurasi

Kredensial & lingkungan (sandbox) Pivot **tidak** di env melainkan di `pivot_master_config`
(dirotasi tanpa redeploy). Yang tersisa di env/`ftth.billing`:

| Properti / env | Bawaan | Guna |
|---|---|---|
| `pivot.redirect-base-url` · `FTTH_BILLING_PIVOT_REDIRECT_BASE_URL` | `""` | basis URL balik mode REDIRECT (WAJIB bila Pivot aktif); charge menurunkan `<base>/paid`, `/failed`, `/expired`. Kosong = charge gagal jelas |
| `webhook-secret` · `FTTH_BILLING_WEBHOOK_SECRET` | *(dev)* | secret verifikasi callback **MANUAL** (fallback saat Pivot nonaktif) |

---

## Checklist menyalakan Pivot

1. **Super-admin** buka setelan master (`/api/platform/pivot-config`): isi Merchant ID,
   Merchant Secret, Callback API Key (dari dashboard Pivot), pilih sandbox/prod, set fee
   platform (FIXED/PERCENTAGE) + rekening payout platform, lalu **aktifkan**.
2. Set `FTTH_BILLING_PIVOT_REDIRECT_BASE_URL` di server (URL balik REDIRECT).
3. Daftarkan URL callback di dashboard Pivot: `{origin}/api/billing/webhooks/{slug}/pivot`
   (tagihan), `{origin}/api/billing/webhooks/{slug}/pivot-payout` (payout), dan
   `{origin}/api/platform/billing/webhooks/pivot` (langganan SaaS).
4. Sub-account tenant **terprovisi otomatis** saat onboarding (NON_KYC); tenant lama
   pra-fitur diprovisi lewat `POST /api/billing/pivot-account/provision`. Lihat
   [`pivot-sub-account.md`](pivot-sub-account.md).
</content>
</invoke>
