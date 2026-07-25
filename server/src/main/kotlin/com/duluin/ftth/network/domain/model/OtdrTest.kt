package com.duluin.ftth.network.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.time.Instant
import java.util.UUID

/** Ujung kabel tempat pengukuran OTDR dimulai. */
enum class CableEnd {
    /** Ujung hulu — awal jalur ([com.duluin.ftth.common.domain.geo.RoutePath.start], sisi `from`). Lazimnya sisi OLT. */
    FROM,

    /** Ujung hilir — akhir jalur (sisi `to`). Dipakai bila reflektometer ditembakkan dari sisi pelanggan. */
    TO,
}

/** Jenis peristiwa yang terbaca reflektometer OTDR di sepanjang serat. */
enum class OtdrEventType {
    /** Serat putus total (loss besar, tak ada sinyal balik sesudahnya). */
    BREAK,

    /** Redaman berlebih di satu titik — bengkokan, jepitan, konektor kotor. */
    HIGH_LOSS,

    /** Pantulan tinggi — konektor mekanis atau ujung serat terbuka. */
    REFLECTION,

    /** Sambungan (splice) — penanda, bukan kerusakan. */
    SPLICE,

    /** Ujung serat. */
    END,
}

/**
 * Satu hasil uji OTDR terhadap sebuah kabel: reflektometer melaporkan jarak dari
 * ujung ukur ke sebuah peristiwa (mis. titik putus). Jarak itu dipetakan ke titik
 * perkiraan di geometri kabel supaya teknisi tahu kira-kira harus menggali di mana —
 * "titik perkiraan", bukan koordinat pasti, karena panjang serat memuat slack yang
 * tak tergambar di jalur.
 *
 * Agregat kecil tersendiri yang menunjuk [cableId] secara polos — seperti pola
 * lampiran di module lain — sehingga memuat kabel tidak ikut menyeret riwayat ujinya.
 */
class OtdrTest private constructor(
    val id: UUID,
    val tenantId: UUID,
    val cableId: UUID,
    /** Jarak dari [measuredFrom] ke peristiwa, dalam meter serat (bukan meter jalur). */
    val distanceMeters: Double,
    val measuredFrom: CableEnd,
    val eventType: OtdrEventType,
    /** Redaman terukur di titik itu (dB), bila alat melaporkannya. */
    val lossDb: Double?,
    val note: String?,
    val recordedBy: UUID,
    val recordedAt: Instant,
) {
    companion object {
        @Suppress("LongParameterList")
        fun record(
            tenantId: UUID,
            cableId: UUID,
            distanceMeters: Double,
            measuredFrom: CableEnd,
            eventType: OtdrEventType,
            lossDb: Double?,
            note: String?,
            recordedBy: UUID,
            recordedAt: Instant = Instant.now(),
        ): OtdrTest {
            if (distanceMeters < 0) throw ValidationException("Jarak OTDR tidak boleh negatif")
            if (!distanceMeters.isFinite()) throw ValidationException("Jarak OTDR tidak valid")
            lossDb?.let { if (!it.isFinite() || it < 0) throw ValidationException("Redaman OTDR tidak valid") }
            return OtdrTest(
                id = UuidV7.generate(),
                tenantId = tenantId,
                cableId = cableId,
                distanceMeters = distanceMeters,
                measuredFrom = measuredFrom,
                eventType = eventType,
                lossDb = lossDb,
                note = note?.trim()?.ifBlank { null },
                recordedBy = recordedBy,
                recordedAt = recordedAt,
            )
        }

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            cableId: UUID,
            distanceMeters: Double,
            measuredFrom: CableEnd,
            eventType: OtdrEventType,
            lossDb: Double?,
            note: String?,
            recordedBy: UUID,
            recordedAt: Instant,
        ) = OtdrTest(
            id, tenantId, cableId, distanceMeters, measuredFrom, eventType, lossDb, note, recordedBy, recordedAt,
        )
    }
}
