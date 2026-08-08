package com.duluin.ftth.notification.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.inbound.ManageNotificationTemplateUseCase
import com.duluin.ftth.notification.application.port.inbound.NotificationTemplateView
import com.duluin.ftth.notification.application.port.inbound.ReplaceAssignmentsCommand
import com.duluin.ftth.notification.application.port.inbound.SaveTemplateCommand
import com.duluin.ftth.notification.application.port.inbound.SyncTemplatesResult
import com.duluin.ftth.notification.application.port.inbound.TemplateCatalogView
import com.duluin.ftth.notification.application.port.outbound.NotificationSettingsRepository
import com.duluin.ftth.notification.application.port.outbound.NotificationTemplateRepository
import com.duluin.ftth.notification.application.port.outbound.WhatsAppTemplateCatalog
import com.duluin.ftth.notification.domain.model.NotificationMessageTemplate
import com.duluin.ftth.notification.domain.model.NotificationSettings
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.WhatsAppProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Sisi operator katalog template WhatsApp. Dua tanggung jawab:
 *
 *  1. Menjaga PRASYARAT — mengelola template hanya masuk akal bila gateway WhatsApp resmi
 *     (Meta Cloud) benar-benar hidup dan kredensialnya tersimpan; kalau tidak, semua operasi
 *     tulis ditolak [ConflictException] dengan sebab yang bisa ditindaklanjuti. Membaca
 *     katalog sengaja dibiarkan bebas agar daftar tak hilang saat gateway dimatikan sesaat.
 *  2. Menegakkan "satu template per pemicu" — lewat [NotificationTemplateRepository.replaceAssignments]
 *     yang menulis ulang seluruh peta sekaligus.
 *
 * Perubahan dicatat ke audit: template menentukan bunyi pesan yang sampai ke pelanggan.
 */
@Service
@Transactional(readOnly = true)
class NotificationTemplateService(
    private val templateRepository: NotificationTemplateRepository,
    private val settingsRepository: NotificationSettingsRepository,
    private val catalog: WhatsAppTemplateCatalog,
    private val auditor: AuditRecorder,
) : ManageNotificationTemplateUseCase {

    override fun list(): TemplateCatalogView = catalogView()

    @Transactional
    override fun create(command: SaveTemplateCommand): TemplateCatalogView {
        requireManageable()
        val template = NotificationMessageTemplate.create(TenantContext.tenantId(), command.name, command.language)
        templateRepository.findByNameAndLanguage(template.name, template.language)?.let {
            throw ConflictException("Template \"${template.name}\" (${template.language}) sudah terdaftar")
        }
        val saved = templateRepository.save(template)
        audit("notification.template.created", saved)
        return catalogView()
    }

    @Transactional
    override fun update(id: UUID, command: SaveTemplateCommand): TemplateCatalogView {
        requireManageable()
        val template = templateRepository.findById(id) ?: throw NotFoundException("Template tidak ditemukan")
        val name = NotificationMessageTemplate.validateName(command.name)
        val language = NotificationMessageTemplate.validateLanguage(command.language)
        templateRepository.findByNameAndLanguage(name, language)?.takeIf { it.id != id }?.let {
            throw ConflictException("Template \"$name\" ($language) sudah terdaftar")
        }
        template.rename(command.name, command.language)
        val saved = templateRepository.save(template)
        audit("notification.template.updated", saved)
        return catalogView()
    }

    @Transactional
    override fun delete(id: UUID): TemplateCatalogView {
        requireManageable()
        val template = templateRepository.findById(id) ?: throw NotFoundException("Template tidak ditemukan")
        // Pemetaan pemicu ikut terhapus lewat ON DELETE CASCADE → pemicu terkait kembali
        // mengirim teks biasa, bukan gagal kirim.
        templateRepository.delete(id)
        audit("notification.template.deleted", template)
        return catalogView()
    }

    @Transactional
    override fun replaceAssignments(command: ReplaceAssignmentsCommand): TemplateCatalogView {
        requireManageable()
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

    @Transactional
    override fun sync(): SyncTemplatesResult {
        val settings = requireManageable()
        val wabaId = settings.metaWabaId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ConflictException(
                "WhatsApp Business Account ID belum diisi — lengkapi di kartu Gateway WhatsApp untuk menarik template dari Meta.",
            )
        val token = settings.metaAccessToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ConflictException("Access token Meta belum tersimpan")

        val remote = catalog.list(wabaId, token)
        val now = Instant.now()
        var imported = 0
        var updated = 0
        var skipped = 0
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
            val existing = templateRepository.findByNameAndLanguage(name, language)
            val target = existing ?: NotificationMessageTemplate.create(TenantContext.tenantId(), name, language)
            target.applyRemote(r.metaId, r.category, r.status, r.bodyText, now)
            templateRepository.save(target)
            if (existing == null) imported++ else updated++
        }
        auditor.record(
            action = "notification.template.synced",
            entityType = "NotificationMessageTemplate",
            entityId = TenantContext.tenantId(),
            tenantId = TenantContext.tenantId(),
            detail = mapOf("imported" to imported.toString(), "updated" to updated.toString(), "skipped" to skipped.toString()),
        )
        val message = buildString {
            append("${remote.size} template dibaca dari Meta")
            if (imported > 0) append(", $imported baru")
            if (updated > 0) append(", $updated diperbarui")
            if (skipped > 0) append(", $skipped dilewati (bukan UTILITY atau namanya tak sah)")
        }
        return SyncTemplatesResult(remote.size, imported, updated, skipped, message, catalogView())
    }

    /**
     * Pastikan prasyarat pengelolaan terpenuhi, kembalikan setelan yang sudah diverifikasi.
     * Pesan menyebut persis apa yang kurang agar operator tak menebak-nebak.
     */
    private fun requireManageable(): NotificationSettings {
        val settings = settingsRepository.find() ?: NotificationSettings.defaultFor(TenantContext.tenantId())
        blockedReason(settings)?.let { throw ConflictException(it) }
        return settings
    }

    private fun blockedReason(settings: NotificationSettings): String? = when {
        !settings.gatewayEnabled -> "Gateway WhatsApp masih nonaktif — nyalakan dulu di kartu Gateway WhatsApp."
        settings.provider != WhatsAppProvider.META_CLOUD ->
            "Template hanya berlaku untuk WhatsApp resmi (Meta Cloud) — ganti penyedia gateway dulu."
        settings.metaPhoneNumberId.isNullOrBlank() -> "Phone Number ID Meta belum diisi."
        settings.metaAccessToken.isNullOrBlank() -> "Access token Meta belum tersimpan — simpan setelan gateway dulu."
        else -> null
    }

    private fun catalogView(): TemplateCatalogView {
        val settings = settingsRepository.find() ?: NotificationSettings.defaultFor(TenantContext.tenantId())
        val reason = blockedReason(settings)
        val assignments = templateRepository.assignments()
        // Balik peta sekali: pemicu-pemicu yang memakai tiap template.
        val usedBy = assignments.entries.groupBy({ it.value }, { it.key.name })
        return TemplateCatalogView(
            templates = templateRepository.findAll().map { it.toView(usedBy[it.id].orEmpty()) },
            assignments = assignments.entries.associate { (trigger, id) -> trigger.name to id },
            manageable = reason == null,
            syncable = reason == null && !settings.metaWabaId.isNullOrBlank(),
            blockedReason = reason,
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
        bodyPreview = bodyPreview,
        bodyParamCount = bodyParamCount,
        syncedAt = syncedAt,
        usedBy = usedBy,
    )
}
