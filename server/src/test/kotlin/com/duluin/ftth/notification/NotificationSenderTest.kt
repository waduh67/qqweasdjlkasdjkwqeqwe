package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.outbound.BroadcastDigest
import com.duluin.ftth.notification.application.port.outbound.BroadcastRepository
import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.MessageDispatcher
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.NotificationTemplateRepository
import com.duluin.ftth.notification.application.service.NotificationSender
import com.duluin.ftth.notification.application.service.NotificationSender.Recipient
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.notification.domain.model.Broadcast
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.NotificationMessageTemplate
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateStatus
import com.duluin.ftth.notification.domain.model.WhatsAppGateway
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Menguji matriks keputusan [NotificationSender] dengan port palsu — tanpa Spring/DB.
 * Fokus cabang-cabangnya: pemicu mati ⇒ null (tak kirim, tak catat); gateway mati ⇒ semua
 * SKIPPED tanpa menyentuh dispatcher; nomor kosong ⇒ SKIPPED; jalur bahagia ⇒ SENT +
 * dispatcher terpanggil; plus pemilihan template per pemicu (terpetakan ⇒ nama template
 * ikut, tak terpetakan ⇒ teks biasa). Semua dijalankan di dalam [TenantContext.runAs].
 */
class NotificationSenderTest {

    private val tenantId: UUID = UuidV7.generate()

    /** Isi BODY yang sah: tepat satu `{{1}}`, diisi seluruh pesan rakitan saat kirim. */
    private val body = "Halo, {{1}}"

    @Test
    fun `pemicu dimatikan mengembalikan null tanpa mengirim atau mencatat`() {
        val settings = settingsWith(gatewayEnabled = true, subscription = false)
        val dispatcher = RecordingDispatcher()
        val broadcasts = CapturingBroadcastRepo()
        val sender = NotificationSender(FixedSettingsRepo(settings), broadcasts, FakeTemplateRepo(), dispatcher)

        val result = TenantContext.runAs(tenantId) {
            sender.dispatch(
                NotificationTrigger.SUBSCRIPTION_ACTIVATED,
                "pesan",
                listOf(Recipient(UuidV7.generate(), "Budi", "628111")),
            )
        }

        assertThat(result).isNull()
        assertThat(dispatcher.calls).isEmpty()
        assertThat(broadcasts.saved).isNull()
    }

    @Test
    fun `gateway mati mencatat semua penerima SKIPPED tanpa menyentuh dispatcher`() {
        val settings = settingsWith(gatewayEnabled = false, subscription = true)
        val dispatcher = RecordingDispatcher()
        val broadcasts = CapturingBroadcastRepo()
        val sender = NotificationSender(FixedSettingsRepo(settings), broadcasts, FakeTemplateRepo(), dispatcher)

        val result = TenantContext.runAs(tenantId) {
            sender.dispatch(
                NotificationTrigger.SUBSCRIPTION_ACTIVATED,
                "pesan",
                listOf(Recipient(UuidV7.generate(), "Budi", "628111")),
            )
        }

        assertThat(result).isNotNull()
        assertThat(dispatcher.calls).isEmpty()
        assertThat(broadcasts.saved).isNotNull()
        assertThat(broadcasts.saved!!.skippedCount).isEqualTo(1)
        assertThat(broadcasts.saved!!.sentCount).isZero()
        assertThat(broadcasts.saved!!.recipients.single().detail).isEqualTo("Gateway WA nonaktif")
    }

    @Test
    fun `nomor kosong SKIPPED sedang nomor terisi SENT`() {
        val settings = settingsWith(gatewayEnabled = true, subscription = true)
        val dispatcher = RecordingDispatcher()
        val broadcasts = CapturingBroadcastRepo()
        val sender = NotificationSender(FixedSettingsRepo(settings), broadcasts, FakeTemplateRepo(), dispatcher)

        val result = TenantContext.runAs(tenantId) {
            sender.dispatch(
                NotificationTrigger.SUBSCRIPTION_ACTIVATED,
                "pesan",
                listOf(
                    Recipient(UuidV7.generate(), "Budi", "628111"),
                    Recipient(UuidV7.generate(), "Tanpa Nomor", null),
                    Recipient(UuidV7.generate(), "Kosong", "   "),
                ),
            )
        }

        assertThat(result).isNotNull()
        // Hanya penerima bernomor yang sampai ke dispatcher.
        assertThat(dispatcher.calls).containsExactly("628111")
        assertThat(broadcasts.saved!!.sentCount).isEqualTo(1)
        assertThat(broadcasts.saved!!.skippedCount).isEqualTo(2)
    }

