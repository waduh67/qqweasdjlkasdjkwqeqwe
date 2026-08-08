package com.duluin.ftth.notification.application.port.inbound

import com.duluin.ftth.notification.domain.model.NotificationTrigger
import java.time.Instant
import java.util.UUID

/**
 * Sisi operator pengelolaan template pesan WhatsApp: katalog template tenant + pemetaan
 * "pemicu mana memakai template mana".
 *
 * Semua operasi TULIS mensyaratkan gateway WhatsApp aktif dengan penyedia Meta Cloud dan
 * kredensial tersimpan ([com.duluin.ftth.notification.domain.model.NotificationSettings.metaTemplateReady]);
 * bila belum, dilempar ConflictException. [list] sengaja bebas syarat agar katalog tak
 * menghilang saat gateway dimatikan sementara.
 */
interface ManageNotificationTemplateUseCase {
    fun list(): TemplateCatalogView
    fun create(command: SaveTemplateCommand): TemplateCatalogView
    fun update(id: UUID, command: SaveTemplateCommand): TemplateCatalogView
    fun delete(id: UUID): TemplateCatalogView
    fun replaceAssignments(command: ReplaceAssignmentsCommand): TemplateCatalogView

    /** Tarik daftar template dari Meta lalu upsert ke katalog lokal. */
    fun sync(): SyncTemplatesResult
}

/**
 * Isi kartu template: katalog + pemetaan pemicu. [manageable] & [syncable] memberi tahu UI
 * apakah aksi boleh ditawarkan, [blockedReason] menjelaskan apa yang kurang bila tidak.
 */
data class TemplateCatalogView(
    val templates: List<NotificationTemplateView>,
    /** Pemicu → id template (string), hanya berisi pemicu yang dipetakan. */
    val assignments: Map<String, UUID>,
    val manageable: Boolean,
    val syncable: Boolean,
    val blockedReason: String?,
)

data class NotificationTemplateView(
    val id: UUID,
    val name: String,
    val language: String,
    val category: String,
    val status: String,
    val source: String,
    val bodyPreview: String?,
    val bodyParamCount: Int,
    val syncedAt: Instant?,
    /** Pemicu yang memakai template ini — memudahkan UI menampilkan "dipakai untuk". */
    val usedBy: List<String>,
)

/** Tambah/sunting entri manual. Bahasa kosong = bawaan `id`. */
data class SaveTemplateCommand(val name: String?, val language: String?)

/**
 * Peta pemicu → id template yang menggantikan SELURUH pemetaan lama. Pemicu yang tak
 * disebut berarti tanpa template (kirim teks biasa).
 */
data class ReplaceAssignmentsCommand(val assignments: Map<NotificationTrigger, UUID>)

/** Ringkasan hasil sync untuk ditampilkan operator. */
data class SyncTemplatesResult(
    val fetched: Int,
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val message: String,
    val catalog: TemplateCatalogView,
)
