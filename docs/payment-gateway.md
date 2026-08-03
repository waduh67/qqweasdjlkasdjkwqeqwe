# Payment gateway multi-tenant (Xendit BYO + PLATFORM)

Bagian dari modul [`billing`](billing.md). Menggantikan "satu gateway global +
satu webhook secret bersama" dengan **satu baris konfigurasi gateway per tenant**:
tiap tenant memilih penyedia, mode, dan kredensialnya sendiri.

Dua mode:

| Mode | Siapa punya akun | Kredensial | Uang mendarat di | Komisi platform |
|---|---|---|---|---|
| **BYO** | tenant | akun gateway tenant sendiri (secret di baris tenant, terenkripsi) | rekening tenant langsung | — |
| **PLATFORM** | platform | akun **MASTER** platform (config/env) + **sub-account** xenPlatform per tenant | balance sub-account tenant di platform | via `fee_rule` (header `with-fee-rule`) |

Xendit digarap penuh (BYO **dan** PLATFORM); **Pivot** (pivot-payment.com) dan **Paywuz**
(paywuz.id) digarap penuh **BYO**. Semua adapter berbagi skema `tenant_payment_gateway` yang
sama — tambah provider tak butuh migrasi.

---

## Model data — `tenant_payment_gateway` (V50)

Satu baris per tenant (`UNIQUE (tenant_id)`), RLS dua-lapis seperti tabel bisnis lain.

```
tenant_payment_gateway
├── provider        XENDIT | PAYWUZ | PIVOT | MANUAL   (CHECK)
├── mode            BYO | PLATFORM                     (CHECK)
├── enabled         default false
├── api_key         ciphertext — penyedia key-pair (Paywuz pk_.../Pivot)
├── secret_key      ciphertext — Xendit BYO secret key
├── webhook_token   ciphertext — token verifikasi callback per-tenant
│                               (PLATFORM = token sub-account)
├── sub_account_id  PLATFORM: user_id sub-account Xendit (header for-user-id)
├── payment_method  plaintext — Paywuz BYO: kode metode per-tenant (V51; bukan rahasia)
│
│   Pembayaran manual (V54) — plaintext, bukan rahasia, semantik "selalu diganti":
├── manual_transfer_enabled  saklar transfer bank
├── transfer_bank_name / transfer_account_number / transfer_account_holder
├── manual_qris_enabled       saklar QRIS
├── qris_storage_key          key byte gambar QRIS di object storage (bukan byte-nya)
└── qris_content_type         MIME gambar QRIS
```

- **Kredensial write-only.** Batas enkripsi ada di adapter persistence (`SecretCipher`
  AES-256-GCM) — DB tak pernah melihat rahasia asli, sama seperti token gateway WA
  notifikasi & secret CoA BRAS. GET setelan hanya menandakan sudah terisi/belum
  (`secretKeySet`/`webhookTokenSet`), tak pernah mengembalikan nilainya.
- **Kredensial MASTER platform TIDAK di sini** melainkan di config/env, supaya
  charge/callback tak perlu membaca lintas-RLS (baris tenant lain).
- **Default aman:** `MANUAL / BYO / MATI` — perilaku lama (webhook MANUAL bersecret
  global) tetap berlaku sampai tenant mengonfigurasi gateway dengan sadar.

---

## Resolusi gateway (`TenantPaymentGatewayResolver`)

Satu tempat yang memetakan setelan mentah → bentuk siap-pakai `ResolvedGatewayContext`,
dipanggil **baik saat penerbitan tagihan (charge) maupun saat callback webhook (verifikasi)** —
meniru `NotificationSender` di modul notification.

```
resolver.resolve()
  └─ repo.find()                       ← baris config tenant aktif (via RLS)
       ?.resolve(platformCreds())      ← TenantPaymentGateway.resolve(...)
       ?: MANUAL fallback              ← ResolvedGatewayContext("MANUAL", BYO,
                                          secretKey=null, webhookToken=props.webhookSecret)
```

