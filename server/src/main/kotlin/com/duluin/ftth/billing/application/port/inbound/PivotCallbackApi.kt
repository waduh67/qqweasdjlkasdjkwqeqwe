package com.duluin.ftth.billing.application.port.inbound

import org.springframework.modulith.NamedInterface

/**
 * Otoritas tunggal callback Pivot sisi billing. Pivot mendaftarkan SATU URL per produk di akun
 * MASTER (bukan per-tenant), jadi seluruh callback masuk lewat endpoint platform-level dan tenant
 * di-resolve dari payload, bukan dari slug di path.
 *
 * Semua method memverifikasi header `X-API-Key` = Callback API Key master (constant-time) lebih dulu
 * dan melempar [com.duluin.ftth.common.domain.error.ValidationException] bila tak cocok — pemanggil
 * membalas 4xx tanpa efek. Rekonsiliasi bersifat idempotent (callback ganda aman).
 *
 * Bagian named interface `gateway` — di-expose agar controller callback di `platformbilling`
 * (yang juga menyetel pelunasan langganan SaaS) mengorkestrasi tanpa menembus enkapsulasi billing.
 */
@NamedInterface("gateway")
interface PivotCallbackApi {

    /**
     * Callback produk PAYMENT. Memilah via `metadata.scope`:
     *  - `TENANT` → resolve tenant dari `metadata.tenantSlug` lalu terapkan pelunasan tagihan
     *    pelanggan (dalam `TenantContext.runAs`); balikkan `true`.
     *  - selain itu (`SAAS`/tanpa metadata) → `false`; pemanggil (platform) yang menyetel pelunasan
     *    langganan SaaS.
     */
    fun handlePayment(headers: Map<String, String>, body: String): Boolean

    /**
     * Callback produk PAYOUT / WITHDRAWAL / INTERNATIONAL_PAYOUT. Cari baris `tenant_payout` lintas
     * tenant via referensi Pivot, lalu rekonsiliasi status di tenant pemiliknya. `true` bila baris
     * ditemukan & diperbarui; `false` bila referensi tak dikenal / status belum final (diabaikan).
     */
    fun handleDisbursement(headers: Map<String, String>, body: String): Boolean

    /**
     * Callback produk SUB_ACCOUNT_REGISTRATION. Perbarui status & KYC `tenant_pivot_account` tenant
     * pemilik `sub_merchant_uuid`. `true` bila sub-account dikenal & diperbarui.
     */
    fun handleSubAccountRegistration(headers: Map<String, String>, body: String): Boolean

    /**
     * Callback produk REFUND. Belum ada domain refund — untuk saat ini diverifikasi & dicatat ke log
     * (di-ACK agar Pivot berhenti retry). Selalu `true` bila tanda tangan sah. TODO: proses balik.
     */
    fun handleRefund(headers: Map<String, String>, body: String): Boolean

    /**
     * Verifikasi tanda tangan callback saja (produk wallet yang tak diproses billing). Melempar bila
     * `X-API-Key` tak cocok; jika lolos, pemanggil cukup meng-ACK 200 (no-op).
     */
    fun verifySignature(headers: Map<String, String>)
}
