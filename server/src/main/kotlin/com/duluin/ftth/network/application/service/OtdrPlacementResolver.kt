package com.duluin.ftth.network.application.service

import com.duluin.ftth.network.application.port.inbound.OtdrLandmarkView
import com.duluin.ftth.network.application.port.inbound.OtdrPlacementView
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import org.springframework.stereotype.Component
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Menerjemahkan angka OTDR jadi kalimat yang bisa dikerjakan.
 *
 * Reflektometer menjawab "1.847 meter". Yang dibutuhkan orang yang akan berangkat
 * membawa sekop bukan itu, melainkan "jatuh di JB-03" atau "antara ODP-2 dan
 * ODP-3, sekitar 60 m sesudah ODP-2". Koordinat perkiraan di peta sudah membantu,
 * tapi ia tetap menyuruh orang menebak benda mana yang paling dekat — padahal
 * sistem sudah tahu persis benda apa saja yang berdiri di sepanjang kabel itu.
 *
 * Yang dipakai sebagai patokan bukan daftar aset di sekitar jalur, melainkan
 * kotak yang KABELNYA BENAR-BENAR DIBUKA di sana — yaitu closure yang punya
 * sambungan pada salah satu core kabel ini. Itu pembedaan yang penting: ODP yang
 * kebetulan berdiri 5 meter dari jalur tapi disuapi kabel lain bukan patokan,
 * ia cuma tetangga.
 *
 * Semua jarak di sini adalah meter OPTIS (termasuk slack), satuan yang sama
 * dengan yang dilaporkan alat — sebab membandingkan meter serat dengan meter
 * peta persis kesalahan yang membuat orang menggali di tempat yang salah.
 */
