package com.duluin.ftth.notification

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.inbound.WhatsAppTestCommand
import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.MessageDispatcher
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.QontakChannel
import com.duluin.ftth.notification.application.port.outbound.QontakChannelDirectory
import com.duluin.ftth.notification.application.service.NotificationSettingsService
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.WhatsAppGateway
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

class NotificationSettingsServiceTest {
    private val tenantId = UUID.randomUUID()

    @Test
    fun `draft token Fonnte dipakai tanpa menyimpan atau mengaktifkan gateway`() = asTenant {
        val repository = RecordingRepository()
        val dispatcher = RecordingDispatcher()
        val events = mutableListOf<AuditTrailEvent>()
        val service = service(repository, dispatcher, events)

        val result = service.sendWhatsAppTest(
            command(provider = WhatsAppProvider.FONNTE, httpToken = "draft-token"),
        )

        assertThat(dispatcher.gateway).isEqualTo(WhatsAppGateway.Fonnte("draft-token"))
        assertThat(dispatcher.phone).isEqualTo("628123456789")
        assertThat(dispatcher.message).isEqualTo("Pesan uji yang diedit")
        assertThat(repository.saved).isNull()
        assertThat(result.delivered).isTrue()
        assertThat(events.single().detail).isEqualTo(
            mapOf("provider" to "FONNTE", "accepted" to "true"),
        )
        assertThat(events.single().toString())
            .doesNotContain("draft-token", "628123456789", "Pesan uji yang diedit")
    }

    @Test
    fun `draft token kosong jatuh ke token tersimpan untuk provider yang sama`() = asTenant {
        val repository = RecordingRepository(settings(WhatsAppProvider.FONNTE, token = "stored-token"))
        val dispatcher = RecordingDispatcher()

        service(repository, dispatcher).sendWhatsAppTest(command(provider = WhatsAppProvider.FONNTE))

        assertThat(dispatcher.gateway).isEqualTo(WhatsAppGateway.Fonnte("stored-token"))
    }

