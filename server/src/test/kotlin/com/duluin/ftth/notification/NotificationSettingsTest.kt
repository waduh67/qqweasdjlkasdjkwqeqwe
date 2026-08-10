package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.notification.domain.model.NotificationChannel
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.TemplateApi
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

    // --- activeChannels ---

    @Test
    fun `kanal aktif mengikuti dua saklar yang berdiri sendiri`() {
        assertThat(channelsWhen(gateway = true, email = false))
            .containsExactly(NotificationChannel.WHATSAPP)
        assertThat(channelsWhen(gateway = false, email = true))
            .containsExactly(NotificationChannel.EMAIL)
        assertThat(channelsWhen(gateway = true, email = true))
            .containsExactly(NotificationChannel.WHATSAPP, NotificationChannel.EMAIL)
    }

    @Test
    fun `tanpa kanal menyala tetap menyisakan WhatsApp demi jejak SKIPPED`() {
        // Daftar kosong akan membuat pemicu yang menyala lenyap dari riwayat; layar setelan
        // justru menjanjikan sebaliknya — tercatat, dengan status SKIPPED.
        assertThat(channelsWhen(gateway = false, email = false))
            .containsExactly(NotificationChannel.WHATSAPP)
    }

    // --- resolveGateway ---

    @Test
    fun `gateway mati mengembalikan null berapa pun providernya`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.LOG, gatewayEnabled = false, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
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
                provider = WhatsAppProvider.LOG, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
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
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = "https://api.fonnte.com/send", httpToken = "rahasia",
                httpPhoneField = "target", httpMessageField = "message",
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
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
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = "rahasia", httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.resolveGateway()).isNull()
    }

    @Test
    fun `Meta Cloud lengkap meresolusi dengan phone-id dan token tanpa template`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.META_CLOUD, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "1234567890", metaAccessToken = "EAAtoken", metaWabaId = "9988",
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        val gateway = settings.resolveGateway()
        assertThat(gateway).isInstanceOf(WhatsAppGateway.MetaCloud::class.java)
        val meta = gateway as WhatsAppGateway.MetaCloud
        assertThat(meta.phoneNumberId).isEqualTo("1234567890")
        assertThat(meta.accessToken).isEqualTo("EAAtoken")
        // Template TAK lagi berasal dari setelan: pilihannya per-pemicu, diisi NotificationSender.
        assertThat(meta.templateName).isNull()
        assertThat(meta.templateLang).isEqualTo(NotificationSettings.DEFAULT_TEMPLATE_LANG)
    }

    @Test
    fun `Qontak lengkap meresolusi dengan token dan kanal tanpa template`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.QONTAK, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = "qontak-token", qontakChannelIntegrationId = "kanal-1",
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        val qontak = settings.resolveGateway() as WhatsAppGateway.Qontak
        assertThat(qontak.accessToken).isEqualTo("qontak-token")
        assertThat(qontak.channelIntegrationId).isEqualTo("kanal-1")
        // Sama seperti Meta: template dipilih per-pemicu oleh NotificationSender, bukan di sini.
        assertThat(qontak.templateId).isNull()
    }

    @Test
    fun `Qontak tanpa kanal meresolusi null walau tokennya ada`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.QONTAK, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = "qontak-token", qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.resolveGateway()).isNull()
    }

    // --- templateBlockedReason / resolveTemplateApi: prasyarat kartu template ---

    @Test
    fun `Meta Cloud lengkap membuka pengelolaan template`() {
        val ready = metaTemplateSettings()

        assertThat(ready.templateBlockedReason()).isNull()
        val api = ready.resolveTemplateApi() as TemplateApi.Meta
        assertThat(api.wabaId).isEqualTo("9988")
        assertThat(api.accessToken).isEqualTo("EAAtoken")
    }

    @Test
    fun `WABA ID wajib untuk template walau tak dibutuhkan saat mengirim`() {
        // Tanpa WABA ID gateway tetap bisa MENGIRIM, tapi seluruh endpoint template
        // beralamat ke WABA — jadi kartu template harus tetap terkunci.
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.META_CLOUD, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "1234567890", metaAccessToken = "EAAtoken", metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.resolveGateway()).isNotNull()
        assertThat(settings.templateBlockedReason()).contains("WhatsApp Business Account ID")
        assertThat(settings.resolveTemplateApi()).isNull()
    }

    @Test
    fun `gateway mati mengunci kartu template walau kredensial lengkap`() {
        val settings = metaTemplateSettings().apply {
            update(
                provider = WhatsAppProvider.META_CLOUD, gatewayEnabled = false, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "1234567890", metaAccessToken = null, metaWabaId = "9988",
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.templateBlockedReason()).contains("nonaktif")
        assertThat(settings.resolveTemplateApi()).isNull()
    }

    @Test
    fun `penyedia tak resmi tak mengenal template sama sekali`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.LOG, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "1234567890", metaAccessToken = "EAAtoken", metaWabaId = "9988",
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.templateBlockedReason()).contains("WhatsApp resmi")
        assertThat(settings.resolveTemplateApi()).isNull()
    }

    @Test
    fun `Qontak butuh token dan kanal sebelum template bisa dikelola`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.QONTAK, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }
        assertThat(settings.templateBlockedReason()).contains("Access token Qontak")

        // Token tersimpan tapi kanal belum dipilih — masih terkunci, dengan alasan yang berbeda.
        settings.update(
            provider = WhatsAppProvider.QONTAK, gatewayEnabled = true, emailEnabled = false,
            httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
            metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
            qontakAccessToken = "qontak-token", qontakChannelIntegrationId = null,
            notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
            notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
        )
        assertThat(settings.templateBlockedReason()).contains("Channel WhatsApp Qontak")

        settings.update(
            provider = WhatsAppProvider.QONTAK, gatewayEnabled = true, emailEnabled = false,
            httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
            metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
            qontakAccessToken = null, qontakChannelIntegrationId = "kanal-1",
            notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
            notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
        )
        assertThat(settings.templateBlockedReason()).isNull()
        val api = settings.resolveTemplateApi() as TemplateApi.Qontak
        assertThat(api.accessToken).isEqualTo("qontak-token")
        assertThat(api.channelIntegrationId).isEqualTo("kanal-1")
    }

    @Test
    fun `Meta Cloud tanpa token meresolusi null`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.META_CLOUD, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "1234567890", metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
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
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = "https://gw.example/send", httpToken = "token-awal",
                httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "111", metaAccessToken = "meta-awal", metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        // Sunting field lain tanpa mengirim token (null) dan token kosong ("  ") — rahasia harus tetap.
        settings.update(
            provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true, emailEnabled = false,
            httpEndpointUrl = "https://gw.example/v2", httpToken = null,
            httpPhoneField = null, httpMessageField = null,
            metaPhoneNumberId = "111", metaAccessToken = "   ", metaWabaId = null,
            qontakAccessToken = null, qontakChannelIntegrationId = null,
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
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = "https://gw.example/send", httpToken = "token-awal",
                httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        settings.update(
            provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true, emailEnabled = false,
            httpEndpointUrl = "https://gw.example/send", httpToken = "token-baru",
            httpPhoneField = null, httpMessageField = null,
            metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
            qontakAccessToken = null, qontakChannelIntegrationId = null,
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
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = "ftp://gw.example/send", httpToken = null,
                httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `field kosong jatuh ke nama bawaan`() {
        val settings = defaultSettings().apply {
            update(
                provider = WhatsAppProvider.HTTP_GENERIC, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = "https://gw.example/send", httpToken = null,
                httpPhoneField = "  ", httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThat(settings.httpPhoneField).isEqualTo(NotificationSettings.DEFAULT_PHONE_FIELD)
        assertThat(settings.httpMessageField).isEqualTo(NotificationSettings.DEFAULT_MESSAGE_FIELD)
    }

    // --- perkakas uji ---

    private fun defaultSettings() = NotificationSettings.defaultFor(UuidV7.generate())

    /** Meta Cloud aktif dengan SELURUH prasyarat template terpenuhi, WABA ID termasuk. */
    private fun metaTemplateSettings() = defaultSettings().apply {
        update(
            provider = WhatsAppProvider.META_CLOUD, gatewayEnabled = true, emailEnabled = false,
            httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
            metaPhoneNumberId = "1234567890", metaAccessToken = "EAAtoken", metaWabaId = "9988",
            qontakAccessToken = null, qontakChannelIntegrationId = null,
            notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
            notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
        )
    }

    /** Kanal aktif setelah menyetel kedua saklar kanal. */
    private fun channelsWhen(gateway: Boolean, email: Boolean) = defaultSettings().apply {
        update(
            provider = WhatsAppProvider.LOG, gatewayEnabled = gateway, emailEnabled = email,
            httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
            metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
            qontakAccessToken = null, qontakChannelIntegrationId = null,
            notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
            notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
        )
    }.activeChannels()

    /** Menyalakan saklar tertentu lewat [NotificationSettings.update], sisanya mati. */
    private fun NotificationSettings.updateToggles(
        subscription: Boolean = false,
        invoice: Boolean = false,
        workOrder: Boolean = false,
        incident: Boolean = false,
    ) = update(
        provider = WhatsAppProvider.LOG, gatewayEnabled = true, emailEnabled = false,
        httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
        metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
        qontakAccessToken = null, qontakChannelIntegrationId = null,
        notifyOnSubscriptionLifecycle = subscription, notifyOnInvoiceReminder = invoice,
        notifyOnWorkOrderSchedule = workOrder, notifyOnIncidentOpen = incident,
    )
}
