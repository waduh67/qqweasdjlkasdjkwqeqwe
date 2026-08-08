# Pivot sub-account tenant — provisioning, KYC, rekening payout

Bagian dari [`pivot-overview.md`](pivot-overview.md). Menjelaskan **sub-account Pivot**
milik tiap tenant: baris `tenant_pivot_account` (V71), cara diprovisikan, beda NON_KYC vs
KYC, siklus-hidup status, rekening payout + validasi inquiry, serta endpoint
`/api/billing/pivot-account`.

Sub-account **menggantikan** kredensial gateway BYO lama: tenant tak lagi memasang akun
sendiri — platform membuatkan sub-account di akun master, dan seluruh charge pelanggan
tenant dibuat **on-behalf-of** `sub_merchant_uuid` (header `x-submerchant-id`).

---

## Model data — `tenant_pivot_account` (V71)

Satu baris per tenant (`UNIQUE (tenant_id)`), **tenant-scoped + RLS dua-lapis** (sama pola
tabel bisnis lain). Semua kolom **non-rahasia** (uuid sub-account, status, rekening payout
bukan kredensial) → plaintext.

```
tenant_pivot_account  (satu baris per tenant, RLS)
├── sub_merchant_uuid      UUID sub-account di Pivot (x-submerchant-id). NULL = belum diprovisikan
├── account_type           NON_KYC | KYC                                          (CHECK)
├── sub_account_status     NOT_PROVISIONED | CREATED | ACTIVE | DEACTIVATED | REJECTED (CHECK)
├── kyc_status             NOT_REQUIRED | WAITING_FOR_DOCUMENT | IN_REVIEW | APPROVED | REJECTED (CHECK)
├── short_name             transaction descriptor (nama singkat di mutasi pelanggan)
│
│   Rekening payout tenant + hasil validasi inquiry:
├── payout_channel_code    channel bank (mis. BCA)
├── payout_account_number  nomor rekening
├── payout_account_name    nama pemilik — DIKETIK tenant, dikirim ke POST /v1/inquiry-account
└── payout_inquiry_id      data.uuid hasil validasi — dipakai POST /v1/payouts
```

Penanda turunan di domain (`TenantPivotAccount`):

- `provisioned` = `sub_merchant_uuid` terisi.
- `payoutReady` = `payout_inquiry_id` terisi (rekening sudah divalidasi).

---

## NON_KYC vs KYC

| Aspek | **NON_KYC** (default onboarding) | **KYC** (tenant verifikasi sendiri) |
|---|---|---|
| Transaksi atas nama | platform FTTH | tenant sendiri |
| Dana pelanggan mendarat di | balance **master** platform | balance **sub-account** tenant |
| Cara tenant dapat dana | **payout** oleh platform ke rekening tenant (`POST /v1/payouts`) | **withdrawal** oleh tenant sendiri (`POST /v1/withdrawals` on-behalf) |
| Verifikasi dokumen | tidak perlu | dokumen dikirim **out-of-band** ke `verification@pivot-payment.com` (di luar aplikasi) untuk approval Pivot |
| Dibuat kapan | otomatis saat onboarding | saat tenant menekan "Ajukan KYC" |

Penyaluran dana kedua tipe dijelaskan di [`pivot-payout.md`](pivot-payout.md).

---

## Provisioning: otomatis vs manual

### Otomatis saat onboarding

`TenantPivotAccountProvisioningListener` mendengar `iam.TenantOnboardedEvent`:

```
iam.TenantOnboardingService  ──publish──▶ TenantOnboardedEvent(tenantId, …)
                                              │  @TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)
TenantPivotAccountProvisioningListener  ◀─────┘
   └─ TenantContext.runAs(tenantId) {                     ← RLS & @TenantId benar
        provisioner.ensureForTenant(tenantId)
          ├─ master = PivotMasterConfigProvider.current() ?: (lewati bila Pivot belum aktif)
          ├─ POST /v1/sub-merchants { subAccountType:NON_KYC, shortName, businessName } → uuid
          └─ markProvisioned(uuid, NON_KYC, status, kycStatus) + save + audit
      }
```

- **Dipisah dari iam lewat event** untuk memutus siklus modul. Terpisah pula dari
  `platformbilling.TenantOnboardedListener` (yang mengurus langganan SaaS): satu event, dua
  consumer independen.
- Berjalan **AFTER_COMMIT** dalam `TenantContext.runAs` (sub-account tenant-scoped) —
  meniru pola listener onboarding lain.
- **Idempotent**: bila sudah `provisioned`, dilewati. **Kegagalan Pivot TIDAK menggagalkan
  onboarding** — bisa diprovisi ulang manual.
- Bila master Pivot **belum aktif**, provisioning dilewati diam-diam (tenant tetap MANUAL
  sampai Pivot dinyalakan).

### Manual (tenant lama pra-fitur)

`POST /api/billing/pivot-account/provision` (`billing.gateway.manage`) memanggil
`provision()` — sama-sama membuat NON_KYC bila belum ada, idempotent.

**Descriptor** (`shortName`) diturunkan dari nama tenant: huruf/angka/spasi, ringkas ≤20
char, fallback `FTTH`.

---

## Siklus-hidup status