    @Test
    fun `token provider lain tidak pernah dipakai sebagai fallback Fonnte`() = asTenant {
        val repository = RecordingRepository(
            settings(WhatsAppProvider.HTTP_GENERIC, token = "generic-secret", endpoint = "https://gateway.test/send"),
        )

        assertThatThrownBy {
            service(repository).sendWhatsAppTest(command(provider = WhatsAppProvider.FONNTE))
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `HTTP Generic memakai endpoint field dan token draft`() = asTenant {
        val dispatcher = RecordingDispatcher()
        val service = service(RecordingRepository(), dispatcher)

        service.sendWhatsAppTest(
            command(
                provider = WhatsAppProvider.HTTP_GENERIC,
                httpToken = "Bearer draft",
                httpEndpointUrl = "https://gateway.test/draft",
                httpPhoneField = "phone",
                httpMessageField = "text",
            ),
        )

        assertThat(dispatcher.gateway).isEqualTo(
            WhatsAppGateway.HttpGeneric(
                endpointUrl = "https://gateway.test/draft",
                token = "Bearer draft",
                phoneField = "phone",
                messageField = "text",
            ),
        )
    }

    @Test
    fun `HTTP Generic memakai konfigurasi tersimpan saat draft kosong`() = asTenant {
        val repository = RecordingRepository(
            settings(WhatsAppProvider.HTTP_GENERIC, token = "stored-token", endpoint = "https://gateway.test/stored"),
        )
        val dispatcher = RecordingDispatcher()

        service(repository, dispatcher).sendWhatsAppTest(command(provider = WhatsAppProvider.HTTP_GENERIC))

        assertThat(dispatcher.gateway).isEqualTo(
            WhatsAppGateway.HttpGeneric(
                endpointUrl = "https://gateway.test/stored",
                token = "stored-token",
                phoneField = NotificationSettings.DEFAULT_PHONE_FIELD,
                messageField = NotificationSettings.DEFAULT_MESSAGE_FIELD,
            ),
        )
    }

    @Test
    fun `provider resmi ditolak dan detail dispatcher tidak dibocorkan`() = asTenant {
        val dispatcher = RecordingDispatcher(
            DeliveryOutcome(DeliveryStatus.FAILED, "token=super-secret message=rahasia https://gateway.test/send"),
        )
        val service = service(RecordingRepository(), dispatcher)

        assertThatThrownBy {
            service.sendWhatsAppTest(command(provider = WhatsAppProvider.META_CLOUD, httpToken = "draft-token"))
        }.isInstanceOf(ValidationException::class.java)

        val result = service.sendWhatsAppTest(
            command(provider = WhatsAppProvider.FONNTE, httpToken = "super-secret", message = "rahasia"),
        )
        assertThat(result.delivered).isFalse()
        assertThat(result.detail).doesNotContain("super-secret", "rahasia", "gateway.test")
    }

    private fun service(
        repository: RecordingRepository,
        dispatcher: RecordingDispatcher = RecordingDispatcher(),
        events: MutableList<AuditTrailEvent> = mutableListOf(),
    ): NotificationSettingsService = NotificationSettingsService(
        repository = repository,
        qontakChannels = object : QontakChannelDirectory {
            override fun list(accessToken: String): List<QontakChannel> = emptyList()
        },
        dispatcher = dispatcher,
        auditor = AuditRecorder(
            ApplicationEventPublisher { event -> if (event is AuditTrailEvent) events += event },
            object : CurrentUserProvider {
                override fun currentOrNull() = null
            },
        ),
    )

    private fun command(
        provider: WhatsAppProvider,
        httpToken: String? = null,
        httpEndpointUrl: String? = null,
        httpPhoneField: String? = null,
        httpMessageField: String? = null,
        message: String = "Pesan uji yang diedit",
    ) = WhatsAppTestCommand(
        provider = provider,
        destination = "628123456789",
        message = message,
        httpToken = httpToken,
        httpEndpointUrl = httpEndpointUrl,
        httpPhoneField = httpPhoneField,
        httpMessageField = httpMessageField,
    )

    private fun settings(
        provider: WhatsAppProvider,
        token: String?,
        endpoint: String? = null,
    ): NotificationSettings = NotificationSettings.defaultFor(tenantId).apply {
        update(
            provider = provider,
            gatewayEnabled = false,
            emailEnabled = false,
            httpEndpointUrl = endpoint,
            httpToken = token,
            httpPhoneField = null,
            httpMessageField = null,
            metaPhoneNumberId = null,
            metaAccessToken = null,
            metaWabaId = null,
            qontakAccessToken = null,
            qontakChannelIntegrationId = null,
            notifyOnSubscriptionLifecycle = false,
            notifyOnInvoiceReminder = false,
            notifyOnWorkOrderSchedule = false,
            notifyOnIncidentOpen = false,
        )
    }

    private fun asTenant(block: () -> Unit): Unit = TenantContext.runAs(tenantId) { block() }

    private class RecordingRepository(initial: NotificationSettings? = null) : NotificationSettingsRepository {
        private var current = initial
        var saved: NotificationSettings? = null
            private set

        override fun find(): NotificationSettings? = current

        override fun save(settings: NotificationSettings): NotificationSettings {
            saved = settings
            current = settings
            return settings
        }
    }

    private class RecordingDispatcher(
        private val outcome: DeliveryOutcome = DeliveryOutcome(DeliveryStatus.SENT, "provider detail"),
    ) : MessageDispatcher {
        var gateway: WhatsAppGateway? = null
            private set
        var phone: String? = null
            private set
        var message: String? = null
            private set

        override fun send(
            gateway: WhatsAppGateway,
            phone: String,
            recipientName: String,
            message: String,
        ): DeliveryOutcome {
            this.gateway = gateway
            this.phone = phone
            this.message = message
            return outcome
        }
    }
}
