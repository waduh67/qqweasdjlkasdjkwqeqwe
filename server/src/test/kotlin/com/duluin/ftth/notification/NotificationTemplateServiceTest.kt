package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.inbound.ReplaceAssignmentsCommand
import com.duluin.ftth.notification.application.port.inbound.SaveTemplateCommand
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.NotificationTemplateRepository
import com.duluin.ftth.notification.application.port.outbound.RemoteTemplate
import com.duluin.ftth.notification.application.port.outbound.WhatsAppTemplateCatalog
import com.duluin.ftth.notification.application.service.NotificationTemplateService
import com.duluin.ftth.notification.domain.model.NotificationMessageTemplate
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateSource
import com.duluin.ftth.notification.domain.model.TemplateStatus
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * Menguji dua aturan yang menjadi alasan service ini ada, memakai fake in-memory:
 * prasyarat "Meta Cloud hidup + kredensial tersimpan" untuk semua operasi tulis, dan
 * "satu template per pemicu" saat pemetaan diganti. Sync diuji dari sisi penyaringan
 * kategori — hanya UTILITY yang boleh masuk katalog.
 */
class NotificationTemplateServiceTest {

    private val tenantId: UUID = UuidV7.generate()

    private lateinit var templates: FakeTemplateRepo
    private lateinit var settings: FakeSettingsRepo
    private lateinit var catalog: FakeCatalog
    private lateinit var service: NotificationTemplateService

