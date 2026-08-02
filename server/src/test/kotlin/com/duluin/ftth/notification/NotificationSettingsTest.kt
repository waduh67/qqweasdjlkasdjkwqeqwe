package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.WhatsAppGateway
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Menguji dua keputusan inti [NotificationSettings] tanpa Spring/DB: saklar pemicu
 * ([isTriggerEnabled]) dan resolusi gateway ([resolveGateway]) — plus perilaku
 * "token kosong = pertahankan" pada [update]. Murni domain, jadi cepat & deterministik.
 */
class NotificationSettingsTest {

    // --- isTriggerEnabled ---

    @Test
    fun `MANUAL selalu aktif walau semua saklar mati`() {
        val settings = defaultSettings()

        assertThat(settings.isTriggerEnabled(NotificationTrigger.MANUAL)).isTrue()
        // Semua pemicu otomatis mati secara bawaan.
        assertThat(settings.isTriggerEnabled(NotificationTrigger.SUBSCRIPTION_ACTIVATED)).isFalse()
        assertThat(settings.isTriggerEnabled(NotificationTrigger.INVOICE_DUE_SOON)).isFalse()
        assertThat(settings.isTriggerEnabled(NotificationTrigger.WORK_ORDER_SCHEDULED)).isFalse()
        assertThat(settings.isTriggerEnabled(NotificationTrigger.INCIDENT_OPENED)).isFalse()
    }

    @Test
    fun `saklar langganan menguasai ketiga pemicu langganan sekaligus`() {
        val settings = defaultSettings().apply { updateToggles(subscription = true) }

        assertThat(settings.isTriggerEnabled(NotificationTrigger.SUBSCRIPTION_ACTIVATED)).isTrue()
        assertThat(settings.isTriggerEnabled(NotificationTrigger.SUBSCRIPTION_ISOLATED)).isTrue()
        assertThat(settings.isTriggerEnabled(NotificationTrigger.SUBSCRIPTION_TERMINATED)).isTrue()
        // Saklar lain tetap mati — tak bocor.
        assertThat(settings.isTriggerEnabled(NotificationTrigger.INVOICE_DUE_SOON)).isFalse()
    }

    @Test
    fun `saklar tagihan menguasai due-soon dan overdue sekaligus`() {
        val settings = defaultSettings().apply { updateToggles(invoice = true) }

        assertThat(settings.isTriggerEnabled(NotificationTrigger.INVOICE_DUE_SOON)).isTrue()
        assertThat(settings.isTriggerEnabled(NotificationTrigger.INVOICE_OVERDUE)).isTrue()
        assertThat(settings.isTriggerEnabled(NotificationTrigger.WORK_ORDER_SCHEDULED)).isFalse()
    }

    @Test
    fun `saklar WO dan insiden berdiri sendiri`() {
        val wo = defaultSettings().apply { updateToggles(workOrder = true) }
        assertThat(wo.isTriggerEnabled(NotificationTrigger.WORK_ORDER_SCHEDULED)).isTrue()
        assertThat(wo.isTriggerEnabled(NotificationTrigger.INCIDENT_OPENED)).isFalse()

        val incident = defaultSettings().apply { updateToggles(incident = true) }
        assertThat(incident.isTriggerEnabled(NotificationTrigger.INCIDENT_OPENED)).isTrue()
        assertThat(incident.isTriggerEnabled(NotificationTrigger.WORK_ORDER_SCHEDULED)).isFalse()
    }

    // --- resolveGateway ---

