package com.duluin.ftth.notification.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Kategori template WhatsApp. Kosakata ini milik Meta dan dipakai apa adanya oleh BSP
 * (termasuk Qontak). Hanya [UTILITY] yang relevan untuk pesan transaksional ISP.
 */
enum class TemplateCategory { UTILITY, MARKETING, AUTHENTICATION }

/**
 * Status persetujuan template di penyedia. [UNKNOWN] = baris yang belum pernah disinkron,
 * jadi status sebenarnya belum diketahui (bukan berarti ditolak).
 */
enum class TemplateStatus { APPROVED, PENDING, REJECTED, PAUSED, DISABLED, UNKNOWN }

/**
 * Asal baris: diketik operator tanpa pernah diajukan ([MANUAL] — sisa data lama), atau
 * benar-benar ada di penyedia ([REMOTE], baik hasil "Tarik" maupun hasil "Buat" dari aplikasi).
 */
enum class TemplateSource { MANUAL, REMOTE }

/**
 * Satu template pesan WhatsApp milik tenant — CERMIN dari template di sisi penyedia (Meta
 * Cloud atau Mekari Qontak), bukan catatan lokal yang berdiri sendiri. Menambah/menghapus
 * baris di sini berarti aplikasi memanggil API penyedia; yang tersimpan lokal hanyalah
 * bayangannya, plus satu hal yang tak dimiliki penyedia: pemetaan ke [NotificationTrigger].
 *
 * Tiga jalan masuk:
 *  - [draft]       calon template yang akan diajukan ke penyedia; status [TemplateStatus.PENDING]
 *                  dan `remoteId` masih null sampai penyedia menjawab.
 *  - [applyRemote] jawaban penyedia (hasil buat/sync): status/kategori/body ditimpa apa adanya.
 *  - [rehydrate]   memuat dari DB.
 *
 * Nama & bahasa TAK BISA diubah setelah tersimpan — Meta maupun Qontak sama-sama melarangnya
 * (Qontak bahkan melarang menyunting apa pun setelah diajukan). Ganti nama = hapus lalu buat
 * baru, karena itulah yang sesungguhnya terjadi di sisi penyedia. Karena itu satu-satunya
 * penyunting di sini adalah [editBody].
 *
 * [bodyParamCount] dicatat karena dispatcher selalu mengirim TEPAT SATU parameter
 * (`{{1}}` = seluruh pesan yang sudah dirakit); template dengan jumlah lain akan ditolak
 * penyedia saat kirim, jadi UI memperingatkannya lebih awal.
 */
