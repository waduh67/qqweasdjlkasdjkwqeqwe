package com.duluin.ftth.network.application.port.inbound

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.domain.model.CableEnd
import com.duluin.ftth.network.domain.model.ClosureKind
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
    /** Titik itu jatuh di kotak mana — jawaban yang benar-benar dibawa ke lapangan. */
    val placement: OtdrPlacementView,
)

/**
 * Angka OTDR yang sudah diterjemahkan jadi tempat.
 *
 * Koordinat perkiraan menjawab "di mana kira-kira di peta"; ini menjawab
 * pertanyaan yang sebetulnya diajukan orang di lapangan: "kotak yang mana".
 * Keduanya perlu — pin peta menuntun ke lokasi, nama kotak menentukan apa yang
 * dibawa dan apakah perlu menggali sama sekali.
 */
data class OtdrPlacementView(
    /** Satu kalimat siap baca, mis. "Di antara JB-03 dan ODP-05, sekitar 60 m sesudah JB-03." */
    val summary: String,
    /**
     * Saran tindakan, terpisah dari [summary] supaya daftar riwayat tetap bisa
     * dibaca sekilas: yang dicari saat memindai daftar adalah TEMPATnya, sedangkan
     * saran baru berguna pada baris yang sedang ditindaklanjuti.
     */
    val advice: String? = null,
    /** Peristiwa jatuh dalam toleransi sebuah kotak — periksa isi kotaknya dulu, jangan menggali. */
    val atClosure: Boolean,
    val nearestKind: ClosureKind? = null,
    val nearestId: UUID? = null,
    val nearestCode: String? = null,
    /** Selisih ke kotak terdekat; positif = sesudahnya (menjauh dari pangkal kabel). */
    val offsetMeters: Double? = null,
    /** Kotak terakhir sebelum titik, dan kotak pertama sesudahnya — ruas galian yang harus disisir. */
    val beforeCode: String? = null,
    val afterCode: String? = null,
    /** Semua patokan di sepanjang kabel ini, urut dari pangkal — penggarisnya, bukan cuma hasil. */
    val landmarks: List<OtdrLandmarkView>,
)

/** Sebuah kotak yang berdiri di sepanjang kabel, beserta jaraknya dari pangkal (meter optis). */
data class OtdrLandmarkView(
    val closureKind: ClosureKind,
    val closureId: UUID,
    val code: String,
    val name: String,
    val distanceMeters: Double,
    /** Kotak ini salah satu ujung kabelnya, bukan sadapan di tengah bentang. */
    val endpoint: Boolean,
)
