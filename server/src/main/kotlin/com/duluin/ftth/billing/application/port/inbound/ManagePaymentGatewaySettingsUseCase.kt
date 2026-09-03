package com.duluin.ftth.billing.application.port.inbound

import com.duluin.ftth.billing.domain.model.PaymentProvider
import com.duluin.ftth.common.storage.StoredObject

/**
 * Sisi operator dari setelan penagihan tenant: pilih metode aktif (PIVOT otomatis / MANUAL) dan
 * atur pembayaran manual (transfer/QRIS). TIDAK ada kredensial di sini — Pivot memakai akun master
 * platform + sub-account tenant (kelola sub-account lewat [ManageTenantPivotAccountUseCase]).
 */
interface ManagePaymentGatewaySettingsUseCase {
    fun get(): PaymentGatewaySettingsView
    fun update(command: UpdatePaymentGatewaySettingsCommand): PaymentGatewaySettingsView

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
 * Setelan penagihan untuk ditampilkan. Rahasia Tripay sengaja tidak pernah keluar dari server;
 * hanya penanda apakah key sudah tersimpan yang dikembalikan.
 */
data class PaymentGatewaySettingsView(
    val provider: String,
    val enabled: Boolean,
    // Pembayaran manual (transfer/QRIS) — non-rahasia, ditampilkan apa adanya.
    val manualTransferEnabled: Boolean,
    val bankName: String?,
    val accountNumber: String?,
    val accountHolder: String?,
    val manualQrisEnabled: Boolean,
    val qrisImageSet: Boolean,
    val tripayMerchantCode: String?,
    val tripayApiKeySet: Boolean,
    val tripayPrivateKeySet: Boolean,
    val tripaySandbox: Boolean,
)

/**
 * Instruksi bayar manual yang ditunjukkan ke pelanggan (halaman detail pelanggan) untuk tagihan
 * MANUAL. Ringkas & non-rahasia; gambar QRIS diambil lewat endpoint konten terpisah (hanya penanda
 * [qrisImageAvailable] di sini).
 */
data class ManualPaymentInstructionsView(
    val transferEnabled: Boolean,
    val bankName: String?,
    val accountNumber: String?,
    val accountHolder: String?,
    val qrisEnabled: Boolean,
    val qrisImageAvailable: Boolean,
)

/**
 * Perubahan setelan. Konfigurasi manual selalu diganti. Key Tripay bersifat write-only: null atau
 * kosong mempertahankan key yang sudah tersimpan, sedangkan nilai baru menggantikannya.
 */
data class UpdatePaymentGatewaySettingsCommand(
    val provider: PaymentProvider,
    val enabled: Boolean,
    val manualTransferEnabled: Boolean,
    val bankName: String?,
    val accountNumber: String?,
    val accountHolder: String?,
    val manualQrisEnabled: Boolean,
    val tripayMerchantCode: String?,
    val tripayApiKey: String?,
    val tripayPrivateKey: String?,
    val tripaySandbox: Boolean,
)