class NotificationMessageTemplate private constructor(
    val id: UUID,
    val tenantId: UUID,
    val name: String,
    val language: String,
    category: TemplateCategory,
    status: TemplateStatus,
    source: TemplateSource,
    remoteId: String?,
    bodyText: String?,
    bodyParamCount: Int,
    syncedAt: Instant?,
) {
    var category: TemplateCategory = category
        private set

    var status: TemplateStatus = status
        private set

    var source: TemplateSource = source
        private set

    /**
     * Id template di sisi penyedia — angka untuk Meta, UUID untuk Qontak. Null berarti belum
     * (atau tak pernah) ada padanannya di sana: baris [TemplateSource.MANUAL] warisan lama,
     * atau draft yang pengajuannya belum dijawab.
     */
    var remoteId: String? = remoteId
        private set

    /**
     * Teks komponen BODY. Bukan sekadar pratinjau: inilah yang dikirim aplikasi saat MEMBUAT
     * template di penyedia, dan yang ditimpa balik dengan versi penyedia setelah sinkron.
     */
    var bodyText: String? = bodyText
        private set

    var bodyParamCount: Int = bodyParamCount
        private set

    var syncedAt: Instant? = syncedAt
        private set

    /**
     * Sunting isi + kategori. Hanya ini yang boleh berubah; Meta mengizinkannya untuk template
     * ber-status APPROVED/REJECTED, dan hasil suntingan kembali masuk antrean peninjauan —
     * karena itu status TIDAK diubah di sini, melainkan diambil dari jawaban penyedia lewat
     * [applyRemote].
     */
    fun editBody(bodyText: String, category: TemplateCategory) {
        this.bodyText = trimBody(bodyText)
        this.bodyParamCount = countBodyParams(bodyText)
        this.category = category
    }

    /** Terapkan data dari penyedia. Penyedia adalah sumber kebenaran → semua field ditimpa. */
    fun applyRemote(
        remoteId: String?,
        category: TemplateCategory,
        status: TemplateStatus,
        bodyText: String?,
        at: Instant,
    ) {
        this.remoteId = remoteId
        this.category = category
        this.status = status
        this.bodyText = trimBody(bodyText)
        this.bodyParamCount = countBodyParams(bodyText)
        this.source = TemplateSource.REMOTE
        this.syncedAt = at
    }

    /**
     * Tandai template yang tak lagi muncul di daftar penyedia. Sengaja TIDAK dihapus: baris
     * lokal memikul pemetaan pemicu yang tak ada padanannya di penyedia, dan menghapusnya
     * diam-diam saat sync akan membungkam notifikasi tanpa jejak. [TemplateStatus.DISABLED]
     * membuatnya kelihatan merah di UI, jadi operator yang memutuskan.
     */
    fun markMissingRemotely(at: Instant) {
        this.status = TemplateStatus.DISABLED
        this.syncedAt = at
    }

    companion object {
        const val MAX_NAME = 128
        const val MAX_LANGUAGE = 10

        /** Batas komponen BODY, sama di Meta maupun Qontak. */
        const val MAX_BODY = 1024

        /** Aturan Meta: huruf kecil, angka, dan garis bawah. */
        private val NAME_PATTERN = Regex("^[a-z0-9_]+$")

        /** `id`, `en`, `en_US`, `zh_HANS` — kode bahasa Meta. */
        private val LANGUAGE_PATTERN = Regex("^[A-Za-z]{2,3}(_[A-Za-z]{2,4})?$")

        /** Placeholder posisi Meta: `{{1}}`, `{{2}}`, … (spasi opsional di dalam kurung). */
        private val PARAM_PATTERN = Regex("\\{\\{\\s*(\\d+)\\s*}}")

        /**
         * Calon template yang akan diajukan ke penyedia. Status langsung [TemplateStatus.PENDING]
         * karena itulah nasibnya begitu pengajuan diterima; bila penyedia menolak, tak ada baris
         * yang tersimpan sama sekali.
         */
        fun draft(
            tenantId: UUID,
            name: String?,
            language: String?,
            category: TemplateCategory,
            bodyText: String?,
        ): NotificationMessageTemplate {
            val body = validateBody(bodyText)
            return NotificationMessageTemplate(
                id = UuidV7.generate(),
                tenantId = tenantId,
                name = validateName(name),
                language = validateLanguage(language),
                category = category,
                status = TemplateStatus.PENDING,
                source = TemplateSource.REMOTE,
                remoteId = null,
                bodyText = body,
                bodyParamCount = countBodyParams(body),
                syncedAt = null,
            )
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            name: String,
            language: String,
            category: TemplateCategory,
            status: TemplateStatus,
            source: TemplateSource,
            remoteId: String?,
            bodyText: String?,
            bodyParamCount: Int,
            syncedAt: Instant?,
        ): NotificationMessageTemplate = NotificationMessageTemplate(
            id, tenantId, name, language, category, status, source,
            remoteId, bodyText, bodyParamCount, syncedAt,
        )

        /**
         * Baris hasil sinkron untuk template yang belum pernah kita kenal. Field isi dibiarkan
         * kosong di sini — pemanggil langsung menyusulkan [applyRemote] dengan data penyedia.
         */
        fun mirror(tenantId: UUID, name: String, language: String): NotificationMessageTemplate =
            NotificationMessageTemplate(
                id = UuidV7.generate(),
                tenantId = tenantId,
                name = name,
                language = language,
                category = TemplateCategory.UTILITY,
                status = TemplateStatus.UNKNOWN,
                source = TemplateSource.REMOTE,
                remoteId = null,
                bodyText = null,
                bodyParamCount = 1,
                syncedAt = null,
            )

        fun validateName(value: String?): String {
            val trimmed = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: throw ValidationException("Nama template wajib diisi")
            if (trimmed.length > MAX_NAME) throw ValidationException("Nama template maksimal $MAX_NAME karakter")
            if (!NAME_PATTERN.matches(trimmed)) {
                throw ValidationException("Nama template hanya boleh huruf kecil, angka, dan garis bawah (aturan Meta)")
            }
            return trimmed
        }

        fun validateLanguage(value: String?): String {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
                ?: return NotificationSettings.DEFAULT_TEMPLATE_LANG
            if (trimmed.length > MAX_LANGUAGE) throw ValidationException("Kode bahasa maksimal $MAX_LANGUAGE karakter")
            if (!LANGUAGE_PATTERN.matches(trimmed)) {
                throw ValidationException("Kode bahasa template tak dikenal — contoh yang sah: id, en, en_US")
            }
            return trimmed
        }

        /**
         * Isi BODY yang sah untuk DIAJUKAN. Lebih ketat daripada apa yang kita terima saat
         * menarik dari penyedia (template lama boleh berbentuk apa pun): di sini kita yang
         * mengarang, jadi sekalian ditegakkan konvensi pengiriman kita — tepat satu placeholder
         * `{{1}}` yang akan diisi seluruh pesan rakitan listener.
         *
         * Larangan baris baru beruntun & spasi berlebih datang dari aturan penyedia sendiri
         * ("no newlines, tabs, or more than 4 consecutive spaces"), yang kalau dilanggar
         * ditolak saat peninjauan — lebih baik ketahuan sebelum dikirim.
         */
        fun validateBody(value: String?): String {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw ValidationException("Isi pesan template wajib diisi")
            if (trimmed.length > MAX_BODY) throw ValidationException("Isi pesan template maksimal $MAX_BODY karakter")
            val indices = PARAM_PATTERN.findAll(trimmed).map { it.groupValues[1] }.toSet()
            if (indices != setOf("1")) {
                throw ValidationException(
                    "Isi pesan harus memuat tepat satu jenis variabel {{1}} — variabel itu diisi " +
                        "seluruh isi notifikasi saat pesan dikirim",
                )
            }
            if (trimmed.contains("\n\n") || trimmed.contains("\t") || trimmed.contains("     ")) {
                throw ValidationException(
                    "Isi pesan tak boleh memuat baris kosong, tab, atau lebih dari empat spasi " +
                        "beruntun — template seperti itu ditolak WhatsApp",
                )
            }
            return trimmed
        }

        /**
         * Jumlah placeholder posisi UNIK di body (`{{1}} halo {{1}}` = 1, bukan 2) — yang
         * dihitung penyedia saat memvalidasi parameter kiriman.
         */
        fun countBodyParams(bodyText: String?): Int {
            if (bodyText.isNullOrBlank()) return 0
            return PARAM_PATTERN.findAll(bodyText).map { it.groupValues[1] }.toSet().size
        }

        private fun trimBody(bodyText: String?): String? =
            bodyText?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_BODY)
    }
}