`TenantPaymentGateway.resolve(platform)` mengembalikan `null` (→ fallback MANUAL) bila
gateway tak lengkap; jika tidak, `ResolvedGatewayContext(provider, mode, secretKey,
webhookToken, subAccountId?, feeRuleId?)`:

| Mode / provider | Syarat resolve ≠ null | `secretKey` | `webhookToken` |
|---|---|---|---|
| `enabled = false` | — (selalu null → MANUAL) | — | — |
| BYO · XENDIT | `secret_key` terisi | key tenant | token tenant |
| BYO · PIVOT | selalu (adapter validasi) | `secret_key` (merchant secret) + `api_key` (merchant id) | token tenant (Callback API Key) |
| BYO · PAYWUZ | selalu (adapter validasi) | `api_key` (jadi `secretKey`: Bearer **&** secret HMAC webhook) | — (pakai `secretKey`) |
| BYO · MANUAL | selalu | — | token tenant |
| PLATFORM · XENDIT | `platform.enabled` **&** master secret **&** `sub_account_id` | key **MASTER** | token sub-account **?:** token platform |

- `platformCreds()` = `null` bila `ftth.billing.platform.enabled=false` atau secret
  master kosong → tenant mode PLATFORM ikut jatuh ke fallback MANUAL (dorman).
- PLATFORM hanya untuk XENDIT (xenPlatform) di v1; provider PLATFORM lain → null.

---

## Xendit — charge (`POST /v2/invoices`)

Adapter `XenditPaymentGateway` singleton stateless: `RestClient` dibangun **per-charge**
karena secret key beda tiap tenant. Basic-auth = secret key sebagai username, password
kosong (pola `RouterOsRestAdapter`/`WhatsAppMessageDispatcher`).

```
body = { external_id, amount, currency:"IDR", description,
         customer:{given_names}, payer_email?, invoice_duration }
```

- **IDR zero-decimal:** `amount` dikirim `setScale(0, HALF_UP)` (nilai tagihan di DB
  tetap scale-2, hanya angka yang dikirim dibulatkan — Xendit tolak desimal untuk IDR).
- `invoice_duration` = `due-days × 86400` detik (tautan bayar hidup sepanjang jendela jatuh tempo).
- **Mode PLATFORM** menambah header `for-user-id: <sub_account_id>` (tagih atas nama
  sub-account tenant) + `with-fee-rule: <fee_rule_id>` (potong komisi platform).
- Hasil: `ChargeResult(provider="XENDIT", gatewayRef=id, payUrl=invoice_url)`; `payUrl`
  dilekatkan ke tagihan.
- Charge yang ditolak Xendit → `ConflictException`; di `InvoiceGenerator` tiap charge
  dibungkus `runCatching` sehingga satu tenant/langganan gagal **tak** membatalkan ronde.

## Xendit — callback (`x-callback-token`)

```
POST /api/billing/webhooks/{tenantSlug}/xendit   (publik, tanpa bearer)
  └─ header x-callback-token  ==(constant-time, MessageDigest.isEqual)==  ctx.webhookToken
       └─ status ∈ {PAID, SETTLED}  →  PaymentSettlement(external_id, id,
                                        paid_amount ?: amount, paid_at ?: now)
```

- Token verifikasi kosong / tak cocok / status bukan pelunasan → `null` (callback ditolak
  4xx, tagihan tak disentuh).
- Segmen `{provider}` di path hanya untuk routing/log — **sumber kebenaran adapter =
  resolver** (baris config tenant). Kalau path ≠ provider terkonfigurasi, ikuti resolver.

---

## Pivot (pivot-payment.com) — BYO

Adapter `PivotPaymentGateway` (BYO). Beda dari Xendit, Pivot butuh **sepasang kredensial** dan
**auth dua-langkah**, jadi `ResolvedGatewayContext` dilebarkan satu field `apiKey` (merchant id):

| Kolom DB | Peran Pivot | Header |
|---|---|---|
| `api_key` | merchant id | `X-MERCHANT-ID` (tukar token) |
| `secret_key` | merchant secret | `X-MERCHANT-SECRET` (tukar token) |
| `webhook_token` | Callback API Key | `X-API-Key` (verifikasi callback) |

