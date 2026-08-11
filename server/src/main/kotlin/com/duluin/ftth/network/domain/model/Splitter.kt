package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.network.domain.model.vo.SplitterRatio
import java.util.UUID

/**
 * Satu modul splitter pasif di dalam ODC atau ODP.
 *
 * Benda ini punya bentuk fisik yang tegas: SATU kaki masuk, sekian kaki keluar
 * sesuai rasionya. Ia dipisahkan dari kabinetnya karena satu ODC memang berisi
 * beberapa modul dengan rasio berbeda — dan ada pula ODC yang tak berisi satu
 * pun (murni cross-connect, splitternya menyusul di ODP). Kolom rasio tunggal
 * di kabinet tak pernah bisa mengaku "nol" maupun "tiga".
 *
 * Yang TIDAK ada di sini: siapa yang menyuapi inputnya dan apa yang menempel di
 * tiap kakinya. Itu urusan sambungan ([FiberConnection]) — modul ini cuma
 * menyediakan titik-titiknya, persis seperti benda aslinya.
 */
class Splitter private constructor(
    val id: UUID,
    val tenantId: UUID,
    /** ODC atau ODP; joint box & ODF tak pernah berisi splitter. */
    val ownerKind: ClosureKind,
    val ownerId: UUID,
    /** Label di badan modul, mis. "SPL-1". Unik di dalam kabinetnya. */
    val code: String,
    ratio: SplitterRatio,
    note: String?,
) {
    var ratio: SplitterRatio = ratio
        private set

    var note: String? = note
        private set

    /** Jumlah kaki keluar — batas atas nomor kaki yang boleh disambung. */
    val legCount: Int get() = ratio.splitCount

    /** Rugi sisipan modul ini; komponen tetap dalam anggaran redaman jalur. */
    val insertionLossDb: Double get() = ratio.insertionLossDb

    fun hasLeg(leg: Int): Boolean = leg in 1..legCount

    /**
     * Mengganti rasio & catatan.
     *
     * Menaikkan rasio (1:8 → 1:16) selalu boleh — kakinya bertambah. Menurunkan
     * ditolak selama masih ada kaki terpakai di luar rasio baru: modul 1:8 tak
     * punya kaki nomor 12, dan membiarkan datanya berubah berarti sehelai serat
     * yang benar-benar terpasang di lapangan mendadak menunjuk kaki yang menurut
     * sistem tak pernah ada.
     *
     * @param usedLegs nomor kaki yang sedang dipakai sambungan, disuplai
     *        pemanggil karena okupansi hidup di agregat sambungan.
     */
    fun update(ratio: SplitterRatio, note: String?, usedLegs: Set<Int>) {
        val orphaned = usedLegs.filter { it > ratio.splitCount }.sorted()
        if (orphaned.isNotEmpty()) {
            throw ConflictException(
                "Splitter $code tak bisa diturunkan ke ${ratio.label}: kaki " +
                    "${orphaned.joinToString(", ")} masih terpakai. Lepas dulu sambungannya.",
            )
        }
        this.ratio = ratio
        this.note = sanitizeNote(note)
    }

    companion object {
        const val MAX_NOTE_LENGTH = 200

        /** Kode bawaan modul pertama sebuah kabinet — sama dengan yang dipakai backfill V92. */
        const val DEFAULT_CODE = "SPL-1"

        fun create(
            tenantId: UUID,
            ownerKind: ClosureKind,
            ownerId: UUID,
            code: String,
            ratio: SplitterRatio,
            note: String? = null,
        ): Splitter {
            if (!ownerKind.hasSplitter) {
                throw ValidationException(
                    "${ownerKind.label} tak berisi splitter — di dalamnya serat disambung langsung ke serat",
                )
            }
            return Splitter(
                id = UuidV7.generate(),
                tenantId = tenantId,
                ownerKind = ownerKind,
                ownerId = ownerId,
                code = validateCode(code),
                ratio = ratio,
                note = sanitizeNote(note),
            )
        }

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            ownerKind: ClosureKind,
            ownerId: UUID,
            code: String,
            ratio: SplitterRatio,
            note: String?,
        ): Splitter = Splitter(id, tenantId, ownerKind, ownerId, code, ratio, note)

        /**
         * Ringkasan rasio sebuah kabinet untuk daftar & panel: "1:8", "1:8 ×2 · 1:16",
         * atau tanda pisah bila memang tak berisi splitter. Dipakai di ODC dan ODP
         * sekaligus supaya kalimatnya sama di mana pun muncul.
         */
        fun summarize(splitters: List<Splitter>): String {
            if (splitters.isEmpty()) return "—"
            return splitters
                .groupingBy { it.ratio }
                .eachCount()
                .entries
                .sortedBy { it.key.splitCount }
                .joinToString(" · ") { (ratio, count) ->
                    if (count > 1) "${ratio.label} ×$count" else ratio.label
                }
        }

        private fun validateCode(code: String): String {
            val trimmed = code.trim().uppercase()
            if (trimmed.isEmpty()) throw ValidationException("Kode splitter wajib diisi")
            if (trimmed.length > 40) throw ValidationException("Kode splitter maksimal 40 karakter")
            return trimmed
        }

        private fun sanitizeNote(note: String?): String? {
            val trimmed = note?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > MAX_NOTE_LENGTH) {
                throw ValidationException("Catatan splitter maksimal $MAX_NOTE_LENGTH karakter")
            }
            return trimmed
        }
    }
}
