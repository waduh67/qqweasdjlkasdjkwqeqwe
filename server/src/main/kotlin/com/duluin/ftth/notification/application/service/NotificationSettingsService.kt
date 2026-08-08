package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.inbound.ManageNotificationSettingsUseCase
import com.duluin.ftth.notification.application.port.inbound.NotificationSettingsView
import com.duluin.ftth.notification.application.port.inbound.QontakChannelView
import com.duluin.ftth.notification.application.port.inbound.UpdateNotificationSettingsCommand
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.QontakChannelDirectory
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Sisi operator setelan notifikasi. Perubahan dicatat ke jejak audit: menyalakan
 * gateway/pemicu berarti sistem mulai mengirim pesan atas nama tenant ke pelanggan,
 * jadi harus jelas siapa & kapan mengubahnya.
 */
@Service
@Transactional(readOnly = true)
class NotificationSettingsService(
    private val repository: NotificationSettingsRepository,
    private val qontakChannels: QontakChannelDirectory,
    private val auditor: AuditRecorder,
) : ManageNotificationSettingsUseCase {

    override fun get(): NotificationSettingsView =
        (repository.find() ?: NotificationSettings.defaultFor(TenantContext.tenantId())).toView()

    @Transactional
    override fun update(command: UpdateNotificationSettingsCommand): NotificationSettingsView {
        val settings = repository.find() ?: NotificationSettings.defaultFor(TenantContext.tenantId())
        settings.update(
            provider = command.provider,
            gatewayEnabled = command.gatewayEnabled,
            httpEndpointUrl = command.httpEndpointUrl,
            httpToken = command.httpToken,
            httpPhoneField = command.httpPhoneField,
            httpMessageField = command.httpMessageField,
            metaPhoneNumberId = command.metaPhoneNumberId,
            metaAccessToken = command.metaAccessToken,
            metaWabaId = command.metaWabaId,
            qontakAccessToken = command.qontakAccessToken,
            qontakChannelIntegrationId = command.qontakChannelIntegrationId,
            notifyOnSubscriptionLifecycle = command.notifyOnSubscriptionLifecycle,
            notifyOnInvoiceReminder = command.notifyOnInvoiceReminder,
            notifyOnWorkOrderSchedule = command.notifyOnWorkOrderSchedule,
            notifyOnIncidentOpen = command.notifyOnIncidentOpen,
        )
        val saved = repository.save(settings)
        auditor.record(
            action = "notification.settings.updated",
            entityType = "NotificationSettings",
            entityId = saved.id,
            tenantId = saved.tenantId,
        )
        return saved.toView()
    }

    /**
     * Token yang dipakai adalah yang TERSIMPAN, bukan yang sedang diketik: dropdown kanal baru
     * bisa terisi setelah operator menyimpan tokennya. Penyedia lain menghasilkan daftar kosong
     * alih-alih error — dropdown yang tak relevan memang tak perlu berteriak.
     */
    override fun qontakChannels(): List<QontakChannelView> {
        val settings = repository.find() ?: return emptyList()
        if (settings.provider != WhatsAppProvider.QONTAK) return emptyList()
        val token = settings.qontakAccessToken?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return qontakChannels.list(token).map { QontakChannelView(it.id, it.name) }
    }

    private fun NotificationSettings.toView() = NotificationSettingsView(
        provider = provider.name,
        gatewayEnabled = gatewayEnabled,
        httpEndpointUrl = httpEndpointUrl,
        httpTokenSet = !httpToken.isNullOrBlank(),
        httpPhoneField = httpPhoneField,
        httpMessageField = httpMessageField,
        metaPhoneNumberId = metaPhoneNumberId,
        metaAccessTokenSet = !metaAccessToken.isNullOrBlank(),
        metaWabaId = metaWabaId,
        qontakAccessTokenSet = !qontakAccessToken.isNullOrBlank(),
        qontakChannelIntegrationId = qontakChannelIntegrationId,
        templateReady = templateBlockedReason() == null,
        templateBlockedReason = templateBlockedReason(),
        notifyOnSubscriptionLifecycle = notifyOnSubscriptionLifecycle,
        notifyOnInvoiceReminder = notifyOnInvoiceReminder,
        notifyOnWorkOrderSchedule = notifyOnWorkOrderSchedule,
        notifyOnIncidentOpen = notifyOnIncidentOpen,
    )
}
