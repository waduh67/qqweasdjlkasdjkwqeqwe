# Payment gateway multi-tenant (Xendit BYO + PLATFORM)

Bagian dari modul [`billing`](billing.md). Menggantikan "satu gateway global +
satu webhook secret bersama" dengan **satu baris konfigurasi gateway per tenant**:
tiap tenant memilih penyedia, mode, dan kredensialnya sendiri.

Dua mode:

| Mode | Siapa punya akun | Kredensial | Uang mendarat di | Komisi platform |
|---|---|---|---|---|
| **BYO** | tenant | akun gateway tenant sendiri (secret di baris tenant, terenkripsi) | rekening tenant langsung | — |
| **PLATFORM** | platform | akun **MASTER** platform (config/env) + **sub-account** xenPlatform per tenant | balance sub-account tenant di platform | via `fee_rule` (header `with-fee-rule`) |

Xendit digarap penuh (BYO **dan** PLATFORM). **Paywuz/Pivot** baru kerangka —
bisa dipilih & dikonfigurasi, tapi `createCharge` melempar sampai dokumentasi API-nya
tersedia (drop-in nanti tanpa ubah skema).

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
└── sub_account_id  PLATFORM: user_id sub-account Xendit (header for-user-id)
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
| BYO · PAYWUZ/PIVOT | selalu (adapter melempar) | `api_key` | token tenant |
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

## Paywuz / Pivot (kerangka)

`PaywuzPaymentGateway` / `PivotPaymentGateway`: `provider="PAYWUZ"/"PIVOT"`. `createCharge`
melempar `UnsupportedOperationException("Gateway <X> belum didukung — dokumentasi API
belum tersedia")`, `parseCallback` log-warn + `null`. Enum & CHECK `ck_tpg_provider`
sudah memuat keduanya sejak V50 → impl asli tinggal drop-in tanpa migrasi.

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
