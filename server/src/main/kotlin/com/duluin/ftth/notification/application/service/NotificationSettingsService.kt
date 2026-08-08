package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.inbound.ManageNotificationSettingsUseCase
import com.duluin.ftth.notification.application.port.inbound.NotificationSettingsView
import com.duluin.ftth.notification.application.port.inbound.UpdateNotificationSettingsCommand
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.domain.model.NotificationSettings
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
        metaTemplateReady = metaTemplateReady(),
        notifyOnSubscriptionLifecycle = notifyOnSubscriptionLifecycle,
        notifyOnInvoiceReminder = notifyOnInvoiceReminder,
        notifyOnWorkOrderSchedule = notifyOnWorkOrderSchedule,
        notifyOnIncidentOpen = notifyOnIncidentOpen,
    )
}
