package com.duluin.ftth.notification

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.inbound.EditTemplateCommand
import com.duluin.ftth.notification.application.port.inbound.ReplaceAssignmentsCommand
import com.duluin.ftth.notification.application.port.inbound.SaveTemplateCommand
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.NotificationTemplateRepository
import com.duluin.ftth.notification.application.port.outbound.RemoteTemplate
import com.duluin.ftth.notification.application.port.outbound.TemplateDraft
import com.duluin.ftth.notification.application.port.outbound.WhatsAppTemplateCatalog
import com.duluin.ftth.notification.application.service.NotificationTemplateService
import com.duluin.ftth.notification.domain.model.NotificationMessageTemplate
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.TemplateApi
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
 * Menguji tiga aturan yang menjadi alasan service ini ada, memakai fake in-memory:
 *
 *  1. PRASYARAT — semua operasi tulis butuh gateway WhatsApp resmi yang hidup & berkredensial.
 *  2. CERMIN — penyedia dipanggil lebih dulu; kalau penyedia menolak, tak ada baris lokal yang
 *     tertinggal, dan kemampuan penyedia (`canEdit`/`canDeleteRemotely`) menentukan apa yang
 *     boleh dilakukan operator.
 *  3. SATU TEMPLATE PER PEMICU — lewat penulisan-ulang seluruh peta pemetaan.
 *
 * Dua fake katalog didaftarkan sekaligus (Meta & Qontak) supaya berganti penyedia cukup dengan
 * menukar baris setelan — persis seperti registry `provider → catalog` di produksi.
 */
class NotificationTemplateServiceTest {

    private val tenantId: UUID = UuidV7.generate()

    private lateinit var templates: FakeTemplateRepo
    private lateinit var settings: FakeSettingsRepo
    private lateinit var meta: FakeCatalog
    private lateinit var qontak: FakeCatalog
    private lateinit var service: NotificationTemplateService

    @BeforeEach
    fun setUp() {
        TenantContext.set(tenantId)
        templates = FakeTemplateRepo()
        settings = FakeSettingsRepo(metaSettings())
        meta = FakeCatalog(WhatsAppProvider.META_CLOUD, "Meta Cloud", canEdit = true, canDeleteRemotely = true)
        qontak = FakeCatalog(WhatsAppProvider.QONTAK, "Mekari Qontak", canEdit = false, canDeleteRemotely = false)
        service = NotificationTemplateService(
            templates,
            settings,
            listOf(meta, qontak),
            AuditRecorder(ApplicationEventPublisher { }, NoUser),
        )
    }

    @AfterEach
    fun tearDown() = TenantContext.clear()

    // --- prasyarat ---

