package com.duluin.ftth.notification.application.port.outbound

import com.duluin.ftth.notification.domain.model.TemplateApi
import com.duluin.ftth.notification.domain.model.TemplateCategory
import com.duluin.ftth.notification.domain.model.TemplateStatus
import com.duluin.ftth.notification.domain.model.WhatsAppProvider

/**
 * Pengelola katalog template di sisi PENYEDIA — satu implementasi per
 * [WhatsAppProvider] resmi (Meta Cloud, Mekari Qontak), dipilih service lewat [provider].
 *
 * Port ini sengaja bukan cuma pembaca: sejak katalog lokal diperlakukan sebagai cermin,
 * "tambah template" di aplikasi berarti benar-benar mengajukan template ke penyedia. Yang
 * tidak seragam adalah SEJAUH MANA tiap penyedia mengizinkannya — Qontak, misalnya, sama
 * sekali tak punya endpoint ubah maupun hapus. Alih-alih memaksa semua implementasi
 * berpura-pura mampu, kemampuan itu diumumkan lewat [canEdit] / [canDeleteRemotely] supaya
 * service bisa menolak lebih awal dengan penjelasan, dan UI bisa menyembunyikan tombolnya.
 *
 * Semua metode melempar [com.duluin.ftth.common.domain.error.ConflictException] bila penyedia
 * menolak (kredensial salah, izin kurang, template melanggar aturan) — operator melihat
 * sebabnya, bukan 500.
 */
interface WhatsAppTemplateCatalog {
    val provider: WhatsAppProvider

    /** Nama penyedia untuk pesan & label UI, mis. "Meta Cloud". */
    val label: String

    /** Penyedia mengizinkan menyunting template yang sudah ada? */
    val canEdit: Boolean

    /** Penyedia punya API hapus? Bila tidak, menghapus hanya membuang cermin lokal. */
    val canDeleteRemotely: Boolean

    fun list(api: TemplateApi): List<RemoteTemplate>

    /** Ajukan template baru. Hasilnya membawa id & status awal dari penyedia. */
    fun create(api: TemplateApi, draft: TemplateDraft): RemoteTemplate

    /** Ubah isi/kategori template yang sudah ada. Hanya sah bila [canEdit]. */
    fun edit(api: TemplateApi, remoteId: String, draft: TemplateDraft): RemoteTemplate

    /** Hapus di sisi penyedia. Hanya sah bila [canDeleteRemotely]. */
    fun delete(api: TemplateApi, remoteId: String, name: String)
}

/**
 * Isi template yang diajukan ke penyedia. Hanya komponen BODY — konvensi kirim kita adalah
 * satu variabel `{{1}}` berisi seluruh pesan, jadi header/footer/tombol tak punya gunanya
 * (template yang DITARIK boleh punya semuanya; yang kita BUAT tidak).
 */
data class TemplateDraft(
    val name: String,
    val language: String,
    val category: TemplateCategory,
    val bodyText: String,
)

/** Satu template sebagaimana dilaporkan penyedia. [bodyText] = isi komponen `BODY` (bila ada). */
data class RemoteTemplate(
    val remoteId: String?,
    val name: String,
    val language: String,
    val category: TemplateCategory,
    val status: TemplateStatus,
    val bodyText: String?,
)

/** Satu kanal WhatsApp terdaftar di Qontak, untuk dropdown pemilihan di setelan gateway. */
data class QontakChannel(val id: String, val name: String)

/**
 * Pembaca daftar kanal WhatsApp milik akun Qontak. Dipisah dari [WhatsAppTemplateCatalog]
 * karena dipakai pada tahap yang berbeda: memilih kanal adalah bagian dari MENYETEL gateway,
 * dan justru harus bisa dipanggil ketika channel id masih kosong.
 */
interface QontakChannelDirectory {
    fun list(accessToken: String): List<QontakChannel>
}