    @Test
    fun `gateway mati mengembalikan null berapa pun providernya`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.LOG, gatewayEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.resolveGateway()).isNull()
    }

    @Test
    fun `LOG aktif meresolusi ke gateway Log`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.LOG, gatewayEnabled = true,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.resolveGateway()).isEqualTo(WhatsAppGateway.Log)
    }

    @Test
    fun `HTTP generik lengkap meresolusi dengan field yang disetel`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true,
                httpEndpointUrl = "https://api.fonnte.com/send", httpToken = "rahasia",
                httpPhoneField = "target", httpMessageField = "message",
                metaPhoneNumberId = null, metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        val gateway = settings.resolveGateway()
        assertThat(gateway).isInstanceOf(WhatsAppGateway.HttpGeneric::class.java)
        val http = gateway as WhatsAppGateway.HttpGeneric
        assertThat(http.endpointUrl).isEqualTo("https://api.fonnte.com/send")
        assertThat(http.token).isEqualTo("rahasia")
        assertThat(http.phoneField).isEqualTo("target")
        assertThat(http.messageField).isEqualTo("message")
    }

    @Test
    fun `HTTP generik tanpa URL meresolusi null walau gateway hidup`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true,
                httpEndpointUrl = null, httpToken = "rahasia", httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.resolveGateway()).isNull()
    }

    @Test
    fun `Meta Cloud lengkap meresolusi dengan phone-id token dan template`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.META_CLOUD, gatewayEnabled = true,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "1234567890", metaAccessToken = "EAAtoken",
                metaTemplateName = "tagihan", metaTemplateLang = "id",
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        val gateway = settings.resolveGateway()
        assertThat(gateway).isInstanceOf(WhatsAppGateway.MetaCloud::class.java)
        val meta = gateway as WhatsAppGateway.MetaCloud
        assertThat(meta.phoneNumberId).isEqualTo("1234567890")
        assertThat(meta.accessToken).isEqualTo("EAAtoken")
        assertThat(meta.templateName).isEqualTo("tagihan")
        assertThat(meta.templateLang).isEqualTo("id")
    }

    @Test
    fun `Meta Cloud tanpa token meresolusi null`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.META_CLOUD, gatewayEnabled = true,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "1234567890", metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.resolveGateway()).isNull()
    }

    // --- update: token kosong = pertahankan + validasi URL ---

    @Test
    fun `token kosong atau null saat update mempertahankan yang tersimpan`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true,
                httpEndpointUrl = "https://gw.example/send", httpToken = "token-awal",
                httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "111", metaAccessToken = "meta-awal", metaTemplateName = null, metaTemplateLang = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        // Sunting field lain tanpa mengirim token (null) dan token kosong ("  ") — rahasia harus tetap.
        settings.update(
            provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true,
            httpEndpointUrl = "https://gw.example/v2", httpToken = null,
            httpPhoneField = null, httpMessageField = null,
            metaPhoneNumberId = "111", metaAccessToken = "   ", metaTemplateName = null, metaTemplateLang = null,
            notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
            notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
        )

        assertThat(settings.httpToken).isEqualTo("token-awal")
        assertThat(settings.metaAccessToken).isEqualTo("meta-awal")
        assertThat(settings.httpEndpointUrl).isEqualTo("https://gw.example/v2")
    }

    @Test
    fun `token baru saat update menimpa yang lama`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true,
                httpEndpointUrl = "https://gw.example/send", httpToken = "token-awal",
                httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        settings.update(
            provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true,
            httpEndpointUrl = "https://gw.example/send", httpToken = "token-baru",
            httpPhoneField = null, httpMessageField = null,
            metaPhoneNumberId = null, metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
            notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
            notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
        )

        assertThat(settings.httpToken).isEqualTo("token-baru")
    }

    @Test
    fun `URL endpoint tanpa skema http ditolak`() {
        val settings = defaultSettings()

        assertThatThrownBy {
            settings.update(
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true,
                httpEndpointUrl = "ftp://gw.example/send", httpToken = null,
                httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `field kosong jatuh ke nama bawaan`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true,
                httpEndpointUrl = "https://gw.example/send", httpToken = null,
                httpPhoneField = "  ", httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.httpPhoneField).isEqualTo(NotificationSettings.DEFAULT_PHONE_FIELD)
        assertThat(settings.httpMessageField).isEqualTo(NotificationSettings.DEFAULT_MESSAGE_FIELD)
        assertThat(settings.metaTemplateLang).isEqualTo(NotificationSettings.DEFAULT_TEMPLATE_LANG)
    }

    // --- perkakas uji ---

    private fun defaultSettings() = NotificationSettings.defaultFor(UuidV7.generate())

    /** Menyalakan saklar tertentu lewat [NotificationSettings.update], sisanya mati. */
    private fun NotificationSettings.updateToggles(
        subscription: Boolean = false,
        invoice: Boolean = false,
        workOrder: Boolean = false,
        incident: Boolean = false,
    ) = update(
        provider = WhatsAppProvider.LOG, gatewayEnabled = true,
        httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
        metaPhoneNumberId = null, metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
        notifyOnSubscriptionLifecycle = subscription, notifyOnInvoiceReminder = invoice,
        notifyOnWorkOrderSchedule = workOrder, notifyOnIncidentOpen = incident,
    )
}