@Component
class OtdrPlacementResolver(
    private val connections: FiberConnectionRepository,
    private val closures: ClosureLookup,
) {

    /**
     * Toleransi "dianggap jatuh tepat di kotak ini".
     *
     * Dua sumber galat yang menumpuk. Alatnya sendiri meleset beberapa meter
     * karena indeks bias serat yang disetel tak pernah persis. Lebih besar lagi:
     * slack diperlakukan tersebar rata sepanjang kabel padahal sesungguhnya
     * menumpuk di tiang dan di dalam closure, dan salah taksir itu tumbuh seiring
     * jarak. Karena itu toleransinya proporsional, dengan lantai untuk bentang
     * pendek.
     */
    private fun toleranceAt(meters: Double): Double = max(FLOOR_TOLERANCE_M, meters * PROPORTIONAL_TOLERANCE)

    /**
     * @param landmarks patokan kabel dari [landmarksOf] — sengaja diminta dari luar
     *        supaya riwayat uji sebuah kabel cukup sekali menanyakannya ke basis data,
     *        bukan sekali per baris riwayat.
     * @param fromStart jarak peristiwa dari PANGKAL jalur kabel (ujung `from`),
     *        dalam meter optis — arah ukur sudah dinormalkan pemanggil.
     */
    fun resolve(landmarks: List<OtdrLandmarkView>, fromStart: Double): OtdrPlacementView {
        if (landmarks.isEmpty()) {
            return OtdrPlacementView(
                summary = "Belum ada kotak yang tercatat di sepanjang kabel ini.",
                advice = "Catat sambungannya di meja kerja splicing supaya hasil OTDR berikutnya bisa " +
                    "langsung menunjuk benda, bukan cuma titik taksiran di peta.",
                atClosure = false,
                landmarks = emptyList(),
            )
        }

        val nearest = landmarks.minBy { abs(it.distanceMeters - fromStart) }
        val offset = fromStart - nearest.distanceMeters
        val atClosure = abs(offset) <= toleranceAt(fromStart)

        val before = landmarks.lastOrNull { it.distanceMeters <= fromStart }
        val after = landmarks.firstOrNull { it.distanceMeters > fromStart }

        return OtdrPlacementView(
            summary = summarize(atClosure, nearest, offset, before, after),
            advice = if (atClosure) {
                "Buka kotaknya dulu sebelum menggali — gangguan di dalam closure jauh lebih sering " +
                    "daripada serat putus di tengah bentang."
            } else {
                null
            },
            atClosure = atClosure,
            nearestKind = nearest.closureKind,
            nearestId = nearest.closureId,
            nearestCode = nearest.code,
            offsetMeters = round(offset),
            beforeCode = before?.code,
            afterCode = after?.code,
            landmarks = landmarks,
        )
    }

    private fun summarize(
        atClosure: Boolean,
        nearest: OtdrLandmarkView,
        offset: Double,
        before: OtdrLandmarkView?,
        after: OtdrLandmarkView?,
    ): String {
        if (atClosure) return "Jatuh di ${nearest.code}, selisih ${meters(abs(offset))} dari titik kotaknya."
        val arah = if (offset > 0) "sesudah" else "sebelum"
        return when {
            before != null && after != null ->
                "Di antara ${before.code} dan ${after.code}, sekitar " +
                    "${meters(abs(offset))} $arah ${nearest.code}."
            // Sebelum patokan pertama atau sesudah patokan terakhir: masih di
            // dalam kabel, tapi di ruas yang tak diapit dua kotak.
            after != null -> "Sebelum ${after.code}, sekitar ${meters(abs(offset))} dari ${nearest.code}."
            else -> "Sesudah ${nearest.code}, sekitar ${meters(abs(offset))} dari sana."
        }
    }

    /**
     * Kotak-kotak yang benar-benar membuka kabel ini, urut sepanjang jalurnya.
     *
     * Jaraknya diambil dari letak kotak di peta (bukan angka yang diketik orang),
     * lalu diskalakan dari meter peta ke meter optis dengan perbandingan yang
     * sama dipakai seluruh sistem: panjang-dengan-slack dibagi panjang-tergambar.
     */
    fun landmarksOf(cable: Cable): List<OtdrLandmarkView> {
        val drawn = cable.route.lengthMeters()
        if (drawn <= 0.0) return emptyList()
        val scale = cable.lengthMeters / drawn

        val touched = connections.findByCableId(cable.id)
            .map { it.closureKind to it.closureId }
            .distinct()
        val landmarks = touched.mapNotNull { (kind, id) ->
            closures.find(kind, id)?.let { ref ->
                OtdrLandmarkView(
                    closureKind = ref.kind,
                    closureId = ref.id,
                    code = ref.code,
                    name = ref.name,
                    distanceMeters = cable.route.distanceAlongTo(ref.location) * scale,
                    // Ujung kabel yang kebetulan juga jadi titik sambung tetap
                    // ditandai sebagai ujung: ia patokan yang paling dikenali
                    // teknisi, dan menyembunyikan perannya tak menguntungkan siapa pun.
                    endpoint = cable.from.id == ref.id || cable.to.id == ref.id,
                )
            }
        }
        return (landmarks + endpointLandmarks(cable, scale, landmarks))
            .distinctBy { it.closureId }
            .sortedBy { it.distanceMeters }
            .map { it.copy(distanceMeters = round(it.distanceMeters)) }
    }

    /**
     * Kedua ujung kabel, ditambahkan bila belum muncul sebagai titik sambung.
     *
     * Kabel yang seratnya belum didata pun tetap punya dua ujung yang berdiri
     * nyata di lapangan, dan "sekitar 80 m sebelum ODP-05" sudah jauh lebih
     * berguna daripada sebuah pin di peta tanpa nama.
     */
    private fun endpointLandmarks(cable: Cable, scale: Double, existing: List<OtdrLandmarkView>): List<OtdrLandmarkView> =
        listOf(cable.from, cable.to).mapNotNull { endpoint ->
            if (existing.any { it.closureId == endpoint.id }) return@mapNotNull null
            val kind = endpoint.kind.asClosureKind() ?: return@mapNotNull null
            closures.find(kind, endpoint.id)?.let { ref ->
                OtdrLandmarkView(
                    closureKind = ref.kind,
                    closureId = ref.id,
                    code = ref.code,
                    name = ref.name,
                    distanceMeters = cable.route.distanceAlongTo(ref.location) * scale,
                    endpoint = true,
                )
            }
        }

    private fun NetworkNodeKind.asClosureKind(): ClosureKind? = when (this) {
        NetworkNodeKind.ODC -> ClosureKind.ODC
        NetworkNodeKind.ODP -> ClosureKind.ODP
        NetworkNodeKind.JOINT_BOX -> ClosureKind.JOINT_BOX
        NetworkNodeKind.ODF -> ClosureKind.ODF
        // Site, OLT, dan rumah pelanggan bukan kotak yang bisa dibuka teknisi
        // serat; menyebutnya sebagai patokan galian cuma menyesatkan.
        NetworkNodeKind.SITE, NetworkNodeKind.OLT, NetworkNodeKind.CUSTOMER -> null
    }

    /** Meter bulat: ketelitian di bawah itu tak dimiliki alat maupun taksiran slack-nya. */
    private fun meters(value: Double): String = "${value.roundToInt()} m"

    private fun round(value: Double): Double = String.format(Locale.ROOT, "%.1f", value).toDouble()

    private companion object {
        const val FLOOR_TOLERANCE_M = 25.0
        const val PROPORTIONAL_TOLERANCE = 0.02
    }
}
