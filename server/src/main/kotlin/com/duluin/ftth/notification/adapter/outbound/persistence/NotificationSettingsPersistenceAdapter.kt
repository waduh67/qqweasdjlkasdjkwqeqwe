package com.duluin.ftth.notification.adapter.outbound.persistence

import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.domain.model.NotificationSettings
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Adapter setelan notifikasi sekaligus batas enkripsi: domain memegang token gateway
 * apa adanya, DB hanya pernah melihat ciphertext (sama seperti secret CoA BRAS).
 * Satu baris per tenant — [find] mengambil baris tunggal hasil saring RLS.
 */
@Component
class NotificationSettingsPersistenceAdapter(
    private val jpa: NotificationSettingsJpaRepository,
    private val cipher: SecretCipher,
) : NotificationSettingsRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    // Satu baris per tenant; RLS + @TenantId sudah menyaring findAll ke tenant aktif.
    override fun find(): NotificationSettings? = jpa.findAll().firstOrNull()?.toDomain()

    override fun save(settings: NotificationSettings): NotificationSettings {
        val encryptedHttpToken = settings.httpToken?.let(cipher::encrypt)
        val encryptedMetaToken = settings.metaAccessToken?.let(cipher::encrypt)
        val encryptedQontakToken = settings.qontakAccessToken?.let(cipher::encrypt)
        val entity = jpa.findById(settings.id).orElse(null)?.apply {
            provider = settings.provider
            gatewayEnabled = settings.gatewayEnabled
            emailEnabled = settings.emailEnabled
            httpEndpointUrl = settings.httpEndpointUrl
            httpToken = encryptedHttpToken
            httpPhoneField = settings.httpPhoneField
            httpMessageField = settings.httpMessageField
            metaPhoneNumberId = settings.metaPhoneNumberId
            metaAccessToken = encryptedMetaToken
            metaWabaId = settings.metaWabaId
            qontakAccessToken = encryptedQontakToken
            qontakChannelIntegrationId = settings.qontakChannelIntegrationId
            notifyOnSubscriptionLifecycle = settings.notifyOnSubscriptionLifecycle
            notifyOnInvoiceReminder = settings.notifyOnInvoiceReminder
            notifyOnWorkOrderSchedule = settings.notifyOnWorkOrderSchedule
            notifyOnIncidentOpen = settings.notifyOnIncidentOpen
        } ?: NotificationSettingsJpaEntity(
            id = settings.id,
            provider = settings.provider,
            gatewayEnabled = settings.gatewayEnabled,
            emailEnabled = settings.emailEnabled,
            httpEndpointUrl = settings.httpEndpointUrl,
            httpToken = encryptedHttpToken,
            httpPhoneField = settings.httpPhoneField,
            httpMessageField = settings.httpMessageField,
            metaPhoneNumberId = settings.metaPhoneNumberId,
            metaAccessToken = encryptedMetaToken,
            metaWabaId = settings.metaWabaId,
            qontakAccessToken = encryptedQontakToken,
            qontakChannelIntegrationId = settings.qontakChannelIntegrationId,
            notifyOnSubscriptionLifecycle = settings.notifyOnSubscriptionLifecycle,
            notifyOnInvoiceReminder = settings.notifyOnInvoiceReminder,
            notifyOnWorkOrderSchedule = settings.notifyOnWorkOrderSchedule,
            notifyOnIncidentOpen = settings.notifyOnIncidentOpen,
        )
        return jpa.save(entity).toDomain()
    }

    private fun NotificationSettingsJpaEntity.toDomain(): NotificationSettings = NotificationSettings.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        provider = provider,
        gatewayEnabled = gatewayEnabled,
        emailEnabled = emailEnabled,
        httpEndpointUrl = httpEndpointUrl,
        httpToken = cipher.decryptQuietly(httpToken, "http_token", log),
        httpPhoneField = httpPhoneField,
        httpMessageField = httpMessageField,
        metaPhoneNumberId = metaPhoneNumberId,
        metaAccessToken = cipher.decryptQuietly(metaAccessToken, "meta_access_token", log),
        metaWabaId = metaWabaId,
        qontakAccessToken = cipher.decryptQuietly(qontakAccessToken, "qontak_access_token", log),
        qontakChannelIntegrationId = qontakChannelIntegrationId,
        notifyOnSubscriptionLifecycle = notifyOnSubscriptionLifecycle,
        notifyOnInvoiceReminder = notifyOnInvoiceReminder,
        notifyOnWorkOrderSchedule = notifyOnWorkOrderSchedule,
        notifyOnIncidentOpen = notifyOnIncidentOpen,
    )
}

/**
 * Token yang tak bisa didekripsi (mis. kunci dirotasi tanpa migrasi) tidak boleh
 * menggagalkan pemuatan setelan; barisnya tetap tampil, hanya kehilangan token dan
 * bisa diisi ulang operator — persis pola secret bng.
 */
private fun SecretCipher.decryptQuietly(ciphertext: String?, label: String, log: Logger): String? {
    if (ciphertext == null) return null
    return runCatching { decrypt(ciphertext) }
        .onFailure { log.warn("Token notifikasi '{}' tidak bisa didekripsi; perlu diisi ulang", label) }
        .getOrNull()
}
