package com.duluin.ftth.notification.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/** Kategori template Meta. Hanya [UTILITY] yang relevan untuk pesan transaksional ISP. */
enum class TemplateCategory { UTILITY, MARKETING, AUTHENTICATION }

/**
 * Status persetujuan template di Meta. [UNKNOWN] = entri yang diketik operator dan belum
 * pernah disinkron, jadi status sebenarnya belum diketahui (bukan berarti ditolak).
 */
enum class TemplateStatus { APPROVED, PENDING, REJECTED, PAUSED, DISABLED, UNKNOWN }

/** Asal baris: diketik operator ([MANUAL]) atau ditarik dari Graph API ([META]). */
enum class TemplateSource { MANUAL, META }

/**
 * Satu template pesan WhatsApp milik tenant. Template TIDAK dibuat di sini — pembuatan &
 * persetujuan tetap di Meta Business Manager; baris ini hanya katalog lokal supaya operator
 * bisa memetakan template mana dipakai pemicu mana ([NotificationTrigger]).
 *
 * Dua jalan masuk:
 *  - [create]      operator mengetik nama+bahasa sendiri (jalan darurat bila sync tak tersedia),
 *                  status [TemplateStatus.UNKNOWN].
 *  - [applyRemote] hasil "Tarik dari Meta": status/kategori/body ikut diperbarui apa adanya.
 *
 * [bodyParamCount] dicatat karena dispatcher selalu mengirim TEPAT SATU parameter
 * (`{{1}}` = seluruh pesan yang sudah dirakit); template dengan jumlah lain akan ditolak
 * Meta saat kirim, jadi UI memperingatkannya lebih awal.
 */
class NotificationMessageTemplate private constructor(
    val id: UUID,
    val tenantId: UUID,
    name: String,
    language: String,
    category: TemplateCategory,
    status: TemplateStatus,
    source: TemplateSource,
    metaTemplateId: String?,
    bodyPreview: String?,
    bodyParamCount: Int,
    syncedAt: Instant?,
) {
    var name: String = name
        private set

    var language: String = language
        private set

    var category: TemplateCategory = category
        private set

    var status: TemplateStatus = status
        private set

    var source: TemplateSource = source
        private set

    /** Id template di Meta; null untuk entri manual yang belum pernah cocok saat sync. */
    var metaTemplateId: String? = metaTemplateId
        private set

    var bodyPreview: String? = bodyPreview
        private set

    var bodyParamCount: Int = bodyParamCount
        private set

    var syncedAt: Instant? = syncedAt
        private set

    /**
     * Sunting entri manual. Mengganti nama/bahasa berarti menunjuk template Meta yang lain,
     * jadi status & pratinjau hasil sync lama tak lagi berlaku → dikembalikan ke UNKNOWN.
     */
    fun rename(name: String?, language: String?) {
        val newName = validateName(name)
        val newLang = validateLanguage(language)
        if (newName == this.name && newLang == this.language) return
        this.name = newName
        this.language = newLang
        this.status = TemplateStatus.UNKNOWN
        this.source = TemplateSource.MANUAL
        this.metaTemplateId = null
        this.bodyPreview = null
        this.bodyParamCount = 1
        this.syncedAt = null
    }

    /** Terapkan data dari Meta (sync). Meta adalah sumber kebenaran → semua field ditimpa. */
    fun applyRemote(
        metaTemplateId: String?,
        category: TemplateCategory,
        status: TemplateStatus,
        bodyText: String?,
        at: Instant,
    ) {
        this.metaTemplateId = metaTemplateId
        this.category = category
        this.status = status
        this.bodyPreview = trimPreview(bodyText)
        this.bodyParamCount = countBodyParams(bodyText)
        this.source = TemplateSource.META
        this.syncedAt = at
    }

    companion object {
        const val MAX_NAME = 128
        const val MAX_LANGUAGE = 10
        private const val MAX_PREVIEW = 1024

        /** Aturan Meta: huruf kecil, angka, dan garis bawah. */
        private val NAME_PATTERN = Regex("^[a-z0-9_]+$")

        /** `id`, `en`, `en_US`, `zh_HANS` — kode bahasa Meta. */
        private val LANGUAGE_PATTERN = Regex("^[A-Za-z]{2,3}(_[A-Za-z]{2,4})?$")

        /** Placeholder posisi Meta: `{{1}}`, `{{2}}`, … (spasi opsional di dalam kurung). */
        private val PARAM_PATTERN = Regex("\\{\\{\\s*(\\d+)\\s*}}")

        fun create(
            tenantId: UUID,
            name: String?,
            language: String?,
        ): NotificationMessageTemplate = NotificationMessageTemplate(
            id = UuidV7.generate(),
            tenantId = tenantId,
            name = validateName(name),
            language = validateLanguage(language),
            category = TemplateCategory.UTILITY,
            status = TemplateStatus.UNKNOWN,
            source = TemplateSource.MANUAL,
            metaTemplateId = null,
            bodyPreview = null,
            bodyParamCount = 1,
            syncedAt = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            name: String,
            language: String,
            category: TemplateCategory,
            status: TemplateStatus,
            source: TemplateSource,
            metaTemplateId: String?,
            bodyPreview: String?,
            bodyParamCount: Int,
            syncedAt: Instant?,
        ): NotificationMessageTemplate = NotificationMessageTemplate(
            id, tenantId, name, language, category, status, source,
            metaTemplateId, bodyPreview, bodyParamCount, syncedAt,
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
         * Jumlah placeholder posisi UNIK di body (`{{1}} halo {{1}}` = 1, bukan 2) — yang
         * dihitung Meta saat memvalidasi parameter kiriman.
         */
        fun countBodyParams(bodyText: String?): Int {
            if (bodyText.isNullOrBlank()) return 0
            return PARAM_PATTERN.findAll(bodyText).map { it.groupValues[1] }.toSet().size
        }

        private fun trimPreview(bodyText: String?): String? =
            bodyText?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_PREVIEW)
    }
}
