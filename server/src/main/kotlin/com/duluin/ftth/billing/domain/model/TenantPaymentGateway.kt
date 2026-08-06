package com.duluin.ftth.billing.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import org.springframework.modulith.NamedInterface
import java.util.UUID

/**
 * Penyedia payment gateway yang bisa dipilih tenant untuk menagih pelanggannya.
 *
 *  - [XENDIT]   digarap penuh (BYO & PLATFORM/xenPlatform).
 *  - [MIDTRANS] BYO (Snap): satu Server Key jadi Basic-auth Snap SEKALIGUS secret verifikasi
 *    signature webhook (SHA512), dibawa di [TenantPaymentGateway.secretKey]. Tak ada webhook
 *    token terpisah. Mode PLATFORM belum didukung dari sisi tenant (v1 hanya XENDIT).
 *  - [PAYWUZ]   BYO: satu API key (Bearer + HMAC webhook) + kode metode bayar per-tenant.
 *  - [PIVOT]    BYO: sepasang kredensial (merchant id + merchant secret) + callback API key.
 *  - [MANUAL]   pembayaran luar-band (tunai/transfer/QRIS) + webhook bersecret bersama; ini
 *    juga fallback saat tenant belum/nonaktif mengonfigurasi gateway.
 */
enum class PaymentProvider { XENDIT, MIDTRANS, PAYWUZ, PIVOT, MANUAL }

/**
 *  - [BYO]      tenant memakai akun gateway-nya sendiri (kredensial di baris tenant).
 *  - [PLATFORM] tenant memakai akun MASTER platform lewat sub-account (Xendit xenPlatform):
 *               kredensial master dari config/env, [TenantPaymentGateway.subAccountId] menandai
 *               atas nama siapa charge dibuat (header `for-user-id`).
 *
 * Bagian dari named interface `gateway` — dipakai `platformbilling` (mode gateway langganan).
 */
@NamedInterface("gateway")
enum class GatewayMode { BYO, PLATFORM }

/**
 * Kredensial MASTER platform (dari config/env), disuntikkan ke [TenantPaymentGateway.resolve]
 * saat menyelesaikan cabang PLATFORM. Sengaja BUKAN disimpan per tenant agar charge/callback
 * tak perlu membaca baris tenant lain (lintas-RLS).
 */
data class PlatformGatewayCreds(
    val secretKey: String,
    /** Token verifikasi callback platform (fallback bila sub-account tak punya token sendiri). */
    val webhookToken: String?,
    /** Fee rule komisi platform, dipasang di header `with-fee-rule` saat charge PLATFORM. */
    val feeRuleId: String?,
)

/**
 * Kredensial gateway yang SUDAH teresolusi & terdekripsi — bentuk siap-pakai yang dipakai
 * adapter untuk membuat charge / memverifikasi callback. Beda dari [TenantPaymentGateway]
 * (setelan mentah terenkripsi), tipe ini datar & lahir dari [TenantPaymentGateway.resolve].
 * Analog dengan `WhatsAppGateway` pada module notification.
 *
 * [provider] String (bukan enum) agar registry bisa memilih adapter apa pun — termasuk
 * `MANUAL` fallback yang bukan bagian dari konfigurasi tenant.
 *
 * Bagian dari named interface `gateway` — `platformbilling` menyusun konteks ini untuk
 * menagih langganan lewat gateway platform, memakai adapter billing yang sama.
 */
@NamedInterface("gateway")
data class ResolvedGatewayContext(
    val provider: String,
    val mode: GatewayMode,
    /** BYO: kunci milik tenant; PLATFORM: kunci MASTER platform. Null untuk MANUAL. Pivot: merchant secret. */
    val secretKey: String?,
    /** Token verifikasi callback (MANUAL fallback ke shared secret global). */
    val webhookToken: String?,
    /** PLATFORM saja → header `for-user-id`. */
    val subAccountId: String? = null,
    /** PLATFORM saja → header `with-fee-rule`. */
    val feeRuleId: String? = null,
    /** BYO PIVOT: merchant id (`X-MERCHANT-ID`), disandingkan dengan [secretKey] (`X-MERCHANT-SECRET`). */
    val apiKey: String? = null,
    /** BYO PAYWUZ: kode metode pembayaran per-tenant (mis. `QRIS`/`VA`); null → default global config. */
    val paymentMethod: String? = null,
)