**Charge** (`POST /v2/payments`, payment session mode REDIRECT):

```
1. POST /v1/access-token  (X-MERCHANT-ID + X-MERCHANT-SECRET)  → { accessToken, expiresIn:900 }
     └─ token di-cache per merchant-id (~14 mnt) → satu ronde banyak-tagihan tak tukar berulang
2. POST /v2/payments  Authorization: Bearer <token>
     body { clientReferenceId, amount:{value,currency:IDR}, mode:REDIRECT,
            redirectUrl:{success/failure/expiration}, customer:{givenName,email?},
            orderInformation:{productDetails:[…]}, metadata }
     → ChargeResult(provider="PIVOT", gatewayRef=data.id, payUrl=data.paymentUrl)
```

- **IDR zero-decimal:** `amount.value` dikirim `setScale(0, HALF_UP)` (sama seperti Xendit).
- **`redirectUrl` WAJIB** (mode REDIRECT) → tiga URL diturunkan dari `ftth.billing.pivot.redirect-base-url`
  (`<base>/paid`, `/failed`, `/expired`). Kosong → charge Pivot gagal jelas (`ConflictException`).
- **`orderInformation` minimal-jujur:** satu baris layanan `DIGITAL`; field ber-enum
  (`category`/`subCategory`/`shippingInfo`) diomit — nilainya belum terverifikasi, dan `billingInfo`
  hanya wajib untuk Foreign Card AVS. Bila Pivot menolak, `runCatching` di `InvoiceGenerator`
  mencatat body error tanpa membatalkan ronde → jadi item verifikasi sandbox.
- **Lingkungan** dipilih `ftth.billing.pivot.sandbox` (base `api-stg.pivot-payment.com` vs `api.pivot-payment.com`).

**Callback** (`POST /api/billing/webhooks/{tenantSlug}/pivot`): header **static `X-API-Key`**
(Callback API Key per-tenant, BUKAN HMAC) dibanding **constant-time** dengan `webhook_token`; hanya
`data.status = PAID` jadi settlement. `PaymentSettlement(data.clientReferenceId, data.id, data.amount.value,
data.chargeDetails[0].paidAt ?: now)`.

---

## Mode PLATFORM — auto-provision sub-account

Aksi **platform-admin** membuat sub-account xenPlatform (`MANAGED`) atas nama tenant,
lalu mengunci baris gateway tenant ke `XENDIT / PLATFORM / aktif`.

```
POST /api/billing/platform/gateway/{tenantId}/xendit-subaccount   (izin platform billing.gateway.provision)
  body { email, businessName? }

XenditSubAccountProvisioningService.provisionXendit(...)          ← koordinator
  1. tenantApi.requireById(tenantId)                              (nama bisnis default = nama tenant)
  2. POST /v2/accounts { email, type:"MANAGED",                   ← key MASTER, di LUAR tx/tenant-context
       public_profile:{business_name} }        → user_id
  3. bila callback-base-url diisi:                                ← best-effort, sub-account terlanjur ada
       POST /callback_urls/invoice  (header for-user-id)
         url = <callback-base-url>/api/billing/webhooks/{slug}/xendit
  4. TenantContext.runAs(tenantId) {                              ← simpan DI DALAM RLS tenant sasaran
       persister.persist(...)  →  provisionPlatform(user_id, token)
                               →  repo.save()  +  audit "billing.gateway.provisioned"
     }
```

- **Dua fase sengaja dipisah:** HTTP ke akun MASTER di luar transaksi & tenant-context
  (pakai kredensial platform), lalu penyimpanan di dalam `runAs` lewat bean
  `TenantGatewayProvisionPersister` terpisah agar proxy `@Transactional` benar-benar
  berlaku dan transaksi terbuka **setelah** tenant terpasang (patuh RLS) — pola sama
  dengan `AutoProvisionScheduler`/`AutoProvisioner`.
