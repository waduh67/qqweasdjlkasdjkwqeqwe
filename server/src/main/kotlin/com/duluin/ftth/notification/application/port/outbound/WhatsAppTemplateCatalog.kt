package com.duluin.ftth.notification.application.port.outbound

import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateStatus

/**
 * Pembaca katalog template di sisi penyedia (Meta Graph API). Hanya BACA — pembuatan &
 * penghapusan template tetap lewat Meta Business Manager, karena keduanya memicu proses
 * peninjauan Meta yang tak bisa diwakili dari sini.
 */
interface WhatsAppTemplateCatalog {
    /**
     * Daftar seluruh template milik [wabaId]. Melempar
     * [com.duluin.ftth.common.domain.error.ConflictException] bila Meta menolak (WABA ID/token
     * salah, izin kurang) agar operator melihat sebabnya, bukan 500.
     */
    fun list(wabaId: String, accessToken: String): List<RemoteTemplate>
}

/** Satu template sebagaimana dilaporkan Meta. [bodyText] = isi komponen `BODY` (bila ada). */
data class RemoteTemplate(
    val metaId: String?,
    val name: String,
    val language: String,
    val category: TemplateCategory,
    val status: TemplateStatus,
    val bodyText: String?,
)