    @Test
    fun `menulis ditolak dengan sebab jelas saat prasyarat belum terpenuhi`() {
        settings.row = NotificationSettings.defaultFor(tenantId) // gateway bawaan mati

        assertThatThrownBy { service.create(command("tagihan")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("nonaktif")

        // Membaca tetap boleh: daftar tak boleh hilang cuma karena gateway dimatikan sesaat.
        val view = service.list()
        assertThat(view.manageable).isFalse()
        assertThat(view.blockedReason).isNotNull()
    }

    @Test
    fun `penyedia tak resmi memblokir pengelolaan template`() {
        settings.row = NotificationSettings.defaultFor(tenantId).apply {
            update(
                provider = WhatsAppProvider.LOG, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "123", metaAccessToken = "tok", metaWabaId = "9988",
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = false,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

        assertThatThrownBy { service.create(command("tagihan")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("WhatsApp resmi")
    }

    @Test
    fun `sync butuh WABA ID walau kredensial lain lengkap`() {
        settings.row = metaSettings(wabaId = null)

        assertThatThrownBy { service.sync() }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("WhatsApp Business Account ID")
        assertThat(service.list().syncable).isFalse()
    }

    // --- create: katalog lokal sebagai cermin ---

    @Test
    fun `create mengajukan ke penyedia dan menyimpan id serta status jawabannya`() {
        val view = service.create(command("tagihan", body = "Halo, {{1}}"))

        assertThat(meta.created.single().name).isEqualTo("tagihan")
        assertThat(meta.created.single().bodyText).isEqualTo("Halo, {{1}}")
        val row = view.templates.single()
        assertThat(row.status).isEqualTo(TemplateStatus.PENDING.name)
        assertThat(row.source).isEqualTo(TemplateSource.REMOTE.name)
        assertThat(row.bodyParamCount).isEqualTo(1)
        assertThat(templates.findById(row.id)!!.remoteId).isEqualTo("remote-1")
    }

    @Test
    fun `penyedia menolak create maka tak ada baris lokal yang tertinggal`() {
        meta.failCreate = "Meta Cloud menolak (400): template name already exists"

        assertThatThrownBy { service.create(command("tagihan")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("template name already exists")

        // Inilah yang membuat katalog jadi cermin, bukan catatan terpisah.
        assertThat(templates.findAll()).isEmpty()
    }

    @Test
    fun `nama dan bahasa yang sama ditolak sebagai duplikat sebelum penyedia dipanggil`() {
        service.create(command("tagihan"))

        assertThatThrownBy { service.create(command("TAGIHAN")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("sudah terdaftar")
        // Penyedia tak ikut dipanggil: template kembar di Meta tak bisa ditarik balik.
        assertThat(meta.created).hasSize(1)

        // Bahasa berbeda = template penyedia yang berbeda → boleh berdampingan.
        val view = service.create(command("tagihan", language = "en_US"))
        assertThat(view.templates).hasSize(2)
    }

    // --- update & delete: kemampuan penyedia menentukan ---

    @Test
    fun `update menyunting di penyedia lalu memakai status jawabannya`() {
        val id = service.create(command("tagihan")).templates.single().id

        val view = service.update(id, EditTemplateCommand(TemplateCategory.UTILITY, "Versi baru {{1}}"))

        assertThat(meta.edited.single().first).isEqualTo("remote-1")
        assertThat(meta.edited.single().second.bodyText).isEqualTo("Versi baru {{1}}")
        assertThat(view.templates.single().bodyText).isEqualTo("Versi baru {{1}}")
    }

    @Test
    fun `penyedia tanpa kemampuan sunting menolak update dengan jalan keluarnya`() {
        settings.row = qontakSettings()
        val id = service.create(command("tagihan")).templates.single().id

        assertThatThrownBy { service.update(id, EditTemplateCommand(TemplateCategory.UTILITY, "Versi baru {{1}}")) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("hapus template ini lalu buat yang baru")
        assertThat(qontak.edited).isEmpty()
    }

    @Test
    fun `delete di Meta membuang baris lokal dan template di penyedia`() {
        val id = service.create(command("tagihan")).templates.single().id
        service.replaceAssignments(ReplaceAssignmentsCommand(mapOf(NotificationTrigger.INVOICE_DUE_SOON to id)))

        val result = service.delete(id)

        assertThat(result.removedRemotely).isTrue()
        assertThat(meta.deleted).containsExactly("remote-1")
        assertThat(result.catalog.templates).isEmpty()
        // Pemetaan pemicu ikut lepas (ON DELETE CASCADE di DB, dipalsukan di repo uji).
        assertThat(result.catalog.assignments).isEmpty()
    }

    @Test
    fun `delete di Qontak hanya membuang baris lokal dan mengatakannya apa adanya`() {
        settings.row = qontakSettings()
        val id = service.create(command("tagihan")).templates.single().id

        val result = service.delete(id)

        assertThat(result.removedRemotely).isFalse()
        assertThat(qontak.deleted).isEmpty()
        assertThat(result.message).contains("daftar aplikasi saja")
        assertThat(result.message).contains("Mekari Qontak")
        assertThat(result.catalog.templates).isEmpty()
    }

    @Test
    fun `katalog mengumumkan kemampuan penyedia agar UI tak menawarkan tombol yang mustahil`() {
        assertThat(service.list().canEdit).isTrue()
        assertThat(service.list().canDeleteRemotely).isTrue()
        assertThat(service.list().requiresTemplateForEveryTrigger).isFalse()

        settings.row = qontakSettings()

        val view = service.list()
        assertThat(view.providerLabel).isEqualTo("Mekari Qontak")
        assertThat(view.canEdit).isFalse()
        assertThat(view.canDeleteRemotely).isFalse()
        // Qontak tak punya jalur teks biasa: pemicu tanpa template akan dilewati.
        assertThat(view.requiresTemplateForEveryTrigger).isTrue()
    }

    // --- pemetaan pemicu ---

    @Test
    fun `memindahkan pemicu antar template menyisakan tepat satu pemetaan`() {
        val a = service.create(command("tagihan_a")).templates.single().id
        val b = service.create(command("tagihan_b")).templates.first { it.name == "tagihan_b" }.id

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
        val id = service.create(command("tagihan")).templates.single().id

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

    // --- sync ---

    @Test
    fun `sync hanya mengimpor UTILITY dan memperbarui entri yang sudah ada`() {
        service.create(command("tagihan"))
        meta.rows = listOf(
            RemoteTemplate("1", "tagihan", "id", TemplateCategory.UTILITY, TemplateStatus.APPROVED, "Halo {{1}}"),
            RemoteTemplate("2", "promo", "id", TemplateCategory.MARKETING, TemplateStatus.APPROVED, "Promo {{1}}"),
            RemoteTemplate("3", "wo_terjadwal", "id", TemplateCategory.UTILITY, TemplateStatus.PENDING, "WO {{1}}"),
        )

        val result = service.sync()

        assertThat(result.fetched).isEqualTo(3)
        assertThat(result.imported).isEqualTo(1)
        assertThat(result.updated).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(1)
        assertThat(result.missing).isZero()
        val existing = result.catalog.templates.first { it.name == "tagihan" }
        assertThat(existing.status).isEqualTo(TemplateStatus.APPROVED.name)
        assertThat(existing.source).isEqualTo(TemplateSource.REMOTE.name)
        assertThat(existing.bodyParamCount).isEqualTo(1)
        assertThat(result.catalog.templates.map { it.name }).doesNotContain("promo")
    }

    @Test
    fun `baris yang lenyap di penyedia dinonaktifkan bukan dihapus`() {
        val id = service.create(command("tagihan")).templates.single().id
        service.replaceAssignments(ReplaceAssignmentsCommand(mapOf(NotificationTrigger.INVOICE_DUE_SOON to id)))
        meta.rows = emptyList() // template dihapus lewat dasbor Meta, di luar aplikasi

        val result = service.sync()

        assertThat(result.missing).isEqualTo(1)
        assertThat(result.message).contains("tak ditemukan lagi")
        val row = result.catalog.templates.single()
        assertThat(row.status).isEqualTo(TemplateStatus.DISABLED.name)
        // Pemetaan pemicunya dipertahankan: operator yang memutuskan, bukan sync diam-diam.
        assertThat(row.usedBy).containsExactly(NotificationTrigger.INVOICE_DUE_SOON.name)
    }

    // --- perkakas uji ---

    private fun command(name: String, language: String = "id", body: String = "Halo, {{1}}") =
        SaveTemplateCommand(name, language, TemplateCategory.UTILITY, body)

    private fun metaSettings(wabaId: String? = "9988"): NotificationSettings =
        NotificationSettings.defaultFor(tenantId).apply {
            update(
                provider = WhatsAppProvider.META_CLOUD, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = "1234567890", metaAccessToken = "EAAtoken", metaWabaId = wabaId,
                qontakAccessToken = null, qontakChannelIntegrationId = null,
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = true,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

    private fun qontakSettings(): NotificationSettings =
        NotificationSettings.defaultFor(tenantId).apply {
            update(
                provider = WhatsAppProvider.QONTAK, gatewayEnabled = true, emailEnabled = false,
                httpEndpointUrl = null, httpToken = null, httpPhoneField = null, httpMessageField = null,
                metaPhoneNumberId = null, metaAccessToken = null, metaWabaId = null,
                qontakAccessToken = "qontak-token", qontakChannelIntegrationId = "kanal-1",
                notifyOnSubscriptionLifecycle = false, notifyOnInvoiceReminder = true,
                notifyOnWorkOrderSchedule = false, notifyOnIncidentOpen = false,
            )
        }

    private class FakeSettingsRepo(var row: NotificationSettings?) : NotificationSettingsRepository {
        override fun find(): NotificationSettings? = row
        override fun save(settings: NotificationSettings): NotificationSettings = settings.also { row = it }
    }

    /**
     * Penyedia palsu yang MENCATAT apa yang diminta padanya — itulah yang diperiksa tes ini,
     * karena inti perubahannya adalah "aplikasi benar-benar memanggil penyedia". Larangan
     * [canEdit]/[canDeleteRemotely] ditegakkan di sini juga, supaya kalau service lupa
     * memeriksanya, tes gagal alih-alih diam-diam lolos.
     */
    private class FakeCatalog(
        override val provider: WhatsAppProvider,
        override val label: String,
        override val canEdit: Boolean,
        override val canDeleteRemotely: Boolean,
    ) : WhatsAppTemplateCatalog {
        var rows: List<RemoteTemplate> = emptyList()
        var failCreate: String? = null
        val created = mutableListOf<TemplateDraft>()
        val edited = mutableListOf<Pair<String, TemplateDraft>>()
        val deleted = mutableListOf<String>()

        override fun list(api: TemplateApi): List<RemoteTemplate> = rows

        override fun create(api: TemplateApi, draft: TemplateDraft): RemoteTemplate {
            failCreate?.let { throw ConflictException(it) }
            created += draft
            return RemoteTemplate(
                remoteId = "remote-${created.size}",
                name = draft.name,
                language = draft.language,
                category = draft.category,
                status = TemplateStatus.PENDING,
                bodyText = draft.bodyText,
            )
        }

        override fun edit(api: TemplateApi, remoteId: String, draft: TemplateDraft): RemoteTemplate {
            if (!canEdit) throw IllegalStateException("$label tak punya API sunting — service semestinya menolak dulu")
            edited += remoteId to draft
            return RemoteTemplate(
                remoteId = remoteId,
                name = draft.name,
                language = draft.language,
                category = draft.category,
                status = TemplateStatus.PENDING,
                bodyText = draft.bodyText,
            )
        }

        override fun delete(api: TemplateApi, remoteId: String, name: String) {
            if (!canDeleteRemotely) {
                throw IllegalStateException("$label tak punya API hapus — service semestinya menolak dulu")
            }
            deleted += remoteId
        }
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
