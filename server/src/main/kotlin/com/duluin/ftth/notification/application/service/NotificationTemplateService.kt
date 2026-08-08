package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.inbound.DeleteTemplateResult
import com.duluin.ftth.notification.application.port.inbound.EditTemplateCommand
import com.duluin.ftth.notification.application.port.inbound.ManageNotificationTemplateUseCase
import com.duluin.ftth.notification.application.port.inbound.NotificationTemplateView
import com.duluin.ftth.notification.application.port.inbound.ReplaceAssignmentsCommand
import com.duluin.ftth.notification.application.port.inbound.SaveTemplateCommand
import com.duluin.ftth.notification.application.port.inbound.SyncTemplatesResult
import com.duluin.ftth.notification.application.port.inbound.TemplateCatalogView
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.NotificationTemplateRepository
import com.duluin.ftth.notification.application.port.outbound.TemplateDraft
import com.duluin.ftth.notification.application.port.outbound.WhatsAppTemplateCatalog
import com.duluin.ftth.notification.domain.model.NotificationMessageTemplate
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.TemplateApi
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateSource
import com.duluin.ftth.notification.domain.model.TemplateStatus
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Sisi operator katalog template WhatsApp. Tiga tanggung jawab:
 *
 *  1. Menjaga PRASYARAT — mengelola template hanya masuk akal bila gateway WhatsApp resmi
 *     benar-benar hidup dan kredensialnya tersimpan; kalau tidak, semua operasi tulis ditolak
 *     [ConflictException] dengan sebab yang bisa ditindaklanjuti (kalimatnya dari
 *     [NotificationSettings.templateBlockedReason], satu sumber kebenaran bersama UI). Membaca
 *     katalog sengaja dibiarkan bebas agar daftar tak hilang saat gateway dimatikan sesaat.
 *  2. Menjaga katalog lokal tetap jadi CERMIN penyedia — tambah/ubah/hapus di sini memanggil
 *     API Meta Cloud atau Mekari Qontak lebih dulu, dan baris lokal baru menyusul setelah
 *     penyedia menerima. Kalau penyedia menolak, tak ada baris yang tertinggal.
 *  3. Menegakkan "satu template per pemicu" — lewat [NotificationTemplateRepository.replaceAssignments]
 *     yang menulis ulang seluruh peta sekaligus.
 *
 * Katalog penyedia disuntikkan sebagai DAFTAR lalu dipetakan `provider → catalog`: penyedia yang
 * aktif ditentukan setelan tenant saat runtime, jadi tak ada satu implementasi yang bisa dipilih
 * saat perakitan bean.
 *
 * Perubahan dicatat ke audit: template menentukan bunyi pesan yang sampai ke pelanggan.
 */
