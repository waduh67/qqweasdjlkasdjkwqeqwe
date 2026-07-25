package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.CableEnd
import com.duluin.ftth.network.domain.model.OtdrEventType
import java.time.Instant
import java.util.UUID

/**
 * Mencatat & menampilkan hasil uji OTDR sebuah kabel, lengkap dengan titik
 * perkiraan peristiwanya di peta.
 *
 * Hidup di module network karena bertumpu pada geometri kabel (milik network)
 * untuk memetakan jarak → koordinat; module lain cukup melihat [OtdrTestView].
 */
interface OtdrTestUseCase {

    /** Mencatat satu hasil uji pada kabel dan mengembalikannya beserta titik perkiraan. */
    fun record(cableId: UUID, command: RecordOtdrTestCommand): OtdrTestView

    /** Riwayat uji sebuah kabel, terbaru dulu, tiap entri dengan titik perkiraannya. */
    fun list(cableId: UUID): List<OtdrTestView>

    fun delete(cableId: UUID, testId: UUID)
}

data class RecordOtdrTestCommand(
    val distanceMeters: Double,
    val measuredFrom: CableEnd,
    val eventType: OtdrEventType,
    val lossDb: Double?,
    val note: String?,
    /** Waktu pengukuran di lapangan; default sekarang bila tak diisi. */
    val recordedAt: Instant?,
)

data class OtdrTestView(
    val id: UUID,
    val cableId: UUID,
    val distanceMeters: Double,
    val measuredFrom: CableEnd,
    val eventType: OtdrEventType,
    val lossDb: Double?,
    val note: String?,
    val recordedBy: UUID,
    val recordedByName: String?,
    val recordedAt: Instant,
    /** Titik perkiraan peristiwa di jalur kabel; `null` bila geometri tak bisa diresolusi. */
    val estimatedPoint: Coordinate?,
    /** Jarak uji melampaui panjang kabel — titik dijepit ke ujung, kemungkinan gangguan di segmen berikutnya. */
    val beyondCable: Boolean,
    /** Panjang kabel (termasuk slack) sebagai acuan untuk jarak uji. */
    val cableLengthMeters: Double,
)
