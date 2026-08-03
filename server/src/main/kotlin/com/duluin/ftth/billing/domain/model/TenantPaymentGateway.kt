package com.duluin.ftth.billing.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Penyedia payment gateway yang bisa dipilih tenant.
 *
 *  - [XENDIT] digarap penuh (BYO & PLATFORM/xenPlatform).
 *  - [PAYWUZ]/[PIVOT] kerangka — bisa dipilih & dikonfigurasi, tapi charge belum jalan
 *    (dokumentasi API-nya belum tersedia); adapter melempar sampai diimplementasikan.
 *  - [MANUAL] pembayaran luar-band (tunai/transfer) + webhook bersecret bersama; ini
 *    juga fallback saat tenant belum/nonaktif mengonfigurasi gateway.
 */
enum class PaymentProvider { XENDIT, PAYWUZ, PIVOT, MANUAL }

/**
 *  - [BYO]      tenant memakai akun gateway-nya sendiri (kredensial di baris tenant).
 *  - [PLATFORM] tenant memakai akun MASTER platform lewat sub-account (Xendit xenPlatform):
 *               kredensial master dari config/env, [TenantPaymentGateway.subAccountId] menandai
 *               atas nama siapa charge dibuat (header `for-user-id`).
 */
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
 */
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
        if (mode == GatewayMode.PLATFORM && subAccountId.isNullOrBlank()) {
            throw ValidationException("Mode PLATFORM butuh sub-account — provisikan lewat admin platform dulu")
        }
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
        ): TenantPaymentGateway = TenantPaymentGateway(
            id, tenantId, provider, mode, enabled, apiKey, secretKey, webhookToken, subAccountId, paymentMethod,
        )

        private fun validateSecret(value: String, label: String): String {
            if (value.length > MAX_SECRET) throw ValidationException("$label maksimal $MAX_SECRET karakter")
            return value
        }
    }
}
