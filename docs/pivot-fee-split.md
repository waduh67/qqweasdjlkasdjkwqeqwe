# Fee platform Pivot — split routing

Bagian dari [`pivot-overview.md`](pivot-overview.md). Menjelaskan bagaimana platform
memungut **fee per transaksi** dari tagihan pelanggan tenant lewat fitur **Split Routing**
Pivot, tanpa memungut ke pelanggan: pelanggan membayar nominal tagihan apa adanya, fee
dipotong dari **hasil yang diterima tenant**.

---

## Kapan fee dipungut

Fee **hanya** dipungut pada charge **on-behalf sub-account tenant** (tagihan pelanggan).
Charge langganan SaaS (tanpa sub-account) **tidak** kena split — 100% memang milik platform.

| Charge | `x-submerchant-id` | Split routing | Hasil |
|---|---|---|---|
| Tagihan pelanggan tenant | sub-account tenant | **ya** (fee → master) | pelanggan bayar penuh; tenant terima sisa; platform terima fee |
| Langganan SaaS tenant | — (null) | **tidak** | 100% ke platform (memang pemasukan platform) |

---

## Konfigurasi fee (di `pivot_master_config`)

Fee di-set super-admin di setelan master ([`pivot-overview.md`](pivot-overview.md)):

| Kolom | Arti |
|---|---|
| `platform_fee_minor` | besaran fee. `0` = **tanpa** split routing (fitur mati) |
| `platform_fee_type` | `FIXED` = nominal tetap (minor-unit IDR, mis. `1000` = Rp1.000/transaksi) · `PERCENTAGE` = persen dari nominal (basis 100, mis. `2` = 2%; maksimal 100) |

Nilai ini mengalir lewat `TenantPaymentGatewayResolver` ke `ResolvedGatewayContext`
(`platformFeeMinor` + `platformFeeType`) saat charge tenant.

---

## Cara kerja di adapter (`PivotPaymentGateway.splitRouting`)

Saat `createCharge`, adapter menambah `splitRoutingConfigurations` ke body `POST /v2/payments`
**hanya** bila:

1. `subAccountId` **tidak** null/blank (ini charge on-behalf, bukan SaaS), **dan**
2. `apiKey` (merchant id master) ada — tujuan potongan, **dan**
3. fee terhitung `> 0` **dan** `< nominal tagihan` (tak boleh menelan seluruh tagihan).

```kotlin
val feeValue = when (platformFeeType) {
    FIXED      -> platformFeeMinor
    PERCENTAGE -> amountValue * platformFeeMinor / 100      // dari nominal charge saat ini
}
if (feeValue <= 0 || feeValue >= amountValue) return null   // skip split

splitRoutingConfigurations = [{
    merchantId  = <merchant id MASTER>,     // fee dialihkan ke akun master
    type        = "FIXED",                  // selalu dikirim FIXED (PERCENTAGE dihitung dulu ke nominal)
    currency    = "IDR",
    fixedAmount = feeValue,
    remarks     = "Platform fee",
}]
```

Catatan penting:

- **PERCENTAGE dikonversi ke nominal** saat charge (dari `amount.value` saat itu), lalu
  tetap dikirim ke Pivot sebagai `type: FIXED`. Jadi di sisi Pivot selalu berupa potongan
  nominal tetap.
- Fee ini **di luar** biaya Pivot sendiri (biaya gateway Pivot ditanggung sesuai skema akun
  master).
- Nominal charge memakai **IDR zero-decimal** — `amount.value` dibulatkan ke bilangan bulat
  (`setScale(0, HALF_UP)`); nilai tagihan di DB tetap scale-2.

---

## Contoh perhitungan

Anggap tagihan pelanggan **Rp150.000**.

| `platform_fee_type` | `platform_fee_minor` | Fee terpotong | Diterima tenant | Diterima platform |
|---|---|---|---|---|
| `FIXED` | `2500` | Rp2.500 | Rp147.500 | Rp2.500 |
| `PERCENTAGE` | `2` (2%) | Rp3.000 (`150000 × 2 / 100`) | Rp147.000 | Rp3.000 |
| `FIXED` | `0` | — (split di-skip) | Rp150.000 | Rp0 |

Pelanggan **selalu** membayar Rp150.000; fee mengurangi bagian tenant, bukan menambah
beban pelanggan.

---

## Kapan split di-skip

Split routing **tidak** dikirim (charge tanpa fee) bila salah satu terpenuhi:

- `subAccountId` null → charge **langganan SaaS** (bukan on-behalf tenant).
- `platform_fee_minor = 0` → fee dimatikan platform.
- fee terhitung `≤ 0`.
- fee terhitung **≥ nominal tagihan** (defensif — fee tak boleh menelan seluruh nominal;
  misal PERCENTAGE keliru besar atau tagihan sangat kecil).

Dalam kasus-kasus ini charge tetap berjalan normal, hanya tanpa potongan platform.
</content>