    @BeforeEach
    fun setUp() {
        TenantContext.set(tenantId)
        templates = FakeTemplateRepo()
        settings = FakeSettingsRepo(metaSettings())
        catalog = FakeCatalog()
        service = NotificationTemplateService(templates, settings, catalog, AuditRecorder(ApplicationEventPublisher { }, NoUser))
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    @Test
    fun `menulis ditolak dengan sebab jelas saat prasyarat belum terpenuhi`() {
        settings.row = NotificationSettings.defaultFor(tenantId) // gateway bawaan mati

        assertThatThrownBy { service.create(SaveTemplateCommand("tagihan", "id")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("nonaktif")

        // Membaca tetap boleh: daftar tak boleh hilang cuma karena gateway dimatikan sesaat.
        val view = service.list()
        assertThat(view.manageable).isFalse()
        assertThat(view.blockedReason).isNotNull()
    }

    @Test
    fun `penyedia bukan Meta Cloud memblokir pengelolaan template`() {
        settings.row = NotificationSettings.defaultFor(tenantId).apply {
            update(
                provider = WhatsAppProvider.LOG, gatewayEnabled = true,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "123", metaAccessToken = "tok", metaWabaId = "9988",
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThatThrownBy { service.create(SaveTemplateCommand("tagihan", "id")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("Meta Cloud")
    }

    @Test
    fun `nama dan bahasa yang sama ditolak sebagai duplikat`() {
        service.create(SaveTemplateCommand("tagihan", "id"))

        assertThatThrownBy { service.create(SaveTemplateCommand("TAGIHAN", "id")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("sudah terdaftar")

        // Bahasa berbeda = template Meta yang berbeda → boleh berdampingan.
        val view = service.create(SaveTemplateCommand("tagihan", "en_US"))
        assertThat(view.templates).hasSize(2)
    }

    @Test
    fun `memindahkan pemicu antar template menyisakan tepat satu pemetaan`() {
        val a = service.create(SaveTemplateCommand("tagihan_a", "id")).templates.single().id
        val b = service.create(SaveTemplateCommand("tagihan_b", "id")).templates.first { it.name == "tagihan_b" }.id

        service.replaceAssignments(ReplaceAssignmentsCommand(mapOf(NotificationTrigger.INVOICE_DUE_SOON to a)))
        val moved = service.replaceAssignments(
            ReplaceAssignmentsCommand(mapOf(NotificationTrigger.INVOICE_DUE_SOON to b)),
        )

        assertThat(moved.assignments).containsExactly(entry(NotificationTrigger.INVOICE_DUE_SOON.name, b))
        assertThat(moved.templates.first { it.id == a }.usedBy).isEmpty()
        assertThat(moved.templates.first { it.id == b }.usedBy)
            .containsExactly(NotificationTrigger.INVOICE_DUE_SOON.name)
    }

    @Test
    fun `satu template boleh melayani beberapa pemicu`() {
        val id = service.create(SaveTemplateCommand("tagihan", "id")).templates.single().id

        val view = service.replaceAssignments(
            ReplaceAssignmentsCommand(
                mapOf(
                    NotificationTrigger.INVOICE_DUE_SOON to id,
                    NotificationTrigger.INVOICE_OVERDUE to id,
                ),
            ),
        )

        assertThat(view.assignments).hasSize(2)
        assertThat(view.templates.single().usedBy).hasSize(2)
    }

    @Test
    fun `menghapus template melepas pemetaan pemicunya`() {
        val id = service.create(SaveTemplateCommand("tagihan", "id")).templates.single().id
        service.replaceAssignments(ReplaceAssignmentsCommand(mapOf(NotificationTrigger.INVOICE_DUE_SOON to id)))

        val view = service.delete(id)

        assertThat(view.templates).isEmpty()
        assertThat(view.assignments).isEmpty()
    }

    @Test
    fun `sync hanya mengimpor UTILITY dan memperbarui entri manual yang sudah ada`() {
        service.create(SaveTemplateCommand("tagihan", "id")) // entri manual, status UNKNOWN
        catalog.rows = listOf(
            RemoteTemplate("1", "tagihan", "id", TemplateCategory.UTILITY, TemplateStatus.APPROVED, "Halo {{1}}"),
            RemoteTemplate("2", "promo_lebaran", "id", TemplateCategory.MARKETING, TemplateStatus.APPROVED, "Promo {{1}}"),
            RemoteTemplate("3", "wo_terjadwal", "id", TemplateCategory.UTILITY, TemplateStatus.PENDING, "WO {{1}}"),
        )

        val result = service.sync()

        assertThat(result.fetched).isEqualTo(3)
        assertThat(result.imported).isEqualTo(1)
        assertThat(result.updated).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(1)
        val existing = result.catalog.templates.first { it.name == "tagihan" }
        assertThat(existing.status).isEqualTo(TemplateStatus.APPROVED.name)
        assertThat(existing.source).isEqualTo(TemplateSource.META.name)
        assertThat(existing.bodyParamCount).isEqualTo(1)
        assertThat(result.catalog.templates.map { it.name }).doesNotContain("promo_lebaran")
    }

    @Test
    fun `sync butuh WABA ID walau kredensial lain lengkap`() {
        settings.row = metaSettings(wabaId = null)

        assertThatThrownBy { service.sync() }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("WhatsApp Business Account ID")
        assertThat(service.list().syncable).isFalse()
    }

    // --- perkakas uji ---

    private fun metaSettings(wabaId: String? = "9988"): NotificationSettings =
        NotificationSettings.defaultFor(tenantId).apply {
            update(
                provider = WhatsAppProvider.META_CLOUD, gatewayEnabled = true,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "1234567890", metaAccessToken = "EAAtoken", metaWabaId = wabaId,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = true,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

    private class FakeSettingsRepo(var row: NotificationSettings?) : NotificationSettingsRepository {
        override fun find(): NotificationSettings? = row
        override fun save(settings: NotificationSettings): NotificationSettings = settings.also { row = it }
    }

    private class FakeCatalog : WhatsAppTemplateCatalog {
        var rows: List<RemoteTemplate> = emptyList()
        override fun list(wabaId: String, accessToken: String): List<RemoteTemplate> = rows
    }

    /**
     * Meniru perilaku DB yang penting bagi service: `replaceAssignments` menulis ulang
     * seluruh peta, dan menghapus template ikut menghapus pemetaannya (ON DELETE CASCADE).
     */
    private class FakeTemplateRepo : NotificationTemplateRepository {
        private val rows = linkedMapOf<UUID, NotificationMessageTemplate>()
        private val map = linkedMapOf<NotificationTrigger, UUID>()

        override fun findAll(): List<NotificationMessageTemplate> = rows.values.toList()
        override fun findById(id: UUID): NotificationMessageTemplate? = rows[id]
        override fun findByNameAndLanguage(name: String, language: String): NotificationMessageTemplate? =
            rows.values.firstOrNull { it.name == name && it.language == language }

        override fun save(template: NotificationMessageTemplate): NotificationMessageTemplate =
            template.also { rows[it.id] = it }

        override fun delete(id: UUID) {
            rows.remove(id)
            map.entries.removeIf { it.value == id }
        }

        override fun assignments(): Map<NotificationTrigger, UUID> = map.toMap()

        override fun replaceAssignments(assignments: Map<NotificationTrigger, UUID>) {
            map.clear()
            map.putAll(assignments)
        }

        override fun findForTrigger(trigger: NotificationTrigger): NotificationMessageTemplate? =
            map[trigger]?.let { rows[it] }
    }

    private object NoUser : CurrentUserProvider {
        override fun currentOrNull(): AuthenticatedUser? = null
    }
}
