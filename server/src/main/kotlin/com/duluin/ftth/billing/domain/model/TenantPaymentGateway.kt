package com.duluin.ftth.billing.domain.model

import com.duluin.ftth.common.domain.UuidV7
import org.springframework.modulith.NamedInterface
import java.util.UUID

/**
 * Metode penagihan aktif tenant untuk pelanggannya:
 *  - [PIVOT]  otomatis lewat sub-account Pivot tenant (charge on-behalf + split fee platform).
 *             Butuh sub-account terprovisi ([TenantPivotAccount]); bila belum siap, resolver
 *             jatuh ke [MANUAL].
 *  - [TRIPAY] akun Tripay milik tenant sendiri (BYOK).
 *  - [MANUAL] pembayaran luar-band (tunai/transfer/QRIS) + webhook bersecret bersama. Juga
 *             fallback saat tenant belum/nonaktif memakai Pivot.
 */
enum class PaymentProvider { PIVOT, TRIPAY, MANUAL }

/**
 * Penanda asal charge otomatis:
 *  - [BYO]      TRIPAY atau MANUAL / fallback — bukan charge akun platform.
 *  - [PLATFORM] transaksi Pivot berjalan di akun MASTER platform ([ResolvedGatewayContext.apiKey]),
 *               untuk pelanggan tenant via `x-submerchant-id` ([ResolvedGatewayContext.subAccountId]).
 *
 * Bagian named interface `gateway` — dipakai `platformbilling` (langganan SaaS di akun master).
 */
@NamedInterface("gateway")
enum class GatewayMode { BYO, PLATFORM }

/**
 * Kredensial gateway yang SUDAH teresolusi & terdekripsi — bentuk datar siap-pakai yang dipakai
 * adapter untuk membuat charge / memverifikasi callback. Untuk model Pivot "business as platform",
 * kredensial memakai akun master ([apiKey]/[secretKey]); aksi atas nama tenant lewat [subAccountId].
 * Untuk Tripay BYOK, [apiKey] dan [secretKey] adalah credential tenant sendiri.
 *
 * [provider] String (bukan enum) agar registry bisa memilih adapter apa pun — termasuk `MANUAL`.
 *
 * Bagian named interface `gateway` — `platformbilling` menyusun konteks ini untuk menagih langganan
 * lewat akun master yang sama (tanpa [subAccountId] & tanpa fee split → 100% ke platform).
 */
@NamedInterface("gateway")
data class ResolvedGatewayContext(
    val provider: String,
    val mode: GatewayMode,
    val secretKey: String?,
    /** Token verifikasi callback. PIVOT: Callback API Key master. MANUAL: shared secret global. */
    val webhookToken: String?,
    /** PIVOT customer charge: uuid sub-account tenant (`x-submerchant-id`). Null = charge di master (SaaS). */
    val subAccountId: String? = null,
    /**
     * PIVOT customer charge: slug tenant pemilik charge, disematkan ke `metadata.tenantSlug` agar
     * callback pembayaran (satu URL master) bisa memilah customer vs SaaS & me-resolve tenant O(1).
     * Null untuk charge SaaS/MANUAL.
     */
    val tenantSlug: String? = null,
    val apiKey: String? = null,
    /** TRIPAY: merchant code tenant untuk signature create-transaction. */
    val merchantCode: String? = null,
    val sandbox: Boolean = false,
    /** PIVOT: fee platform per transaksi (minor unit IDR). 0 = tanpa split-routing. */
    val platformFeeMinor: Long = 0,
    /** PIVOT: tipe fee platform. */
    val platformFeeType: PivotFeeType = PivotFeeType.FIXED,
)

/**
 * Konfigurasi pembayaran MANUAL per-tenant (transfer / QRIS) — dipakai saat Pivot nonaktif /
 * sub-account belum siap: inilah satu-satunya instruksi bayar yang bisa ditunjukkan ke pelanggan.
 * Semua NON-RAHASIA (bukan kredensial), plaintext & semantik "selalu diganti" (null/kosong =
 * kosongkan).
 *
 * Gambar QRIS byte-nya TIDAK di sini (ada di object storage); [qrisEnabled] hanya penanda aktif,
 * gambarnya dikelola lewat [TenantPaymentGateway.attachQrisImage]/[clearQrisImage].
 */
data class ManualPaymentConfig(
    val transferEnabled: Boolean = false,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val accountHolder: String? = null,
    val qrisEnabled: Boolean = false,
) {
    /** Rapikan input operator: kosong/whitespace → null (setara "tak diisi"). */
    fun normalized(): ManualPaymentConfig = copy(
        bankName = bankName?.trim()?.takeIf { it.isNotEmpty() },
        accountNumber = accountNumber?.trim()?.takeIf { it.isNotEmpty() },
        accountHolder = accountHolder?.trim()?.takeIf { it.isNotEmpty() },
    )

    companion object {
        val EMPTY = ManualPaymentConfig()
    }
}

/**
 * Kredensial Tripay per tenant. [apiKey] dan [privateKey] hanya berada di memori domain; adapter
 * persistence menyimpannya sebagai ciphertext dan API pengaturan hanya mengekspos penanda `*Set`.
 */