@Service
@Transactional(readOnly = true)
class NotificationTemplateService(
    private val templateRepository: NotificationTemplateRepository,
    private val settingsRepository: NotificationSettingsRepository,
    catalogs: List<WhatsAppTemplateCatalog>,
    private val auditor: AuditRecorder,
) : ManageNotificationTemplateUseCase {

    private val catalogs: Map<WhatsAppProvider, WhatsAppTemplateCatalog> = catalogs.associateBy { it.provider }

    override fun list(): TemplateCatalogView = catalogView()

    @Transactional
    override fun create(command: SaveTemplateCommand): TemplateCatalogView {
        val (catalog, api) = requireCatalog()
        // Divalidasi & diperiksa duplikatnya SEBELUM memanggil penyedia: menolak di sini jauh
        // lebih murah daripada menciptakan template kembar di Meta yang tak bisa ditarik balik.
        val template = NotificationMessageTemplate.draft(
            tenantId = TenantContext.tenantId(),
            name = command.name,
            language = command.language,
            category = command.category,
            bodyText = command.bodyText,
        )
        templateRepository.findByNameAndLanguage(template.name, template.language)?.let {
            throw ConflictException("Template \"${template.name}\" (${template.language}) sudah terdaftar")
        }
        val remote = catalog.create(
            api,
            TemplateDraft(template.name, template.language, template.category, template.bodyText.orEmpty()),
        )
        template.applyRemote(remote.remoteId, remote.category, remote.status, remote.bodyText, Instant.now())
        val saved = templateRepository.save(template)
        audit("notification.template.created", saved)
        return catalogView()
    }

    @Transactional
    override fun update(id: UUID, command: EditTemplateCommand): TemplateCatalogView {
        val (catalog, api) = requireCatalog()
        val template = templateRepository.findById(id) ?: throw NotFoundException("Template tidak ditemukan")
        if (!catalog.canEdit) {
            throw ConflictException(
                "${catalog.label} tak mengizinkan menyunting template yang sudah diajukan — " +
                    "hapus template ini lalu buat yang baru.",
            )
        }
        val remoteId = template.remoteId
            ?: throw ConflictException(
                "Template ini belum punya padanan di ${catalog.label} — tarik ulang katalog dulu, " +
                    "atau hapus lalu buat baru.",
            )
        val bodyText = NotificationMessageTemplate.validateBody(command.bodyText)
        val remote = catalog.edit(
            api,
            remoteId,
            TemplateDraft(template.name, template.language, command.category, bodyText),
        )
        template.editBody(bodyText, command.category)
        // Penyedia yang menentukan status akhir (suntingan kembali masuk antrean peninjauan).
        template.applyRemote(remote.remoteId ?: remoteId, remote.category, remote.status, remote.bodyText, Instant.now())
        val saved = templateRepository.save(template)
        audit("notification.template.updated", saved)
        return catalogView()
    }

    @Transactional
    override fun delete(id: UUID): DeleteTemplateResult {
        val (catalog, api) = requireCatalog()
        val template = templateRepository.findById(id) ?: throw NotFoundException("Template tidak ditemukan")
        val remoteId = template.remoteId
        val removedRemotely = catalog.canDeleteRemotely && remoteId != null
        // Hapus di penyedia DULU: kalau langkah ini gagal, transaksi batal dan baris lokal tetap
        // ada — cermin yang tertinggal jauh lebih baik daripada template hantu yang masih hidup
        // di penyedia tapi tak lagi terlihat di aplikasi.
        if (removedRemotely) catalog.delete(api, remoteId!!, template.name)
        // Pemetaan pemicu ikut terhapus lewat ON DELETE CASCADE.
        templateRepository.delete(id)
        audit("notification.template.deleted", template)
        val message = when {
            removedRemotely -> "Template \"${template.name}\" dihapus dari aplikasi dan dari ${catalog.label}."
            !catalog.canDeleteRemotely ->
                "Template \"${template.name}\" dihapus dari daftar aplikasi saja — ${catalog.label} tak " +
                    "menyediakan API hapus, jadi hapus juga lewat dasbornya agar tak terpakai lagi."
            else ->
                "Template \"${template.name}\" dihapus dari daftar aplikasi. Template ini memang tak " +
                    "punya padanan di ${catalog.label}."
        }
        return DeleteTemplateResult(removedRemotely, message, catalogView())
    }

    @Transactional
    override fun replaceAssignments(command: ReplaceAssignmentsCommand): TemplateCatalogView {
        requireSettings()
        val known = templateRepository.findAll().associateBy { it.id }
        command.assignments.forEach { (trigger, templateId) ->
            known[templateId] ?: throw NotFoundException("Template untuk pemicu $trigger tidak ditemukan")
        }
        templateRepository.replaceAssignments(command.assignments)
        auditor.record(
            action = "notification.template.assigned",
            entityType = "NotificationTriggerTemplate",
            entityId = TenantContext.tenantId(),
            tenantId = TenantContext.tenantId(),
            detail = command.assignments.entries.associate { (trigger, id) -> trigger.name to (known[id]?.name ?: "") },
        )
        return catalogView()
    }

    @Suppress("CyclomaticComplexMethod")
    @Transactional
    override fun sync(): SyncTemplatesResult {
        val (catalog, api) = requireCatalog()
        val remote = catalog.list(api)
        val now = Instant.now()
        var imported = 0
        var updated = 0
        var skipped = 0
        // Yang benar-benar terlihat di penyedia, untuk mengenali baris lokal yang jadi yatim.
        val seen = mutableSetOf<Pair<String, String>>()
        remote.forEach { r ->
            // Hanya UTILITY yang relevan: pesan transaksional ISP. MARKETING/AUTHENTICATION
            // dilewati agar katalog tak penuh template yang tak akan pernah dipakai pemicu.
            if (r.category != TemplateCategory.UTILITY) {
                skipped++
                return@forEach
            }
            val name = runCatching { NotificationMessageTemplate.validateName(r.name) }.getOrNull()
            val language = runCatching { NotificationMessageTemplate.validateLanguage(r.language) }.getOrNull()
            if (name == null || language == null) {
                skipped++
                return@forEach
            }
            seen += name to language
            val existing = templateRepository.findByNameAndLanguage(name, language)
            val target = existing ?: NotificationMessageTemplate.mirror(TenantContext.tenantId(), name, language)
            target.applyRemote(r.remoteId, r.category, r.status, r.bodyText, now)
            templateRepository.save(target)
            if (existing == null) imported++ else updated++
        }
        // Baris cermin yang tak lagi muncul di penyedia: DINONAKTIFKAN, bukan dihapus. Baris itu
        // memikul pemetaan pemicu yang tak ada padanannya di penyedia; menghapusnya diam-diam
        // akan membungkam notifikasi tanpa jejak yang bisa dilacak operator.
        var missing = 0
        templateRepository.findAll()
            .filter { it.source == TemplateSource.REMOTE && (it.name to it.language) !in seen }
            .filter { it.status != TemplateStatus.DISABLED }
            .forEach {
                it.markMissingRemotely(now)
                templateRepository.save(it)
                missing++
            }
        auditor.record(
            action = "notification.template.synced",
            entityType = "NotificationMessageTemplate",
            entityId = TenantContext.tenantId(),
            tenantId = TenantContext.tenantId(),
            detail = mapOf(
                "provider" to catalog.provider.name,
                "imported" to imported.toString(),
                "updated" to updated.toString(),
                "skipped" to skipped.toString(),
                "missing" to missing.toString(),
            ),
        )
        val message = buildString {
            append("${remote.size} template dibaca dari ${catalog.label}")
            if (imported > 0) append(", $imported baru")
            if (updated > 0) append(", $updated diperbarui")
            if (skipped > 0) append(", $skipped dilewati (bukan UTILITY atau namanya tak sah)")
            if (missing > 0) append(", $missing tak ditemukan lagi di ${catalog.label} (dinonaktifkan)")
        }
        return SyncTemplatesResult(remote.size, imported, updated, skipped, missing, message, catalogView())
    }

    private fun requireSettings(): NotificationSettings {
        val settings = settingsRepository.find() ?: NotificationSettings.defaultFor(TenantContext.tenantId())
        settings.templateBlockedReason()?.let { throw ConflictException(it) }
        return settings
    }

    /**
     * Katalog penyedia aktif + kredensialnya, atau [ConflictException] yang menyebut persis apa
     * yang kurang. Penyedia tanpa implementasi katalog (semestinya mustahil — hanya penyedia
     * resmi yang lolos [NotificationSettings.templateBlockedReason]) ditolak dengan jelas juga,
     * daripada NPE di kemudian hari.
     */
    private fun requireCatalog(): Pair<WhatsAppTemplateCatalog, TemplateApi> {
        val settings = requireSettings()
        val api = settings.resolveTemplateApi()
            ?: throw ConflictException("Kredensial pengelolaan template belum lengkap")
        val catalog = catalogs[settings.provider]
            ?: throw ConflictException("Penyedia ${settings.provider} belum mendukung pengelolaan template")
        return catalog to api
    }

    private fun catalogView(): TemplateCatalogView {
        val settings = settingsRepository.find() ?: NotificationSettings.defaultFor(TenantContext.tenantId())
        val reason = settings.templateBlockedReason()
        val catalog = catalogs[settings.provider]
        val assignments = templateRepository.assignments()
        // Balik peta sekali: pemicu-pemicu yang memakai tiap template.
        val usedBy = assignments.entries.groupBy({ it.value }, { it.key.name })
        return TemplateCatalogView(
            templates = templateRepository.findAll().map { it.toView(usedBy[it.id].orEmpty()) },
            assignments = assignments.entries.associate { (trigger, id) -> trigger.name to id },
            manageable = reason == null && catalog != null,
            syncable = reason == null && catalog != null,
            blockedReason = reason,
            providerLabel = catalog?.label,
            canEdit = catalog?.canEdit ?: false,
            canDeleteRemotely = catalog?.canDeleteRemotely ?: false,
            // Qontak hanya bisa mengirim template; pemicu tanpa template akan dilewati.
            requiresTemplateForEveryTrigger = settings.provider == WhatsAppProvider.QONTAK,
        )
    }

    private fun audit(action: String, template: NotificationMessageTemplate) = auditor.record(
        action = action,
        entityType = "NotificationMessageTemplate",
        entityId = template.id,
        tenantId = template.tenantId,
        detail = mapOf("name" to template.name, "language" to template.language),
    )

    private fun NotificationMessageTemplate.toView(usedBy: List<String>) = NotificationTemplateView(
        id = id,
        name = name,
        language = language,
        category = category.name,
        status = status.name,
        source = source.name,
        bodyText = bodyText,
        bodyParamCount = bodyParamCount,
        syncedAt = syncedAt,
        usedBy = usedBy,
    )
}
