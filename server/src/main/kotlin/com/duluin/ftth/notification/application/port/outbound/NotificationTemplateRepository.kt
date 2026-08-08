package com.duluin.ftth.notification.application.port.outbound

import com.duluin.ftth.notification.domain.model.NotificationMessageTemplate
import com.duluin.ftth.notification.domain.model.NotificationTrigger
import java.util.UUID

/**
 * Persistence katalog template WhatsApp tenant + pemetaannya ke pemicu. Semua operasi
 * tersaring ke tenant aktif (RLS + @TenantId), jadi tak ada parameter tenantId.
 *
 * Pemetaan disimpan sebagai peta pemicu→id template: satu pemicu paling banyak punya satu
 * template (ditegakkan unique index `uq_notification_trigger_template`), sementara satu
 * template boleh melayani beberapa pemicu.
 */
interface NotificationTemplateRepository {
    fun findAll(): List<NotificationMessageTemplate>
    fun findById(id: UUID): NotificationMessageTemplate?
    fun findByNameAndLanguage(name: String, language: String): NotificationMessageTemplate?
    fun save(template: NotificationMessageTemplate): NotificationMessageTemplate
    fun delete(id: UUID)

    /** Pemetaan pemicu → id template yang berlaku saat ini. */
    fun assignments(): Map<NotificationTrigger, UUID>

    /**
     * Ganti SELURUH peta dalam satu transaksi (hapus lalu tulis ulang) supaya memindahkan
     * pemicu antar template tak pernah melanggar unique index di tengah jalan.
     */
    fun replaceAssignments(assignments: Map<NotificationTrigger, UUID>)

    /** Template yang dipakai [trigger], atau null bila pemicu itu belum dipetakan. */
    fun findForTrigger(trigger: NotificationTrigger): NotificationMessageTemplate?
}