/**
 * Konfigurasi pembayaran MANUAL per-tenant (transfer / QRIS) — dipakai saat gateway otomatis
 * nonaktif: inilah satu-satunya instruksi bayar yang bisa ditunjukkan ke pelanggan. Semua
 * NON-RAHASIA (bukan kredensial), jadi plaintext & semantik "selalu diganti" (null/kosong =
 * kosongkan), mengikuti pola [TenantPaymentGateway.paymentMethod].
 *
 * Gambar QRIS byte-nya TIDAK di sini (ada di object storage); [qrisEnabled] hanya penanda
 * aktif, gambarnya dikelola lewat [TenantPaymentGateway.attachQrisImage]/[clearQrisImage].
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
 * Setelan payment gateway satu tenant (satu baris per tenant): penyedia + mode + kredensial
 * bawa-sendiri (BYO) atau penanda sub-account (PLATFORM).
 *
 * Kredensial ([apiKey]/[secretKey]/[webhookToken]) plaintext di domain; adapter persistence
 * yang mengenkripsi ke DB — sama seperti token gateway WA & secret CoA BRAS. Pada [update],
 * kredensial null/kosong berarti "biarkan apa adanya" agar sunting field lain tak menghapus
 * rahasia tanpa sengaja.
 *
 * Default aman: [PaymentProvider.MANUAL] / [GatewayMode.BYO] / MATI — perilaku lama (webhook
 * MANUAL bersecret global) berlaku sampai tenant mengonfigurasi gateway dengan sadar.
 */