`sub_account_status` dipetakan dari `subAccountStatus` Pivot (`PivotSubMerchantGateway`);
nilai tak dikenal → `CREATED`. `kyc_status` dari `subAccountKycStatus`; tak dikenal →
`NOT_REQUIRED`.

```
SubAccountStatus:  NOT_PROVISIONED ──create──▶ CREATED ──aktivasi Pivot──▶ ACTIVE
                                                   └──────────────────────▶ DEACTIVATED / REJECTED

SubAccountKycStatus: NOT_REQUIRED
   requestKyc() ─▶ WAITING_FOR_DOCUMENT ──dokumen diproses──▶ IN_REVIEW ──▶ APPROVED / REJECTED
```

- **Charge boleh** selama status **bukan** `DEACTIVATED`/`REJECTED`
  (`usableForCharge()` di `TenantPaymentGatewayResolver`). Bila sub-account belum siap,
  resolver jatuh ke **MANUAL** — pelanggan tetap dapat instruksi bayar, bukan charge yang
  pasti gagal.
- `refreshStatus()` (`POST /api/billing/pivot-account/refresh`) menarik status terbaru via
  `GET /v1/sub-merchants/{uuid}` dan menerapkannya (`applyStatus`).
- `requestKyc()` membuat sub-account **baru** bertipe KYC (`POST /v1/sub-merchants`
  `subAccountType=KYC`), set `type=KYC` + `kycStatus=WAITING_FOR_DOCUMENT`; approval
  menyusul out-of-band.

---

## Rekening payout + inquiry

`setPayoutAccount()` (`POST /api/billing/pivot-account/payout-account`) menyimpan rekening
tujuan penyaluran dana tenant, **divalidasi lebih dulu** ke Pivot:

```
POST /v1/inquiry-account            header: x-submerchant-id: <uuid sub-account>
{ "channelCode": "BCA",
  "channelInformation": { "accountNumber": "…", "accountName": "…" } }
   → { "data": { "uuid": "<inquiryId>", "inquiryResult": { "status": …, "detail": … } } }
      └─ setPayoutAccount(channelCode, accountNumber, accountName, uuid)
```

- **Body harus BERSARANG.** Bentuk pipih (`accountNumber` di akar) ditolak `400 field_required`
  dengan pesan berlubang `"Make sure  value is fulfilled"` — perhatikan spasi gandanya, nama
  fieldnya kosong. Ini pernah membuat SEMUA payout gagal.
- **Nama pemilik DIINPUT tenant** (maks 60 karakter), bukan hasil inquiry — Pivot tak pernah
  mengembalikan nama pemilik sebagai field. Yang dikembalikan `inquiryResult.status`:

  | status | arti | perlakuan kita |
  | --- | --- | --- |
  | `VALID` | nomor & nama cocok | diteruskan |
  | `WARNING` | nomor ada, nama beda — `detail` memuat nama versi bank | **ditahan** (`ConflictException`), `detail` diteruskan ke UI |
  | `INVALID` | rekening tak ditemukan | ditahan |
  | `PENDING` | masih diproses (juga fallback status tak dikenal) | ditahan |

- **Wajib on-behalf `x-submerchant-id`** — biaya inquiry dibebankan ke saldo pemanggil, jadi
  inquiry atas nama master ditolak `400 balance_insufficient`. Artinya rekening baru bisa
  divalidasi **setelah** sub-account ada; sebelum itu profil hanya menyimpannya lokal.
- Inquiry **idempoten** per (channelCode, accountNumber): panggilan berulang mengembalikan `uuid`
  yang sama.
- `payout_inquiry_id` inilah yang dibawa `POST /v1/payouts` saat payout NON_KYC (lihat
  [`pivot-payout.md`](pivot-payout.md)). Tanpa inquiry tervalidasi (`payoutReady=false`),
  payout ditolak.

---

## Endpoint `/api/billing/pivot-account`

| Endpoint | Izin | Guna |
|---|---|---|
| `GET /api/billing/pivot-account` | `billing.gateway.view` | status sub-account tenant (+ `masterActive`) |
| `POST /api/billing/pivot-account/provision` | `billing.gateway.manage` | provisi NON_KYC bila belum ada (tenant lama) |
| `POST /api/billing/pivot-account/refresh` | `billing.gateway.view` | tarik status terbaru dari Pivot |
| `POST /api/billing/pivot-account/request-kyc` | `billing.gateway.manage` | ajukan upgrade KYC (sub-account atas nama tenant) |
| `POST /api/billing/pivot-account/payout-account` | `billing.gateway.manage` | set rekening payout (divalidasi via inquiry) |

Endpoint saldo & penyaluran (`/balance`, `/payouts`, `/withdrawals`) ada di controller yang
sama basis path-nya — dijelaskan di [`pivot-payout.md`](pivot-payout.md). Semua operasi
membutuhkan master Pivot aktif (`PivotMasterConfigProvider.current() != null`); bila belum,
`ConflictException` jelas.

> **Catatan.** Setelan **metode aktif** (PIVOT/MANUAL) + **pembayaran manual** (transfer/
> QRIS) tetap di `/api/billing/gateway-settings` (lihat [`payment-gateway.md`](payment-gateway.md)),
> terpisah dari manajemen sub-account di sini.
</content>
