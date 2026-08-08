package com.duluin.ftth.platformbilling.application.port.inbound

import com.duluin.ftth.billing.application.port.outbound.SimulatedChargeStatus

/**
 * Alat uji super-admin: memaksa sebuah sesi bayar penyedia menjadi lunas/kedaluwarsa TANPA transaksi
 * bank/e-wallet sungguhan, agar seluruh alur "bayar → webhook → lunas" bisa dicoba di sandbox.
 *
 * Berbeda dari simulasi per-tagihan (tagihan pelanggan / langganan tenant), di sini id sesi bayar
 * diketik langsung — jadi sesi apa pun di akun master bisa diuji, termasuk yang tak terhubung ke
 * tagihan di aplikasi ini. Dijaga izin `platform.billing.manage`.
 */
interface SimulatePaymentUseCase {
    /**
     * Kirim simulasi untuk [command]. Melempar bila Pivot master belum dikonfigurasi, bukan mode
     * sandbox, atau penyedia menolak id sesi. Efeknya asinkron: pelunasan menyusul lewat webhook.
     */
    fun simulate(command: SimulatePaymentCommand): SimulatePaymentResult

    /** Apakah simulasi tersedia sekarang (Pivot master aktif & mode sandbox) — untuk gating UI. */
    fun availability(): SimulationAvailability
}

/**
 * [paymentSessionId] = id sesi bayar dari respons create payment Pivot (`data.id`), yang juga
 * tersimpan sebagai `gatewayRef` pada tagihan. [subMerchantId] opsional: isi bila sesi dibuat
 * atas nama sub-account tenant (charge tagihan pelanggan), kosongkan untuk sesi langganan SaaS
 * yang dibuat langsung di akun master.
 */
data class SimulatePaymentCommand(
    val paymentSessionId: String,
    val status: SimulatedChargeStatus,
    val subMerchantId: String? = null,
)

/** Konfirmasi simulasi terkirim; pelunasan sebenarnya menyusul lewat callback penyedia. */
data class SimulatePaymentResult(
    val paymentSessionId: String,
    val status: SimulatedChargeStatus,
    val provider: String,
)

/**
 * [available] = simulasi boleh dijalankan. [sandbox] = Pivot master mode sandbox; [configured] =
 * kredensial master lengkap & aktif. [reason] menjelaskan penyebab bila tak tersedia (untuk UI).
 */
data class SimulationAvailability(
    val available: Boolean,
    val configured: Boolean,
    val sandbox: Boolean,
    val reason: String?,
)
