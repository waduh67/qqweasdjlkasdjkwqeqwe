package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Adapter setelan payment gateway sekaligus batas enkripsi: domain memegang kredensial
 * apa adanya, DB hanya pernah melihat ciphertext (cermin [NotificationSettingsPersistenceAdapter]).
 * Satu baris per tenant — [find] mengambil baris tunggal hasil saring RLS.
 */
@Component
class TenantPaymentGatewayPersistenceAdapter(
    private val jpa: TenantPaymentGatewayJpaRepository,
    private val cipher: SecretCipher,
) : TenantPaymentGatewayRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    // Satu baris per tenant; RLS + @TenantId sudah menyaring findAll ke tenant aktif.
    override fun find(): TenantPaymentGateway? = jpa.findAll().firstOrNull()?.toDomain()

    override fun save(settings: TenantPaymentGateway): TenantPaymentGateway {
        val encryptedApiKey = settings.apiKey?.let(cipher::encrypt)
        val encryptedSecretKey = settings.secretKey?.let(cipher::encrypt)
        val encryptedWebhookToken = settings.webhookToken?.let(cipher::encrypt)
        val entity = jpa.findById(settings.id).orElse(null)?.apply {
            provider = settings.provider
            mode = settings.mode
            enabled = settings.enabled
            apiKey = encryptedApiKey
            secretKey = encryptedSecretKey
            webhookToken = encryptedWebhookToken
            subAccountId = settings.subAccountId
            paymentMethod = settings.paymentMethod
        } ?: TenantPaymentGatewayJpaEntity(
            id = settings.id,
            provider = settings.provider,
            mode = settings.mode,
            enabled = settings.enabled,
            apiKey = encryptedApiKey,
            secretKey = encryptedSecretKey,
            webhookToken = encryptedWebhookToken,
            subAccountId = settings.subAccountId,
            paymentMethod = settings.paymentMethod,
        )
        return jpa.save(entity).toDomain()
    }

    private fun TenantPaymentGatewayJpaEntity.toDomain(): TenantPaymentGateway = TenantPaymentGateway.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        provider = provider,
        mode = mode,
        enabled = enabled,
        apiKey = cipher.decryptQuietly(apiKey, "api_key", log),
        secretKey = cipher.decryptQuietly(secretKey, "secret_key", log),
        webhookToken = cipher.decryptQuietly(webhookToken, "webhook_token", log),
        subAccountId = subAccountId,
        paymentMethod = paymentMethod,
    )
}

/**
 * Kredensial yang tak bisa didekripsi (mis. kunci dirotasi tanpa migrasi) tidak boleh
 * menggagalkan pemuatan setelan; barisnya tetap tampil, hanya kehilangan kredensial dan
 * bisa diisi ulang operator — persis pola secret notifikasi & bng.
 */
private fun SecretCipher.decryptQuietly(ciphertext: String?, label: String, log: Logger): String? {
    if (ciphertext == null) return null
    return runCatching { decrypt(ciphertext) }
        .onFailure { log.warn("Kredensial gateway '{}' tidak bisa didekripsi; perlu diisi ulang", label) }
        .getOrNull()
}
