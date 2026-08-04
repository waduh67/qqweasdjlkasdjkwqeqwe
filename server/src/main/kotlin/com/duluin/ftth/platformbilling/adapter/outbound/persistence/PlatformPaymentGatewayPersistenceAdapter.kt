package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.platformbilling.application.port.outbound.PlatformPaymentGatewayRepository
import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentGateway
import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Adapter kredensial gateway platform sekaligus batas enkripsi: domain memegang kredensial apa
 * adanya, DB hanya pernah melihat ciphertext (cermin `TenantPaymentGatewayPersistenceAdapter`).
 * Platform-level (tanpa RLS).
 */
@Component
class PlatformPaymentGatewayPersistenceAdapter(
    private val jpa: PlatformPaymentGatewayJpaRepository,
    private val cipher: SecretCipher,
) : PlatformPaymentGatewayRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun findAll(): List<PlatformPaymentGateway> = jpa.findAll().map { it.toDomain() }

    override fun findByProvider(provider: PlatformPaymentProvider): PlatformPaymentGateway? =
        jpa.findByProvider(provider)?.toDomain()

    override fun save(gateway: PlatformPaymentGateway): PlatformPaymentGateway {
        val encryptedApiKey = gateway.apiKey?.let(cipher::encrypt)
        val encryptedSecretKey = gateway.secretKey?.let(cipher::encrypt)
        val encryptedWebhookToken = gateway.webhookToken?.let(cipher::encrypt)
        val entity = jpa.findById(gateway.id).orElse(null)?.apply {
            provider = gateway.provider
            enabled = gateway.enabled
            apiKey = encryptedApiKey
            secretKey = encryptedSecretKey
            webhookToken = encryptedWebhookToken
            paymentMethod = gateway.paymentMethod
        } ?: PlatformPaymentGatewayJpaEntity(
            id = gateway.id,
            provider = gateway.provider,
            enabled = gateway.enabled,
            apiKey = encryptedApiKey,
            secretKey = encryptedSecretKey,
            webhookToken = encryptedWebhookToken,
            paymentMethod = gateway.paymentMethod,
        )
        return jpa.save(entity).toDomain()
    }

    private fun PlatformPaymentGatewayJpaEntity.toDomain(): PlatformPaymentGateway = PlatformPaymentGateway.rehydrate(
        id = id,
        provider = provider,
        enabled = enabled,
        apiKey = cipher.decryptQuietly(apiKey, "api_key", log),
        secretKey = cipher.decryptQuietly(secretKey, "secret_key", log),
        webhookToken = cipher.decryptQuietly(webhookToken, "webhook_token", log),
        paymentMethod = paymentMethod,
    )
}

/**
 * Kredensial yang tak bisa didekripsi (mis. kunci dirotasi tanpa migrasi) tidak boleh
 * menggagalkan pemuatan; barisnya tetap tampil, kehilangan kredensial dan bisa diisi ulang.
 */
private fun SecretCipher.decryptQuietly(ciphertext: String?, label: String, log: Logger): String? {
    if (ciphertext == null) return null
    return runCatching { decrypt(ciphertext) }
        .onFailure { log.warn("Kredensial gateway platform '{}' tidak bisa didekripsi; perlu diisi ulang", label) }
        .getOrNull()
}
