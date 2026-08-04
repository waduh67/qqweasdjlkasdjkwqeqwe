package com.duluin.ftth.platformbilling.domain.model

import com.duluin.ftth.billing.domain.model.GatewayMode
import com.duluin.ftth.billing.domain.model.ResolvedGatewayContext
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Kredensial satu penyedia pembayaran di level PLATFORM (satu baris per
 * [PlatformPaymentProvider]) — dipakai menagih langganan tenant ke aplikasi. Beda dari
 * `TenantPaymentGateway` (per tenant, mode BYO/PLATFORM), gateway platform SELALU memakai
 * akun platform sendiri → resolusi selalu [GatewayMode.BYO].
 *
 * Kredensial ([apiKey]/[secretKey]/[webhookToken]) plaintext di domain; adapter persistence
 * yang mengenkripsi ke DB. Pada [update], kredensial null/kosong berarti "biarkan apa adanya"
 * agar menyunting field lain tak menghapus rahasia tanpa sengaja (pola `TenantPaymentGateway`).
 *
 * Pemetaan kredensial per penyedia (dituangkan ke [ResolvedGatewayContext] di [resolve]):
 *  - PAYWUZ   : [apiKey] = API key proyek (Bearer auth + secret HMAC webhook, satu kunci) →
 *               dibawa di `secretKey`. [paymentMethod] opsional (mis. QRIS/VA).
 *  - XENDIT   : [secretKey] = secret key (basic-auth), [webhookToken] = token verifikasi callback.
 *  - MIDTRANS : [secretKey] = Server Key (basic-auth Snap + secret signature SHA512).
 */
class PlatformPaymentGateway private constructor(
    val id: UUID,
    val provider: PlatformPaymentProvider,
    enabled: Boolean,
    apiKey: String?,
    secretKey: String?,
    webhookToken: String?,
    paymentMethod: String?,
) {
    var enabled: Boolean = enabled
        private set

    /** Ciphertext di DB, plaintext di sini. PAYWUZ: API key (Bearer + HMAC). */
    var apiKey: String? = apiKey
        private set

    /** Ciphertext di DB, plaintext di sini. XENDIT: secret key. MIDTRANS: Server Key. */
    var secretKey: String? = secretKey
        private set

    /** Ciphertext di DB, plaintext di sini. Token verifikasi callback (dipakai XENDIT). */
    var webhookToken: String? = webhookToken
        private set

    /** PAYWUZ: kode metode pembayaran (mis. QRIS/VA). Bukan rahasia (plaintext). null = default global. */
    var paymentMethod: String? = paymentMethod
        private set

    /**
     * Sunting kredensial dari sisi super-admin. Rahasia null/kosong = biarkan apa adanya.
     * [paymentMethod] BUKAN rahasia → semantik ganti (null/kosong = kosongkan).
     */
    fun update(
        enabled: Boolean,
        apiKey: String?,
        secretKey: String?,
        webhookToken: String?,
        paymentMethod: String? = null,
    ) {
        this.enabled = enabled
        apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { this.apiKey = validateSecret(it, "API key") }
        secretKey?.trim()?.takeIf { it.isNotEmpty() }?.let { this.secretKey = validateSecret(it, "Secret key") }
        webhookToken?.trim()?.takeIf { it.isNotEmpty() }?.let { this.webhookToken = validateSecret(it, "Webhook token") }
        this.paymentMethod = paymentMethod?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Apakah kredensial utama penyedia sudah terisi (untuk view boolean, tanpa membocorkan rahasia). */
    val credentialsSet: Boolean
        get() = when (provider) {
            PlatformPaymentProvider.PAYWUZ -> !apiKey.isNullOrBlank()
            PlatformPaymentProvider.XENDIT, PlatformPaymentProvider.MIDTRANS -> !secretKey.isNullOrBlank()
        }

    /**
     * Bentuk kredensial siap-pakai untuk adapter, atau null bila gateway mati / kredensial belum
     * lengkap. Selalu mode [GatewayMode.BYO] (akun platform sendiri).
     */
    fun resolve(): ResolvedGatewayContext? {
        if (!enabled) return null
        return when (provider) {
            PlatformPaymentProvider.PAYWUZ -> {
                val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                ResolvedGatewayContext(
                    provider = "PAYWUZ",
                    mode = GatewayMode.BYO,
                    secretKey = key,
                    webhookToken = null,
                    paymentMethod = paymentMethod,
                )
            }
            PlatformPaymentProvider.XENDIT -> {
                val key = secretKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                ResolvedGatewayContext(
                    provider = "XENDIT",
                    mode = GatewayMode.BYO,
                    secretKey = key,
                    webhookToken = webhookToken,
                )
            }
            PlatformPaymentProvider.MIDTRANS -> {
                val key = secretKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                ResolvedGatewayContext(
                    provider = "MIDTRANS",
                    mode = GatewayMode.BYO,
                    secretKey = key,
                    webhookToken = webhookToken,
                )
            }
        }
    }

    companion object {
        private const val MAX_SECRET = 512

        /** Baris bawaan penyedia yang belum dikonfigurasi — MATI, tanpa kredensial. */
        fun defaultFor(provider: PlatformPaymentProvider): PlatformPaymentGateway = PlatformPaymentGateway(
            id = UuidV7.generate(),
            provider = provider,
            enabled = false,
            apiKey = null,
            secretKey = null,
            webhookToken = null,
            paymentMethod = null,
        )

        fun rehydrate(
            id: UUID,
            provider: PlatformPaymentProvider,
            enabled: Boolean,
            apiKey: String?,
            secretKey: String?,
            webhookToken: String?,
            paymentMethod: String?,
        ): PlatformPaymentGateway = PlatformPaymentGateway(
            id, provider, enabled, apiKey, secretKey, webhookToken, paymentMethod,
        )

        private fun validateSecret(value: String, label: String): String {
            if (value.length > MAX_SECRET) throw ValidationException("$label maksimal $MAX_SECRET karakter")
            return value
        }
    }
}
