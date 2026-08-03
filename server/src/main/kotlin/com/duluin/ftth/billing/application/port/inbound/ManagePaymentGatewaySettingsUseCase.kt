package com.duluin.ftth.billing.application.port.inbound

import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.PaymentProvider

/**
 * Sisi operator dari setelan payment gateway tenant: baca setelan (atau bawaan MANUAL/mati
 * bila belum pernah disetel) dan ubah penyedia + mode + kredensial.
 */
interface ManagePaymentGatewaySettingsUseCase {
    fun get(): PaymentGatewaySettingsView
    fun update(command: UpdatePaymentGatewaySettingsCommand): PaymentGatewaySettingsView

    /**
     * Daftar metode pembayaran aktif proyek Paywuz tenant (untuk mengisi pilihan metode di UI).
     * Kosong bila tenant bukan Paywuz; melempar bila API key belum tersimpan atau Paywuz menolak.
     */
    fun listPaywuzMethods(): List<PaywuzMethodView>
}

/**
 * Setelan gateway untuk ditampilkan. Kredensial TAK pernah dikembalikan — hanya penanda
 * apakah sudah terisi ([apiKeySet]/[secretKeySet]/[webhookTokenSet]) agar rahasia tak bocor
 * ke UI. [subAccountId] & [paymentMethod] aman ditampilkan (bukan rahasia).
 */
data class PaymentGatewaySettingsView(
    val provider: String,
    val mode: String,
    val enabled: Boolean,
    val apiKeySet: Boolean,
    val secretKeySet: Boolean,
    val webhookTokenSet: Boolean,
    val subAccountId: String?,
    val paymentMethod: String?,
)

/** Satu metode pembayaran Paywuz untuk pilihan di UI. */
data class PaywuzMethodView(
    val code: String,
    val name: String,
    val type: String,
)

/**
 * Perubahan setelan. Kredensial ([apiKey]/[secretKey]/[webhookToken]) null/kosong = biarkan
 * apa adanya (tak menimpa yang tersimpan), agar sunting field lain tak menghapus rahasia.
 * [paymentMethod] BUKAN rahasia → null/kosong = kosongkan (jatuh ke default global). [subAccountId]
 * tak disertakan — ia hasil provisioning platform-admin, bukan input operator.
 */
data class UpdatePaymentGatewaySettingsCommand(
    val provider: PaymentProvider,
    val mode: GatewayMode,
    val enabled: Boolean,
    val apiKey: String?,
    val secretKey: String?,
    val webhookToken: String?,
    val paymentMethod: String?,
)
