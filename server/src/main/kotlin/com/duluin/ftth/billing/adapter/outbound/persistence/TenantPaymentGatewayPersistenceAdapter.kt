package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.application.port.outbound.TenantPaymentGatewayRepository
import com.duluin.ftth.billing.domain.model.ManualPaymentConfig
import com.duluin.ftth.billing.domain.model.TenantPaymentGateway
import com.duluin.ftth.billing.domain.model.TripayPaymentConfig
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Adapter setelan penagihan per-tenant. Satu baris per tenant — [find] mengambil baris tunggal
 * hasil saring RLS. API/private key Tripay hanya dienkripsi/dekripsi di batas ini.
 */
@Component
class TenantPaymentGatewayPersistenceAdapter(
    private val jpa: TenantPaymentGatewayJpaRepository,
    private val cipher: SecretCipher,
) : TenantPaymentGatewayRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun find(): TenantPaymentGateway? = jpa.findAll().firstOrNull()?.toDomain()

    override fun save(settings: TenantPaymentGateway): TenantPaymentGateway {
        val encryptedTripayApiKey = settings.tripay.apiKeyForGateway()?.let(cipher::encrypt)
        val encryptedTripayPrivateKey = settings.tripay.privateKeyForGateway()?.let(cipher::encrypt)
        val entity = jpa.findById(settings.id).orElse(null)?.apply {
            provider = settings.provider
            enabled = settings.enabled
            tripayMerchantCode = settings.tripay.merchantCode
            tripayApiKey = encryptedTripayApiKey
            tripayPrivateKey = encryptedTripayPrivateKey
            tripaySandbox = settings.tripay.sandbox
            manualTransferEnabled = settings.manual.transferEnabled
            transferBankName = settings.manual.bankName
            transferAccountNumber = settings.manual.accountNumber
            transferAccountHolder = settings.manual.accountHolder
            manualQrisEnabled = settings.manual.qrisEnabled
            qrisStorageKey = settings.qrisStorageKey
            qrisContentType = settings.qrisContentType
        } ?: TenantPaymentGatewayJpaEntity(
            id = settings.id,
            provider = settings.provider,
            enabled = settings.enabled,
            tripayMerchantCode = settings.tripay.merchantCode,
            tripayApiKey = encryptedTripayApiKey,
            tripayPrivateKey = encryptedTripayPrivateKey,
            tripaySandbox = settings.tripay.sandbox,
            manualTransferEnabled = settings.manual.transferEnabled,
            transferBankName = settings.manual.bankName,
            transferAccountNumber = settings.manual.accountNumber,
            transferAccountHolder = settings.manual.accountHolder,
            manualQrisEnabled = settings.manual.qrisEnabled,
            qrisStorageKey = settings.qrisStorageKey,
            qrisContentType = settings.qrisContentType,
        )
        return jpa.save(entity).toDomain()
    }

    private fun TenantPaymentGatewayJpaEntity.toDomain(): TenantPaymentGateway = TenantPaymentGateway.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        provider = provider,
        enabled = enabled,
        manual = ManualPaymentConfig(
            transferEnabled = manualTransferEnabled,
            bankName = transferBankName,
            accountNumber = transferAccountNumber,
            accountHolder = transferAccountHolder,
            qrisEnabled = manualQrisEnabled,
        ),
        tripay = TripayPaymentConfig(
            merchantCode = tripayMerchantCode,
            apiKey = cipher.decryptQuietly(tripayApiKey, "api key", log),
            privateKey = cipher.decryptQuietly(tripayPrivateKey, "private key", log),
            sandbox = tripaySandbox,
        ),
        qrisStorageKey = qrisStorageKey,
        qrisContentType = qrisContentType,
    )
}

private fun SecretCipher.decryptQuietly(ciphertext: String?, label: String, log: Logger): String? {
    if (ciphertext == null) return null
    return runCatching { decrypt(ciphertext) }
        .onFailure { log.warn("Kredensial Tripay '{}' tidak bisa didekripsi; perlu diisi ulang", label) }
        .getOrNull()
}
