package com.duluin.ftth.notification.application.port.inbound

import com.duluin.ftth.notification.domain.model.NotificationTrigger
import com.duluin.ftth.notification.domain.model.TemplateCategory
import java.time.Instant
import java.util.UUID

/**
 * Sisi operator pengelolaan template pesan WhatsApp: katalog template tenant + pemetaan
 * "pemicu mana memakai template mana".
 *
 * Katalog lokal adalah CERMIN dari template di penyedia — [create] / [update] / [delete]
 * benar-benar memanggil API Meta Cloud atau Mekari Qontak, bukan sekadar menulis baris.
 * Karena itu semua operasi TULIS mensyaratkan gateway WhatsApp resmi yang aktif dengan
 * kredensial tersimpan ([com.duluin.ftth.notification.domain.model.NotificationSettings.templateBlockedReason]);
 * bila belum, dilempar ConflictException. [list] sengaja bebas syarat agar katalog tak
 * menghilang saat gateway dimatikan sementara.
 */
interface ManageNotificationTemplateUseCase {
    fun list(): TemplateCatalogView

    /** Ajukan template baru KE PENYEDIA; baris lokal hanya tersimpan bila pengajuan diterima. */
    fun create(command: SaveTemplateCommand): TemplateCatalogView

    /** Sunting isi & kategori di penyedia. Nama/bahasa tak bisa diubah — hapus lalu buat baru. */
    fun update(id: UUID, command: EditTemplateCommand): TemplateCatalogView

    fun delete(id: UUID): DeleteTemplateResult
    fun replaceAssignments(command: ReplaceAssignmentsCommand): TemplateCatalogView

    /** Tarik daftar template dari penyedia lalu upsert ke katalog lokal. */
    fun sync(): SyncTemplatesResult
}

/**
 * Isi kartu template: katalog + pemetaan pemicu, plus apa yang boleh dilakukan terhadapnya.
 *
 * [manageable] & [syncable] memberi tahu UI apakah aksi boleh ditawarkan sama sekali,
 * [blockedReason] menjelaskan apa yang kurang bila tidak. [canEdit] / [canDeleteRemotely]
 * mencerminkan kemampuan PENYEDIA yang sedang dipakai (Qontak tak punya API ubah maupun hapus),
 * supaya UI menyembunyikan tombol yang pasti gagal alih-alih menjanjikannya.
 */
data class TemplateCatalogView(
    val templates: List<NotificationTemplateView>,
    /** Pemicu → id template (string), hanya berisi pemicu yang dipetakan. */
    val assignments: Map<String, UUID>,
    val manageable: Boolean,
    val syncable: Boolean,
    val blockedReason: String?,
    /** Nama penyedia aktif untuk teks UI, mis. "Meta Cloud". Null bila belum ada penyedia resmi. */
    val providerLabel: String?,
    val canEdit: Boolean,
    /** Penyedia punya API hapus? Bila false, menghapus hanya membuang cermin lokal. */
    val canDeleteRemotely: Boolean,
    /**
     * Penyedia TAK BISA mengirim teks biasa, jadi pemicu tanpa template akan dilewati diam-diam
     * (Qontak). UI memakai ini untuk memerahkan pemicu yang belum dipetakan.
     */
    val requiresTemplateForEveryTrigger: Boolean,
)

data class NotificationTemplateView(
    val id: UUID,
    val name: String,
    val language: String,
    val category: String,
    val status: String,
    val source: String,
    /** Teks komponen BODY — yang diajukan ke penyedia, bukan sekadar cuplikan. */
    val bodyText: String?,
    val bodyParamCount: Int,
    val syncedAt: Instant?,
    /** Pemicu yang memakai template ini — memudahkan UI menampilkan "dipakai untuk". */
    val usedBy: List<String>,
)

/** Pengajuan template baru ke penyedia. Bahasa kosong = bawaan `id`. */
data class SaveTemplateCommand(
    val name: String?,
    val language: String?,
    val category: TemplateCategory,
    val bodyText: String?,
)

/** Suntingan template yang sudah ada — hanya isi & kategori; nama/bahasa terkunci di penyedia. */
data class EditTemplateCommand(val category: TemplateCategory, val bodyText: String?)

/**
 * Hasil hapus. [removedRemotely] false berarti template MASIH ADA di penyedia (Qontak tak
 * menyediakan API hapus, atau baris ini memang tak pernah punya padanan di sana) — UI wajib
 * mengatakannya, bukan berpura-pura bersih.
 */
data class DeleteTemplateResult(
    val removedRemotely: Boolean,
    val message: String,
    val catalog: TemplateCatalogView,
)

/**
 * Peta pemicu → id template yang menggantikan SELURUH pemetaan lama. Pemicu yang tak
 * disebut berarti tanpa template (kirim teks biasa, atau dilewati bila penyedianya Qontak).
 */
data class ReplaceAssignmentsCommand(val assignments: Map<NotificationTrigger, UUID>)

/** Ringkasan hasil sync untuk ditampilkan operator. */
data class SyncTemplatesResult(
    val fetched: Int,
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    /** Baris lokal yang tak lagi ada di penyedia; ditandai DISABLED, bukan dihapus. */
    val missing: Int,
    val message: String,
    val catalog: TemplateCatalogView,
)
