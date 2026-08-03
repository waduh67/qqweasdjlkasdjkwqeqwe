package com.duluin.ftth.billing.application.port.inbound

import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.common.storage.StoredObject

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

    /** Simpan/ganti gambar QRIS pembayaran manual (byte ke object storage). */
    fun uploadQrisImage(contentType: String, bytes: ByteArray): PaymentGatewaySettingsView

    /** Lepas gambar QRIS (hapus dari storage). */
    fun deleteQrisImage(): PaymentGatewaySettingsView

    /** Ambil byte gambar QRIS untuk disajikan; null bila belum ada. */
    fun getQrisImage(): StoredObject?

    /** Instruksi bayar manual ringkas untuk pelanggan (dipakai halaman detail pelanggan). */
    fun manualPaymentInstructions(): ManualPaymentInstructionsView
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
    // Pembayaran manual (transfer/QRIS) — non-rahasia, ditampilkan apa adanya.
    val manualTransferEnabled: Boolean,
    val bankName: String?,
    val accountNumber: String?,
    val accountHolder: String?,
    val manualQrisEnabled: Boolean,
    val qrisImageSet: Boolean,
)

/**
 * Instruksi bayar manual yang ditunjukkan ke pelanggan (halaman detail pelanggan) untuk
 * tagihan MANUAL. Ringkas & non-rahasia; gambar QRIS diambil lewat endpoint konten terpisah
 * (hanya penanda [qrisImageAvailable] di sini).
 */
data class ManualPaymentInstructionsView(
    val transferEnabled: Boolean,
    val bankName: String?,
    val accountNumber: String?,
    val accountHolder: String?,
    val qrisEnabled: Boolean,
    val qrisImageAvailable: Boolean,
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
    // Pembayaran manual (non-rahasia) — selalu diganti (null/kosong = kosongkan).
    val manualTransferEnabled: Boolean,
    val bankName: String?,
    val accountNumber: String?,
    val accountHolder: String?,
    val manualQrisEnabled: Boolean,
)