    @Test
    fun `MANUAL tetap terkirim walau setelan belum pernah disetel`() {
        // find() null → NotificationSender jatuh ke defaultFor: MANUAL selalu aktif,
        // tapi gateway bawaan mati → penerima SKIPPED (bukan null, bukan SENT).
        val dispatcher = RecordingDispatcher()
        val broadcasts = CapturingBroadcastRepo()
        val sender = NotificationSender(FixedSettingsRepo(null), broadcasts, FakeTemplateRepo(), dispatcher)

        val result = TenantContext.runAs(tenantId) {
            sender.dispatch(
                NotificationTrigger.MANUAL,
                "pesan",
                listOf(Recipient(UuidV7.generate(), "Budi", "628111")),
            )
        }

        assertThat(result).isNotNull()
        assertThat(dispatcher.calls).isEmpty()
        assertThat(broadcasts.saved!!.skippedCount).isEqualTo(1)
    }

    @Test
    fun `pemicu terpetakan memakai template yang ditunjuk`() {
        val dispatcher = RecordingDispatcher()
        val broadcasts = CapturingBroadcastRepo()
        val template = template("tagihan_jatuh_tempo", "en_US")
        val templates = FakeTemplateRepo(mapOf(NotificationTrigger.INVOICE_DUE_SOON to template))
        val sender = NotificationSender(FixedSettingsRepo(metaSettings()), broadcasts, templates, dispatcher)

        TenantContext.runAs(tenantId) {
            sender.dispatch(
                NotificationTrigger.INVOICE_DUE_SOON,
                "pesan",
                listOf(Recipient(UuidV7.generate(), "Budi", "628111")),
            )
        }

        val meta = dispatcher.gateways.single() as WhatsAppGateway.MetaCloud
        assertThat(meta.templateName).isEqualTo("tagihan_jatuh_tempo")
        assertThat(meta.templateLang).isEqualTo("en_US")
    }

    @Test
    fun `pemicu tanpa pemetaan terkirim sebagai teks biasa`() {
        val dispatcher = RecordingDispatcher()
        val broadcasts = CapturingBroadcastRepo()
        // Template ada untuk pemicu LAIN — pemicu yang dikirim tetap tak terpetakan.
        val other = template("tagihan_menunggak", "id")
        val templates = FakeTemplateRepo(mapOf(NotificationTrigger.INVOICE_OVERDUE to other))
        val sender = NotificationSender(FixedSettingsRepo(metaSettings()), broadcasts, templates, dispatcher)

        TenantContext.runAs(tenantId) {
            sender.dispatch(
                NotificationTrigger.INVOICE_DUE_SOON,
                "pesan",
                listOf(Recipient(UuidV7.generate(), "Budi", "628111")),
            )
        }

        val meta = dispatcher.gateways.single() as WhatsAppGateway.MetaCloud
        assertThat(meta.templateName).isNull()
        assertThat(broadcasts.saved!!.sentCount).isEqualTo(1)
    }

    @Test
    fun `Qontak memakai id template penyedia bukan namanya`() {
        val dispatcher = RecordingDispatcher()
        val broadcasts = CapturingBroadcastRepo()
        val template = template("tagihan_jatuh_tempo", "en_US", remoteId = "8f2c-uuid")
        val templates = FakeTemplateRepo(mapOf(NotificationTrigger.INVOICE_DUE_SOON to template))
        val sender = NotificationSender(FixedSettingsRepo(qontakSettings()), broadcasts, templates, dispatcher)

        TenantContext.runAs(tenantId) {
            sender.dispatch(
                NotificationTrigger.INVOICE_DUE_SOON,
                "pesan",
                listOf(Recipient(UuidV7.generate(), "Budi", "628111")),
            )
        }

        val qontak = dispatcher.gateways.single() as WhatsAppGateway.Qontak
        assertThat(qontak.templateId).isEqualTo("8f2c-uuid")
        assertThat(qontak.templateLang).isEqualTo("en_US")
    }

