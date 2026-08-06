package com.duluin.ftth.billing.adapter.outbound.persistence

import com.duluin.ftth.billing.application.port.outbound.PivotMasterConfigRepository
import com.duluin.ftth.billing.domain.model.PivotMasterConfig
import com.duluin.ftth.common.security.SecretCipher
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Adapter setelan MASTER Pivot sekaligus batas enkripsi: domain memegang kredensial apa adanya,
 * DB hanya pernah melihat ciphertext (cermin [TenantPaymentGatewayPersistenceAdapter]). Singleton
 * global — [find] mengambil baris tunggal (tabel hanya pernah berisi satu).
 */
@Component
class PivotMasterConfigPersistenceAdapter(
    private val jpa: PivotMasterConfigJpaRepository,
    private val cipher: SecretCipher,
) : PivotMasterConfigRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun find(): PivotMasterConfig? = jpa.findAll().firstOrNull()?.toDomain()

    override fun save(config: PivotMasterConfig): PivotMasterConfig {
        val encMerchantId = config.merchantId?.let(cipher::encrypt)
        val encMerchantSecret = config.merchantSecret?.let(cipher::encrypt)
        val encCallbackKey = config.callbackApiKey?.let(cipher::encrypt)
        val entity = jpa.findById(config.id).orElse(null)?.apply {
            enabled = config.enabled
            merchantId = encMerchantId
            merchantSecret = encMerchantSecret
            callbackApiKey = encCallbackKey
            sandbox = config.sandbox
            platformFeeMinor = config.platformFeeMinor
            platformFeeType = config.platformFeeType
            payoutChannelCode = config.payoutChannelCode
            payoutAccountNumber = config.payoutAccountNumber
        } ?: PivotMasterConfigJpaEntity(
            id = config.id,
            enabled = config.enabled,
            merchantId = encMerchantId,
            merchantSecret = encMerchantSecret,
            callbackApiKey = encCallbackKey,
            sandbox = config.sandbox,
            platformFeeMinor = config.platformFeeMinor,
            platformFeeType = config.platformFeeType,
            payoutChannelCode = config.payoutChannelCode,
            payoutAccountNumber = config.payoutAccountNumber,
        )
        return jpa.save(entity).toDomain()
    }

    private fun PivotMasterConfigJpaEntity.toDomain(): PivotMasterConfig = PivotMasterConfig.rehydrate(
        id = id,
        enabled = enabled,
        merchantId = cipher.decryptQuietly(merchantId, "merchant_id", log),
        merchantSecret = cipher.decryptQuietly(merchantSecret, "merchant_secret", log),
        callbackApiKey = cipher.decryptQuietly(callbackApiKey, "callback_api_key", log),
        sandbox = sandbox,
        platformFeeMinor = platformFeeMinor,
        platformFeeType = platformFeeType,
        payoutChannelCode = payoutChannelCode,
        payoutAccountNumber = payoutAccountNumber,
    )
}

/**
 * Kredensial yang tak bisa didekripsi (mis. kunci dirotasi tanpa migrasi) tidak menggagalkan
 * pemuatan setelan; barisnya tetap tampil, hanya kehilangan kredensial & bisa diisi ulang admin.
 */
private fun SecretCipher.decryptQuietly(ciphertext: String?, label: String, log: Logger): String? {
    if (ciphertext == null) return null
    return runCatching { decrypt(ciphertext) }
        .onFailure { log.warn("Kredensial Pivot master '{}' tidak bisa didekripsi; perlu diisi ulang", label) }
        .getOrNull()
}
