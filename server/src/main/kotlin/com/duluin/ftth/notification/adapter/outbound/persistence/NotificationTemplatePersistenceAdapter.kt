package com.duluin.ftth.notification.adapter.outbound.persistence

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.notification.application.port.outbound.NotificationTemplateRepository
import com.duluin.ftth.notification.domain.model.NotificationMessageTemplate
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Adapter katalog template + pemetaan pemicu. Tanpa enkripsi: tak ada rahasia di sini
 * (token tetap tinggal di [NotificationSettingsPersistenceAdapter]).
 *
 * Dua repository terpisah tanpa relasi JPA (pola [BroadcastPersistenceAdapter]) supaya
 * [findForTrigger] — yang dipanggil di jalur kirim — jadi dua query kecil yang bisa
 * diprediksi, bukan graph fetch.
 */
@Component
class NotificationTemplatePersistenceAdapter(
    private val jpa: NotificationMessageTemplateJpaRepository,
    private val assignmentJpa: NotificationTriggerTemplateJpaRepository,
) : NotificationTemplateRepository {

    override fun findAll(): List<NotificationMessageTemplate> =
        jpa.findAllByOrderByNameAscLanguageAsc().map { it.toDomain() }

    override fun findById(id: UUID): NotificationMessageTemplate? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByNameAndLanguage(name: String, language: String): NotificationMessageTemplate? =
        jpa.findByNameAndLanguage(name, language)?.toDomain()

    override fun save(template: NotificationMessageTemplate): NotificationMessageTemplate {
        val entity = jpa.findById(template.id).orElse(null)?.apply {
            name = template.name
            language = template.language
            category = template.category
            status = template.status
            source = template.source
            metaTemplateId = template.metaTemplateId
            bodyPreview = template.bodyPreview
            bodyParamCount = template.bodyParamCount
            syncedAt = template.syncedAt
        } ?: NotificationMessageTemplateJpaEntity(
            id = template.id,
            name = template.name,
            language = template.language,
            category = template.category,
            status = template.status,
            source = template.source,
            metaTemplateId = template.metaTemplateId,
            bodyPreview = template.bodyPreview,
            bodyParamCount = template.bodyParamCount,
            syncedAt = template.syncedAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun delete(id: UUID) {
        // Baris pemetaan ikut lenyap lewat ON DELETE CASCADE di DB; hapus dulu di sisi JPA
        // agar konteks persistence tak menyimpan baris yatim dalam transaksi yang sama.
        assignmentJpa.deleteAll(assignmentJpa.findAll().filter { it.templateId == id })
        jpa.deleteById(id)
    }

    override fun assignments(): Map<NotificationTrigger, UUID> =
        assignmentJpa.findAll().associate { it.trigger to it.templateId }

    override fun replaceAssignments(assignments: Map<NotificationTrigger, UUID>) {
        // Hapus-lalu-tulis-ulang + flush: memindahkan pemicu dari template A ke B tak pernah
        // membentur unique index (tenant_id, trigger) di tengah transaksi.
        assignmentJpa.deleteAllInBatch(assignmentJpa.findAll())
        assignmentJpa.flush()
        assignments.forEach { (trigger, templateId) ->
            assignmentJpa.save(
                NotificationTriggerTemplateJpaEntity(
                    id = UuidV7.generate(),
                    trigger = trigger,
                    templateId = templateId,
                ),
            )
        }
    }

    override fun findForTrigger(trigger: NotificationTrigger): NotificationMessageTemplate? =
        assignmentJpa.findByTrigger(trigger)?.let { jpa.findById(it.templateId).orElse(null)?.toDomain() }

    private fun NotificationMessageTemplateJpaEntity.toDomain() = NotificationMessageTemplate.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        name = name,
        language = language,
        category = category,
        status = status,
        source = source,
        metaTemplateId = metaTemplateId,
        bodyPreview = bodyPreview,
        bodyParamCount = bodyParamCount,
        syncedAt = syncedAt,
    )
}