- Token callback umumnya **tidak** ikut di respons `POST /v2/accounts`; diambil
  best-effort (`callback_token`), else `null` → `resolve()` fallback ke token platform
  global. Balikan API memberi `callbackTokenSet` supaya UI tahu apakah pakai fallback.
- Setelah provisioning, operator tenant **tak boleh** memilih mode PLATFORM secara
  manual tanpa sub-account — `TenantPaymentGateway.update` menolaknya (`ValidationException`).

---

## Paywuz (paywuz.id) — BYO

Adapter `PaywuzPaymentGateway` (BYO). Paywuz hanya butuh **satu** kredensial: **API key** proyek
(`pk_live_…`/`pk_sand_…`) yang jadi Bearer auth **sekaligus** secret HMAC verifikasi webhook —
disimpan di kolom `api_key`, dibawa sebagai `ResolvedGatewayContext.secretKey`, tanpa
`webhook_token` terpisah. Lingkungan (sandbox vs live) ditentukan prefiks key, base URL sama
(`https://api.paywuz.id/v1`).

**Charge** (`POST /v1/transactions`):

```
body { orderId=invoiceNumber, amount (int IDR), paymentMethod, expiryMinutes, metadata }
Authorization: Bearer <api_key>
→ { data: { id, paymentUrl, status } }
  ChargeResult(provider="PAYWUZ", gatewayRef=data.id, payUrl=data.paymentUrl)
```

- **`paymentMethod` WAJIB** — kode metode (mis. meta-method `QRIS`/`VA`), beda dari halaman hosted
  Xendit/Pivot yang membiarkan pelanggan memilih. **Presedensi:** kolom `payment_method` per-tenant
  (bila diisi) → jatuh ke `ftth.billing.paywuz.payment-method` global (default `QRIS`). Nilai per-tenant
  disimpan **plaintext** (bukan rahasia) di kolom `payment_method`, dibawa lewat
  `ResolvedGatewayContext.paymentMethod`.
- **Pilihan metode per-tenant (UI):** halaman setelan menampilkan **dropdown statis meta-method**
  `QRIS` / `VA` ("Virtual Account (Pilih Bank)") + opsi "Default server (QRIS)" — kode disimpan apa
  adanya ke kolom `payment_method`, plus tampilan **URL webhook per-tenant** (read-only, tombol
  salin) yang ditempel operator ke dashboard Paywuz. Endpoint dinamis `GET /api/billing/gateway-settings/paywuz-methods`
  (`billing.gateway.view`, memanggil `GET /v1/payment-methods` proyek tenant lewat port outbound
  `PaywuzMethodDirectory`) **masih tersedia** tapi tak lagi dipakai UI — dipertahankan untuk klien
  yang ingin daftar metode live per proyek.
- **IDR zero-decimal:** `amount` dikirim `setScale(0, HALF_UP)`. `expiryMinutes` dari config (default 1440).

**Callback** (`POST /api/billing/webhooks/{tenantSlug}/paywuz`): header **`X-Paywuz-Signature:
sha256=<hex>`** = HMAC-SHA256(**api_key**, rawBody) dibanding **constant-time**; hanya status
`settlement`/`success` jadi settlement. `PaymentSettlement(orderId, id, amount, timestamp ?: now)`.

---

## Pembayaran manual (transfer / QRIS) — V54

Saat gateway **nonaktif** (atau provider `MANUAL`), tenant "cuma bisa manual" — dan `ManualPaymentGateway`
tak punya tautan bayar. Fitur ini mengisi celah itu: tenant mengatur **instruksi bayar manual** yang
tampil ke pelanggan pada tagihan MANUAL. Dua metode independen, tiap saklar membuka fieldnya:

- **Transfer bank** — `transfer_bank_name`, `transfer_account_number`, `transfer_account_holder`.
- **QRIS** — unggah **gambar QRIS**; byte disimpan di **object storage** (bukan DB), DB hanya simpan
  `qris_storage_key` + `qris_content_type`.

