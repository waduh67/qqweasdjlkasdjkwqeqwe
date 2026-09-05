package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.inbound.ManageNotificationSettingsUseCase
import com.duluin.ftth.notification.application.port.inbound.NotificationSettingsView
import com.duluin.ftth.notification.application.port.inbound.QontakChannelView
import com.duluin.ftth.notification.application.port.inbound.UpdateNotificationSettingsCommand
import com.duluin.ftth.notification.application.port.inbound.WhatsAppTestCommand
import com.duluin.ftth.notification.application.port.inbound.WhatsAppTestResultView
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.QontakChannelDirectory
import com.duluin.ftth.notification.application.port.outbound.MessageDispatcher
import com.duluin.ftth.notification.domain.model.DeliveryStatus
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
    private val dispatcher: MessageDispatcher,
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
            emailEnabled = command.emailEnabled,
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

    @Transactional
    override fun sendWhatsAppTest(command: WhatsAppTestCommand): WhatsAppTestResultView {
        if (command.provider != WhatsAppProvider.FONNTE && command.provider != WhatsAppProvider.HTTP_GENERIC) {
            throw ValidationException("Pengujian langsung hanya tersedia untuk Fonnte dan HTTP Generic.")
        }
        val destination = command.destination.trim()
        if (!INTERNATIONAL_PHONE.matches(destination)) {
            throw ValidationException("Nomor tujuan harus berupa nomor internasional, misalnya 628123456789")
        }
        val message = command.message.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Pesan uji wajib diisi")
        if (message.length > MAX_TEST_MESSAGE) throw ValidationException("Pesan uji maksimal 2000 karakter")

        val settings = repository.find() ?: NotificationSettings.defaultFor(TenantContext.tenantId())
        val gateway = settings.resolveGatewayForTest(
            testProvider = command.provider,
            draftEndpointUrl = command.httpEndpointUrl,
            draftToken = command.httpToken,
            draftPhoneField = command.httpPhoneField,
            draftMessageField = command.httpMessageField,
        ) ?: throw ConflictException(missingTestConfiguration(command.provider))
        val outcome = dispatcher.send(
            gateway = gateway,
            phone = destination,
            recipientName = "Uji WhatsApp",
            message = message,
        )
        val accepted = outcome.status == DeliveryStatus.SENT
        auditor.record(
            action = "notification.settings.whatsapp.tested",
            entityType = "NotificationSettings",
            entityId = settings.id,
            tenantId = settings.tenantId,
            detail = mapOf(
                "provider" to command.provider.name,
                "accepted" to accepted.toString(),
            ),
        )
        val providerLabel = if (command.provider == WhatsAppProvider.FONNTE) "Fonnte" else "Gateway HTTP"
        return WhatsAppTestResultView(
            delivered = accepted,
            detail = if (accepted) {
                "$providerLabel menerima permintaan uji."
            } else {
                "$providerLabel menolak atau gagal menerima permintaan uji."
            },
        )
    }

    private fun NotificationSettings.toView() = NotificationSettingsView(
        provider = provider.name,
        gatewayEnabled = gatewayEnabled,
        emailEnabled = emailEnabled,
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

    private companion object {
        const val MAX_TEST_MESSAGE = 2000
        val INTERNATIONAL_PHONE = Regex("^\\+?[1-9][0-9]{7,14}$")

        fun missingTestConfiguration(provider: WhatsAppProvider): String = when (provider) {
            WhatsAppProvider.FONNTE -> "Pengujian Fonnte membutuhkan token draft atau token Fonnte yang sudah tersimpan."
            WhatsAppProvider.HTTP_GENERIC ->
                "Pengujian HTTP Generic membutuhkan URL endpoint draft atau endpoint yang sudah tersimpan."
            else -> "Pengujian langsung tidak tersedia untuk provider ini."
        }
    }
}