class TenantPaymentGateway private constructor(
    val id: UUID,
    val tenantId: UUID,
    provider: PaymentProvider,
    mode: GatewayMode,
    enabled: Boolean,
    apiKey: String?,
    secretKey: String?,
    webhookToken: String?,
    subAccountId: String?,
    paymentMethod: String?,
    manual: ManualPaymentConfig,
    qrisStorageKey: String?,
    qrisContentType: String?,
) {
    var provider: PaymentProvider = provider
        private set

    var mode: GatewayMode = mode
        private set

    var enabled: Boolean = enabled
        private set

    /** Ciphertext di DB, plaintext di sini. Penyedia key-pair (Paywuz `pk_...`/Pivot). */
    var apiKey: String? = apiKey
        private set

    /** Ciphertext di DB, plaintext di sini. Xendit BYO secret key. */
    var secretKey: String? = secretKey
        private set

    /** Ciphertext di DB, plaintext di sini. Token verifikasi callback per-tenant. */
    var webhookToken: String? = webhookToken
        private set

    /** PLATFORM: user_id sub-account Xendit. Diisi oleh provisioning platform-admin, bukan operator. */
    var subAccountId: String? = subAccountId
        private set

    /** BYO PAYWUZ: kode metode pembayaran per-tenant. Bukan rahasia (plaintext di DB). null = default global. */
    var paymentMethod: String? = paymentMethod
        private set

    /** Metode pembayaran manual (tunai/transfer/QRIS). Non-rahasia; disunting operator lewat [update]. */
    var manual: ManualPaymentConfig = manual
        private set

    /** Object-storage key gambar QRIS (satu per tenant). Dikelola [attachQrisImage]/[clearQrisImage], bukan [update]. */
    var qrisStorageKey: String? = qrisStorageKey
        private set

    /** MIME gambar QRIS (mis. `image/png`), untuk menyajikan byte balik dengan tipe benar. */
    var qrisContentType: String? = qrisContentType
        private set

    /** Apakah gambar QRIS sudah terunggah (byte ada di storage). */
    val qrisImageSet: Boolean get() = !qrisStorageKey.isNullOrBlank()

    /**
     * Sunting setelan dari sisi operator (tenant admin). Kredensial null/kosong = biarkan apa
     * adanya. [paymentMethod] BUKAN rahasia → semantik ganti: null/kosong = kosongkan (jatuh ke
     * default global). [subAccountId] TIDAK disetel di sini — ia hasil provisioning platform-admin
     * ([provisionPlatform]); operator memilih PLATFORM hanya setelah sub-account tersedia.
     */
    fun update(
        provider: PaymentProvider,
        mode: GatewayMode,
        enabled: Boolean,
        apiKey: String?,
        secretKey: String?,
        webhookToken: String?,
        paymentMethod: String? = null,
        manual: ManualPaymentConfig = ManualPaymentConfig.EMPTY,
    ) {
        this.provider = provider
        this.mode = mode
        this.enabled = enabled
        // Null/kosong = biarkan apa adanya, agar rahasia tak terhapus saat menyunting field lain.
        apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { this.apiKey = validateSecret(it, "API key") }
        secretKey?.trim()?.takeIf { it.isNotEmpty() }?.let { this.secretKey = validateSecret(it, "Secret key") }
        webhookToken?.trim()?.takeIf { it.isNotEmpty() }?.let { this.webhookToken = validateSecret(it, "Webhook token") }
        // Bukan rahasia — selalu diganti (null/kosong = pakai default global).
        this.paymentMethod = paymentMethod?.trim()?.takeIf { it.isNotEmpty() }
        // Konfigurasi manual non-rahasia — selalu diganti (gambar QRIS dikelola terpisah).
        this.manual = manual.normalized()
        if (mode == GatewayMode.PLATFORM && subAccountId.isNullOrBlank()) {
            throw ValidationException("Mode PLATFORM butuh sub-account — provisikan lewat admin platform dulu")
        }
    }

    /** Pasang (atau ganti) gambar QRIS yang sudah tersimpan di object storage. */
    fun attachQrisImage(storageKey: String, contentType: String) {
        this.qrisStorageKey = storageKey.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Storage key QRIS kosong")
        this.qrisContentType = contentType.trim().takeIf { it.isNotEmpty() } ?: "application/octet-stream"
    }

    /** Lepas gambar QRIS (byte-nya dihapus dari storage oleh pemanggil). */
    fun clearQrisImage() {
        this.qrisStorageKey = null
        this.qrisContentType = null
    }

    /**
     * Pasang hasil provisioning sub-account platform (Xendit xenPlatform): kunci mode PLATFORM
     * ke penyedia XENDIT, aktifkan, dan simpan id sub-account + token callback-nya. Dipanggil
     * oleh service provisioning platform-admin di dalam konteks tenant sasaran.
     */
    fun provisionPlatform(subAccountId: String, webhookToken: String?) {
        this.provider = PaymentProvider.XENDIT
        this.mode = GatewayMode.PLATFORM
        this.enabled = true
        this.subAccountId = subAccountId.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Sub-account ID kosong")
        webhookToken?.trim()?.takeIf { it.isNotEmpty() }?.let { this.webhookToken = it }
    }

    /**
     * Bentuk kredensial siap-pakai untuk adapter, atau null bila gateway mati / konfigurasinya
     * belum lengkap (mis. XENDIT BYO tanpa secret key, atau PLATFORM tanpa sub-account /
     * platform nonaktif). Null = pemanggil jatuh ke fallback MANUAL.
     *
     * [platform] kredensial master (null bila platform nonaktif / secret master kosong).
     */
    fun resolve(platform: PlatformGatewayCreds?): ResolvedGatewayContext? {
        if (!enabled) return null
        return when (mode) {
            GatewayMode.BYO -> resolveByo()
            GatewayMode.PLATFORM -> resolvePlatform(platform)
        }
    }

    private fun resolveByo(): ResolvedGatewayContext? = when (provider) {
        PaymentProvider.XENDIT -> {
            val key = secretKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            ResolvedGatewayContext("XENDIT", GatewayMode.BYO, secretKey = key, webhookToken = webhookToken)
        }
        // Midtrans (Snap) butuh SATU kredensial: Server Key — jadi Basic-auth Snap SEKALIGUS
        // secret verifikasi signature webhook (SHA512), dibawa di secretKey; tak ada webhook
        // token terpisah. Seperti XENDIT: tanpa Server Key konfigurasi tak lengkap → null
        // (pemanggil jatuh ke fallback MANUAL, bukan diam-diam charge dengan kredensial kosong).
        PaymentProvider.MIDTRANS -> {
            val key = secretKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            ResolvedGatewayContext("MIDTRANS", GatewayMode.BYO, secretKey = key, webhookToken = null)
        }
        // Pivot butuh SEPASANG kredensial: merchant id (apiKey → X-MERCHANT-ID) + merchant
        // secret (secretKey → X-MERCHANT-SECRET). Resolusi tetap jalan walau belum lengkap agar
        // adapter Pivot yang dipilih & melempar pesan jelas (bukan diam-diam jatuh ke MANUAL).
        PaymentProvider.PIVOT ->
            ResolvedGatewayContext("PIVOT", GatewayMode.BYO, secretKey = secretKey, webhookToken = webhookToken, apiKey = apiKey)
        // Paywuz butuh SATU kredensial (API key) yang jadi Bearer auth SEKALIGUS secret HMAC
        // webhook — dibawa di secretKey; tak ada webhook_token terpisah. Kode metode per-tenant
        // ikut dibawa (null → adapter pakai default global config).
        PaymentProvider.PAYWUZ ->
            ResolvedGatewayContext("PAYWUZ", GatewayMode.BYO, secretKey = apiKey, webhookToken = webhookToken, paymentMethod = paymentMethod)
        PaymentProvider.MANUAL ->
            ResolvedGatewayContext("MANUAL", GatewayMode.BYO, secretKey = null, webhookToken = webhookToken)
    }

    private fun resolvePlatform(platform: PlatformGatewayCreds?): ResolvedGatewayContext? {
        // PLATFORM hanya untuk XENDIT (xenPlatform) di v1.
        if (provider != PaymentProvider.XENDIT) return null
        val creds = platform ?: return null
        val sub = subAccountId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ResolvedGatewayContext(
            provider = "XENDIT",
            mode = GatewayMode.PLATFORM,
            secretKey = creds.secretKey,
            webhookToken = webhookToken ?: creds.webhookToken,
            subAccountId = sub,
            feeRuleId = creds.feeRuleId,
        )
    }

    companion object {
        private const val MAX_SECRET = 512

        /** Setelan bawaan tenant yang belum pernah menyetel — MANUAL/BYO/MATI, tanpa kredensial. */
        fun defaultFor(tenantId: UUID): TenantPaymentGateway = TenantPaymentGateway(
            id = UuidV7.generate(),
            tenantId = tenantId,
            provider = PaymentProvider.MANUAL,
            mode = GatewayMode.BYO,
            enabled = false,
            apiKey = null,
            secretKey = null,
            webhookToken = null,
            subAccountId = null,
            paymentMethod = null,
            manual = ManualPaymentConfig.EMPTY,
            qrisStorageKey = null,
            qrisContentType = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            provider: PaymentProvider,
            mode: GatewayMode,
            enabled: Boolean,
            apiKey: String?,
            secretKey: String?,
            webhookToken: String?,
            subAccountId: String?,
            paymentMethod: String?,
            manual: ManualPaymentConfig,
            qrisStorageKey: String?,
            qrisContentType: String?,
        ): TenantPaymentGateway = TenantPaymentGateway(
            id, tenantId, provider, mode, enabled, apiKey, secretKey, webhookToken, subAccountId, paymentMethod,
            manual, qrisStorageKey, qrisContentType,
        )

        private fun validateSecret(value: String, label: String): String {
            if (value.length > MAX_SECRET) throw ValidationException("$label maksimal $MAX_SECRET karakter")
            return value
        }
    }
}