Semua field manual **non-rahasia** → ikuti pola kolom `payment_method` (V51): **plaintext, tak
dienkripsi, semantik "selalu diganti"** (bukan write-only seperti kredensial). Toggle & teks disimpan
lewat `PUT /api/billing/gateway-settings` biasa; hanya byte gambar QRIS yang lewat endpoint multipart
terpisah.

**Object storage QRIS.** Port `ObjectStorage`/`StoredObject` (dulu di modul `workorder`) dipromosikan
ke `com.duluin.ftth.common.storage` (adapter S3/MinIO di `common.infrastructure.storage`) supaya modul
`billing` ikut pakai — bucket & prefix `ftth.storage` sama dengan bukti work-order. Key QRIS satu per
tenant: **`"$tenantId/billing/gateway/qris"`** (unggah ulang menimpa). Validasi: `contentType` harus
`image/*`, maksimal **5 MB**. Pola "taruh byte dulu, baru simpan metadata" meniru `WorkOrderEvidenceService`.

**UI (halaman Payment Gateway).** Saat `!enabled || provider === 'MANUAL'`, provider terkunci ke
MANUAL (tanpa dropdown penyedia) dan seksi **"Pembayaran manual"** muncul. Unggah/hapus gambar QRIS
**ditunda** ke satu alur "Tinjau & simpan" bersama edit transfer (preview lokal ditahan sampai save)
— PUT setelan dulu, baru unggah/hapus byte — supaya edit transfer tak hilang & tak terasa auto-submit.
Preview gambar pakai pola `AuthedImage` (`api.blob` → `createObjectURL`, karena `<img src>` tak bisa
kirim Bearer).

**Tampilan ke pelanggan.** `CustomerDetailPage` memanggil `GET /api/billing/manual-payment-instructions`
untuk merender panel "Cara bayar" pada tagihan MANUAL yang belum lunas: rekening (bila transfer aktif)
+ gambar QRIS (bila QRIS aktif & tersedia).

---

## Konfigurasi (`ftth.billing`)

| Properti / env | Bawaan | Guna |
|---|---|---|
| `webhook-secret` · `FTTH_BILLING_WEBHOOK_SECRET` | *(dev)* | secret verifikasi callback **MANUAL** (fallback global) |
| `platform.enabled` · `FTTH_BILLING_PLATFORM_ENABLED` | `false` | gerbang keras mode PLATFORM (mati = semua tenant PLATFORM dorman) |
| `platform.xendit.secret-key` · `FTTH_BILLING_PLATFORM_XENDIT_SECRET_KEY` | `""` | secret key **MASTER** Xendit (basic-auth charge PLATFORM + buat sub-account) |
| `platform.xendit.webhook-token` · `FTTH_BILLING_PLATFORM_XENDIT_WEBHOOK_TOKEN` | `""` | token callback platform (fallback bila sub-account tak punya token sendiri) |
| `platform.xendit.fee-rule-id` · `FTTH_BILLING_PLATFORM_XENDIT_FEE_RULE_ID` | `""` | fee rule komisi (`with-fee-rule`) — dibuat sekali di dashboard, **tak** diotomasi |
| `platform.xendit.callback-base-url` · `FTTH_BILLING_PLATFORM_XENDIT_CALLBACK_BASE_URL` | `""` | basis URL publik untuk mendaftarkan callback sub-account |
| `pivot.sandbox` · `FTTH_BILLING_PIVOT_SANDBOX` | `false` | Pivot BYO: `true` → base `api-stg`, else `api` produksi |
| `pivot.redirect-base-url` · `FTTH_BILLING_PIVOT_REDIRECT_BASE_URL` | `""` | Pivot BYO: basis URL balik (mode REDIRECT WAJIB); kosong = charge Pivot gagal jelas |
| `paywuz.payment-method` · `FTTH_BILLING_PAYWUZ_PAYMENT_METHOD` | `QRIS` | Paywuz BYO: kode metode **default global** (fallback) — dipakai bila kolom `payment_method` per-tenant kosong |
| `paywuz.expiry-minutes` · `FTTH_BILLING_PAYWUZ_EXPIRY_MINUTES` | `1440` | Paywuz BYO: masa hidup tautan bayar (menit) |