    @Test
    fun `Qontak tanpa id penyedia tak dipaksakan memakai template`() {
        val dispatcher = RecordingDispatcher()
        val broadcasts = CapturingBroadcastRepo()
        // Baris cermin yang pengajuannya belum dijawab penyedia: tak ada id untuk dirujuk.
        val templates = FakeTemplateRepo(
            mapOf(NotificationTrigger.INVOICE_DUE_SOON to template("tagihan_jatuh_tempo", "id")),
        )
        val sender = NotificationSender(FixedSettingsRepo(qontakSettings()), broadcasts, templates, dispatcher)

        TenantContext.runAs(tenantId) {
            sender.dispatch(
                NotificationTrigger.INVOICE_DUE_SOON,
                "pesan",
                listOf(Recipient(UuidV7.generate(), "Budi", "628111")),
            )
        }

        // Dispatcher-lah yang melapor SKIPPED; sender tak menebak-nebak id yang tak ada.
        assertThat((dispatcher.gateways.single() as WhatsAppGateway.Qontak).templateId).isNull()
    }

    // --- perkakas uji ---

    /** Template siap-pakai; [remoteId] non-null berarti penyedia sudah menjawab pengajuannya. */
    private fun template(name: String, language: String, remoteId: String? = null): NotificationMessageTemplate =
        NotificationMessageTemplate.draft(tenantId, name, language, TemplateCategory.UTILITY, body).also { t ->
            remoteId?.let {
                t.applyRemote(it, TemplateCategory.UTILITY, TemplateStatus.APPROVED, body, Instant.EPOCH)
            }
        }

    private fun settingsWith(gatewayEnabled: Boolean, subscription: Boolean): NotificationSettings =
        NotificationSettings.defaultFor(tenantId).apply {
            update(
                provider = WhatsAppProvider.LOG, gatewayEnabled = gatewayEnabled,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = subscription, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

    /** Meta Cloud aktif dengan pemicu tagihan menyala — dasar uji pemetaan template. */
    private fun metaSettings(): NotificationSettings =
        NotificationSettings.defaultFor(tenantId).apply {
            update(
                provider = WhatsAppProvider.META_CLOUD, gatewayEnabled = true,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "1234567890", metaAccessToken = "EAAtoken", metaWabaId = "9988",
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = true,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

    /** Cermin [metaSettings] untuk penyedia Mekari Qontak. */
    private fun qontakSettings(): NotificationSettings =
        NotificationSettings.defaultFor(tenantId).apply {
            update(
                provider = WhatsAppProvider.QONTAK, gatewayEnabled = true,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = "qontak-token", qontakChannelIntegrationId = "kanal-1",
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = true,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

    private class FixedSettingsRepo(private val settings: NotificationSettings?) : NotificationSettingsRepository {
        override fun find(): NotificationSettings? = settings
        override fun save(settings: NotificationSettings): NotificationSettings = settings
    }

    /** Hanya [findForTrigger] yang dipakai jalur kirim; sisanya bukan urusan sender. */
    private class FakeTemplateRepo(
        private val byTrigger: Map<NotificationTrigger, NotificationMessageTemplate> = emptyMap(),
    ) : NotificationTemplateRepository {
        override fun findForTrigger(trigger: NotificationTrigger): NotificationMessageTemplate? = byTrigger[trigger]

        override fun findAll(): List<NotificationMessageTemplate> = throw UnsupportedOperationException()
        override fun findById(id: UUID): NotificationMessageTemplate? = throw UnsupportedOperationException()
        override fun findByNameAndLanguage(name: String, language: String): NotificationMessageTemplate? =
            throw UnsupportedOperationException()

        override fun save(template: NotificationMessageTemplate): NotificationMessageTemplate =
            throw UnsupportedOperationException()

        override fun delete(id: UUID) = throw UnsupportedOperationException()
        override fun assignments(): Map<NotificationTrigger, UUID> = throw UnsupportedOperationException()
        override fun replaceAssignments(assignments: Map<NotificationTrigger, UUID>) =
            throw UnsupportedOperationException()
    }

    /** Mengembalikan SENT untuk tiap kirim; mencatat nomor & gateway yang benar-benar dipakai. */
    private class RecordingDispatcher : MessageDispatcher {
        val calls = mutableListOf<String>()
        val gateways = mutableListOf<WhatsAppGateway>()
        override fun send(
            gateway: WhatsAppGateway,
            phone: String,
            recipientName: String,
            message: String,
        ): DeliveryOutcome {
            calls += phone
            gateways += gateway
            return DeliveryOutcome(DeliveryStatus.SENT, "ok")
        }
    }

    private class CapturingBroadcastRepo : BroadcastRepository {
        var saved: Broadcast? = null
        override fun save(broadcast: Broadcast): Broadcast {
            saved = broadcast
            return broadcast
        }

        override fun findById(id: UUID): Broadcast? = throw UnsupportedOperationException()
        override fun recent(request: PageRequest): Page<BroadcastDigest> = throw UnsupportedOperationException()
    }
}
