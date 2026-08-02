package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.outbound.BroadcastDigest
import com.duluin.ftth.notification.application.port.outbound.BroadcastRepository
import com.duluin.ftth.notification.application.port.outbound.DeliveryOutcome
import com.duluin.ftth.notification.application.port.outbound.MessageDispatcher
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.application.service.NotificationSender
import com.duluin.ftth.notification.application.service.NotificationSender.Recipient
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.notification.domain.model.Broadcast
import com.duluin.ftth.notification.domain.model.DeliveryStatus
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.WhatsAppGateway
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Menguji matriks keputusan [NotificationSender] dengan port palsu — tanpa Spring/DB.
 * Fokus empat cabang: pemicu mati ⇒ null (tak kirim, tak catat); gateway mati ⇒ semua
 * SKIPPED tanpa menyentuh dispatcher; nomor kosong ⇒ SKIPPED; jalur bahagia ⇒ SENT +
 * dispatcher terpanggil. Semua dijalankan di dalam [TenantContext.runAs].
 */
class NotificationSenderTest {

    private val tenantId: UUID = UuidV7.generate()

    @Test
    fun `pemicu dimatikan mengembalikan null tanpa mengirim atau mencatat`() {
        val settings = settingsWith(gatewayEnabled = true, subscription = false)
        val dispatcher = RecordingDispatcher()
        val broadcasts = CapturingBroadcastRepo()
        val sender = NotificationSender(FixedSettingsRepo(settings), broadcasts, dispatcher)

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
        val sender = NotificationSender(FixedSettingsRepo(settings), broadcasts, dispatcher)

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
        val sender = NotificationSender(FixedSettingsRepo(settings), broadcasts, dispatcher)

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
        val sender = NotificationSender(FixedSettingsRepo(null), broadcasts, dispatcher)

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

    // --- perkakas uji ---

    private fun settingsWith(gatewayEnabled: Boolean, subscription: Boolean): NotificationSettings =
        NotificationSettings.defaultFor(tenantId).apply {
            update(
                provider = WhatsAppProvider.LOG, gatewayEnabled = gatewayEnabled,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaTemplateName = null, metaTemplateLang = null,
                notifyOnSubscriptionLifecycle = subscription, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

    private class FixedSettingsRepo(private val settings: NotificationSettings?) : NotificationSettingsRepository {
        override fun find(): NotificationSettings? = settings
        override fun save(settings: NotificationSettings): NotificationSettings = settings
    }

    /** Mengembalikan SENT untuk tiap kirim; mencatat nomor yang benar-benar dikirim. */
    private class RecordingDispatcher : MessageDispatcher {
        val calls = mutableListOf<String>()
        override fun send(gateway: WhatsAppGateway, phone: String, message: String): DeliveryOutcome {
            calls += phone
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