> **Config lawas `default-provider`** kini **usang** — resolver memilih adapter dari
> baris config tenant (sumber kebenaran), dan fallback MANUAL sudah hardcoded. Dibiarkan
> di `BillingProperties`/`PaymentGatewayRegistry.default()` tapi tak lagi di jalur charge.

### Cakupan API key MASTER Xendit (mode PLATFORM)

Saat membuat secret key master di dashboard Xendit, cukup beri izin:
**Money-in products = Write** (buat invoice), **xenPlatform → Account = Write**
(buat sub-account), **xenPlatform → Split Payments = Write** (fee rule). Sisanya `None`.
Key master **hanya** di env server (`FTTH_BILLING_PLATFORM_XENDIT_SECRET_KEY`) — jangan
di DB/klien/git; rotate bila bocor.

---

## API

| Endpoint | Izin |
|---|---|
| `GET /api/billing/gateway-settings` | `billing.gateway.view` |
| `PUT /api/billing/gateway-settings` | `billing.gateway.manage` |
| `POST /api/billing/gateway-settings/qris` (multipart `file`) | `billing.gateway.manage` |
| `DELETE /api/billing/gateway-settings/qris` | `billing.gateway.manage` |
| `GET /api/billing/gateway-settings/qris` (byte gambar) | `billing.gateway.view` / `billing.invoice.view` |
| `GET /api/billing/gateway-settings/paywuz-methods` | `billing.gateway.view` (tak dipakai UI baru) |
| `GET /api/billing/manual-payment-instructions` | `billing.invoice.view` |
| `POST /api/billing/platform/gateway/{tenantId}/xendit-subaccount` | `billing.gateway.provision` (platform-only) |
| `POST /api/billing/webhooks/{tenantSlug}/{provider}` | publik (tanda tangan gateway) |

`billing.gateway.view`/`manage` masuk role Tenant Admin otomatis; `billing.gateway.provision`
platform-only (tak tenant-assignable). Web: halaman **Payment Gateway** (setelan tenant,
guard `billing.gateway.view`) + tombol **"Provisi Xendit"** per tenant di halaman platform-admin.

---

## Keamanan

- **Webhook publik.** `/api/billing/webhooks/**` di-`permitAll`; endpoint setelan &
  provisioning tetap butuh bearer + `@PreAuthorize`. Keaslian callback dijamin
  `x-callback-token` (Xendit) / `X-Billing-Signature` (MANUAL) dibanding **constant-time**.
- **RLS dua-lapis** pada `tenant_payment_gateway`: tenant B tak pernah lihat/timpa baris
  tenant A; token B tak bisa melunasi invoice A.
- Secret **write-only**, terenkripsi di batas persistence; `sub_account_id` aman ditampilkan.

---

## Butuh verifikasi live Xendit (bukan blocker koding — sudah dibela defensif)

1. **Token webhook sub-account MANAGED.** Rencana pakai token per-sub-account (disimpan
   saat provisioning). Konfirmasi di sandbox apakah `x-callback-token` webhook membawa
   token sub-account atau token platform teragregasi — kalau teragregasi, `resolve()`
   sudah fallback ke `platform.webhook-token`.
2. **Field token respons `POST /v2/accounts`.** Diasumsikan mungkin `callback_token`
   (best-effort parse); bila absen → `null` + fallback token platform global.

---

## Checklist naikkan gateway

**BYO (tenant, tanpa env apa pun):** login admin tenant → halaman **Payment Gateway** →
provider `XENDIT`, mode `BYO`, isi secret key + webhook token (dari dashboard Xendit
tenant), aktifkan. Daftarkan callback Xendit ke `/api/billing/webhooks/{slug}/xendit`.

**PLATFORM (platform-admin):** isi env `FTTH_BILLING_PLATFORM_*` (enabled + master secret
+ fee-rule-id + callback-base-url) di VPS → restart server → tombol **"Provisi Xendit"**
per tenant → sub-account dibuat & baris gateway tenant terkunci ke PLATFORM otomatis.