class TripayPaymentConfig(
    val merchantCode: String? = null,
    apiKey: String? = null,
    privateKey: String? = null,
    val sandbox: Boolean = true,
) {
    private val apiKeyValue = apiKey
    private val privateKeyValue = privateKey

    val ready: Boolean
        get() = !merchantCode.isNullOrBlank() && !apiKeyValue.isNullOrBlank() && !privateKeyValue.isNullOrBlank()

    fun apiKeyForGateway(): String? = apiKeyValue

    fun privateKeyForGateway(): String? = privateKeyValue

    fun normalized(): TripayPaymentConfig = TripayPaymentConfig(
        merchantCode = merchantCode?.trim()?.takeIf { it.isNotEmpty() },
        apiKey = apiKeyValue?.trim()?.takeIf { it.isNotEmpty() },
        privateKey = privateKeyValue?.trim()?.takeIf { it.isNotEmpty() },
        sandbox = sandbox,
    )

    override fun equals(other: Any?): Boolean =
        other is TripayPaymentConfig &&
            merchantCode == other.merchantCode &&
            apiKeyValue == other.apiKeyValue &&
            privateKeyValue == other.privateKeyValue &&
            sandbox == other.sandbox

    override fun hashCode(): Int =
        listOf(merchantCode, apiKeyValue, privateKeyValue, sandbox).hashCode()

    override fun toString(): String =
        "TripayPaymentConfig(merchantCode=$merchantCode, apiKeySet=${!apiKeyValue.isNullOrBlank()}, " +
            "privateKeySet=${!privateKeyValue.isNullOrBlank()}, sandbox=$sandbox)"

    companion object {
        val EMPTY = TripayPaymentConfig()
    }
}

/**
 * Setelan penagihan satu tenant (satu baris per tenant): metode aktif ([provider]) + konfigurasi
 * pembayaran MANUAL, serta kredensial Tripay BYOK. Charge Pivot memakai akun MASTER platform
 * ([PivotMasterConfig]) atas nama sub-account tenant ([TenantPivotAccount]); rahasia Tripay
 * dienkripsi di persistence dan tidak pernah dikembalikan oleh API.
 *
 * Default aman: [PaymentProvider.MANUAL] / MATI — perilaku lama (webhook MANUAL bersecret global)
 * berlaku sampai tenant mengaktifkan Pivot dengan sadar.
 */
class TenantPaymentGateway private constructor(
    val id: UUID,
    val tenantId: UUID,
    provider: PaymentProvider,
    enabled: Boolean,
    manual: ManualPaymentConfig,
    tripay: TripayPaymentConfig,
    qrisStorageKey: String?,
    qrisContentType: String?,
) {
    var provider: PaymentProvider = provider
        private set

    var enabled: Boolean = enabled
        private set

    /** Metode pembayaran manual (tunai/transfer/QRIS). Non-rahasia; disunting operator lewat [update]. */
    var manual: ManualPaymentConfig = manual
        private set

    /** Konfigurasi Tripay BYOK; dua key hanya dipakai resolver/adapter, bukan view API. */
    var tripay: TripayPaymentConfig = tripay
        private set

    /** Object-storage key gambar QRIS (satu per tenant). Dikelola [attachQrisImage]/[clearQrisImage]. */
    var qrisStorageKey: String? = qrisStorageKey
        private set

    /** MIME gambar QRIS (mis. `image/png`), untuk menyajikan byte balik dengan tipe benar. */
    var qrisContentType: String? = qrisContentType
        private set

    /** Apakah gambar QRIS sudah terunggah (byte ada di storage). */
    val qrisImageSet: Boolean get() = !qrisStorageKey.isNullOrBlank()

    /** Apakah tenant memakai Pivot otomatis (bukan MANUAL) untuk menagih pelanggannya. */
    val usesPivot: Boolean get() = enabled && provider == PaymentProvider.PIVOT

    /** Apakah Tripay BYOK siap dipakai untuk menerbitkan charge otomatis. */
    val usesTripay: Boolean get() = enabled && provider == PaymentProvider.TRIPAY && tripay.ready

    /** Sunting metode aktif + konfigurasi manual (semua non-rahasia). */
    fun update(
        provider: PaymentProvider,
        enabled: Boolean,
        manual: ManualPaymentConfig = ManualPaymentConfig.EMPTY,
        tripay: TripayPaymentConfig = TripayPaymentConfig.EMPTY,
    ) {
        this.provider = provider
        this.enabled = enabled
        this.manual = manual.normalized()
        this.tripay = tripay.normalized()
    }

    /** Pasang (atau ganti) gambar QRIS yang sudah tersimpan di object storage. */
    fun attachQrisImage(storageKey: String, contentType: String) {
        this.qrisStorageKey = storageKey.trim().takeIf { it.isNotEmpty() }
            ?: throw com.duluin.ftth.common.domain.error.ValidationException("Storage key QRIS kosong")
        this.qrisContentType = contentType.trim().takeIf { it.isNotEmpty() } ?: "application/octet-stream"
    }

    /** Lepas gambar QRIS (byte-nya dihapus dari storage oleh pemanggil). */
    fun clearQrisImage() {
        this.qrisStorageKey = null
        this.qrisContentType = null
    }

    companion object {
        /** Setelan bawaan tenant yang belum pernah menyetel — MANUAL/MATI. */
        fun defaultFor(tenantId: UUID): TenantPaymentGateway = TenantPaymentGateway(
            id = UuidV7.generate(),
            tenantId = tenantId,
            provider = PaymentProvider.MANUAL,
            enabled = false,
            manual = ManualPaymentConfig.EMPTY,
            tripay = TripayPaymentConfig.EMPTY,
            qrisStorageKey = null,
            qrisContentType = null,
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            provider: PaymentProvider,
            enabled: Boolean,
            manual: ManualPaymentConfig,
            tripay: TripayPaymentConfig = TripayPaymentConfig.EMPTY,
            qrisStorageKey: String?,
            qrisContentType: String?,
        ): TenantPaymentGateway = TenantPaymentGateway(
            id, tenantId, provider, enabled, manual, tripay, qrisStorageKey, qrisContentType,
        )
    }
}
