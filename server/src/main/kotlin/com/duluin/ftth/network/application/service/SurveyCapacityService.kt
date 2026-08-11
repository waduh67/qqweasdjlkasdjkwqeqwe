package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.network.OdpUsageProbe
import com.duluin.ftth.network.application.port.inbound.SurveyCableView
import com.duluin.ftth.network.application.port.inbound.SurveyCapacityUseCase
import com.duluin.ftth.network.application.port.inbound.SurveyCapacityView
import com.duluin.ftth.network.application.port.inbound.SurveyOdpView
import com.duluin.ftth.network.application.port.outbound.CableCoreRepository
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.SplitterRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.Odp
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Menjawab "alamat ini bisa dipasang atau tidak" dari data yang sudah ada.
 *
 * Semua bahannya sudah dicatat potongan-potongan sebelumnya — kapasitas kotak,
 * kaki splitter, status core, geometri kabel — tapi tersebar di layar yang
 * berbeda-beda, dan tak satu pun bisa dibuka sambil berdiri di depan rumah calon
 * pelanggan. Yang dikerjakan berkas ini menyatukannya menjadi satu jawaban untuk
 * satu titik koordinat.
 *
 * Yang membuatnya lebih dari sekadar "cari ODP terdekat": kotak penuh BUKAN
 * jawaban akhir. Selama ada selubung lewat di dekat situ dengan core menganggur,
 * alamat itu masih bisa dilayani — tinggal dikupas di tengah bentang. Sistem yang
 * cuma menghitung port kosong akan menolak pelanggan yang sebenarnya bisa dipasang.
 */
@Service
@Transactional(readOnly = true)
class SurveyCapacityService(
    private val odpRepository: OdpRepository,
    private val cableRepository: CableRepository,
    private val cableCoreRepository: CableCoreRepository,
    private val splitters: SplitterRepository,
    private val connections: FiberConnectionRepository,
    /** Diisi module lain (customer) — lihat [OdpUsageProbe] soal arah dependensinya. */
    private val usageProbes: List<OdpUsageProbe>,
) : SurveyCapacityUseCase {

    override fun nearby(location: Coordinate, radiusMeters: Double, limit: Int): SurveyCapacityView {
        val radius = radiusMeters.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
        val take = limit.coerceIn(1, MAX_ROWS)

        // Hanya aset yang boleh menerima layanan baru: kotak berstatus rencana
        // atau nonaktif bukan jawaban buat orang yang mau dipasang minggu ini.
        val odps = odpRepository.findNear(location, radius).filter { it.status.acceptsService() }
        val rows = odpRows(odps, location).take(take)
        val cables = cableRows(location, radius).take(take)

        val ready = rows.filter { it.ready }
        return SurveyCapacityView(
            location = location,
            radiusMeters = radius,
            verdict = verdict(ready, rows, cables, radius),
            serviceable = ready.isNotEmpty(),
            odps = rows,
            cables = cables,
            warnings = warnings(ready, cables),
        )
    }

    // ------------------------------------------------------------------
    // Kotak
    // ------------------------------------------------------------------

    private fun odpRows(odps: List<Odp>, from: Coordinate): List<SurveyOdpView> {
        if (odps.isEmpty()) return emptyList()
        val ids = odps.mapTo(HashSet()) { it.id }
        val used = countAttached(ids)
        val modulesByOdp = splitters.findByOwnerIds(ids)
        val legsUsed = connections.usedPortNumbersOfNodes(
            ConnectionPointKind.SPLITTER_OUT,
            modulesByOdp.values.flatten().mapTo(HashSet()) { it.id },
        )
        return odps.map { odp ->
            val modules = modulesByOdp[odp.id].orEmpty()
            val legs = modules.sumOf { it.legCount }
            val freeLegs = modules.sumOf { it.legCount - legsUsed[it.id].orEmpty().size }
            val usedPorts = (used[odp.id] ?: 0L).toInt()
            val freePorts = (odp.capacity - usedPorts).coerceAtLeast(0)
            // Kaki splitter cuma jadi penghalang kalau modulnya memang sudah didata.
            // Menolak kotak karena datanya belum lengkap akan membuat survey
            // menyalahkan lapangan atas kelalaian kantor.
            val legsKnown = legs > 0
            SurveyOdpView(
                odpId = odp.id,
                code = odp.code,
                name = odp.name,
                address = odp.address,
                location = odp.location,
                distanceMeters = meters(from.distanceTo(odp.location)),
                capacity = odp.capacity,
                usedPorts = usedPorts,
                freePorts = freePorts,
                splitterLegs = legs,
                freeLegs = freeLegs,
                ready = freePorts > 0 && (!legsKnown || freeLegs > 0),
                note = when {
                    freePorts <= 0 -> "Panelnya penuh — $usedPorts dari ${odp.capacity} port terpakai."
                    legsKnown && freeLegs <= 0 ->
                        "Port panel masih ada, tapi kaki splitternya sudah habis. Perlu modul tambahan " +
                            "sebelum port itu bisa dijual."
                    !legsKnown -> "Modul splitternya belum didata, jadi sisa kakinya belum bisa dipastikan."
                    else -> null
                },
            )
        }.sortedWith(compareByDescending<SurveyOdpView> { it.ready }.thenBy { it.distanceMeters })
    }

    private fun countAttached(odpIds: Set<UUID>): Map<UUID, Long> {
        val total = HashMap<UUID, Long>()
        usageProbes.forEach { probe ->
            probe.countAttachedTo(odpIds).forEach { (id, count) -> total.merge(id, count, Long::plus) }
        }
        return total
    }

    // ------------------------------------------------------------------
    // Selubung yang lewat
    // ------------------------------------------------------------------

    private fun cableRows(from: Coordinate, radius: Double): List<SurveyCableView> {
        val passing = cableRepository.findPassing(from, radius).filter { it.status.acceptsService() }
        if (passing.isEmpty()) return emptyList()
        val coresByCable = cableCoreRepository.findByCableIds(passing.map { it.id }).groupBy { it.cableId }
        return passing.mapNotNull { cable ->
            val free = coresByCable[cable.id].orEmpty().filter { it.available }
            if (free.isEmpty()) null else toRow(cable, from, free.map { it.coreNumber })
        }.sortedWith(compareBy({ it.distanceMeters }, { -it.freeCores }))
    }

    private fun toRow(cable: Cable, from: Coordinate, freeNumbers: List<Int>) = SurveyCableView(
        cableId = cable.id,
        code = cable.code,
        name = cable.name,
        cableType = cable.cableType,
        distanceMeters = meters(cable.route.distanceTo(from)),
        // Panjang optik selalu lebih dari panjang gambar (slack di tiang & kotak),
        // jadi letak kupasan diskalakan sama seperti di hasil OTDR — kalau tidak,
        // teknisi mencari titik yang bergeser puluhan meter dari angka di layar.
        tapDistanceMeters = meters(cable.route.distanceAlongTo(from) * opticalScale(cable)),
        coreCount = cable.coreCount,
        freeCores = freeNumbers.size,
        freeCoreNumbers = freeNumbers.take(MAX_CORE_NUMBERS),
    )

    private fun opticalScale(cable: Cable): Double {
        val drawn = cable.route.lengthMeters()
        return if (drawn <= 0.0) 1.0 else cable.lengthMeters / drawn
    }

    // ------------------------------------------------------------------
    // Kalimat yang diucapkan ke calon pelanggan
    // ------------------------------------------------------------------

    private fun verdict(
        ready: List<SurveyOdpView>,
        rows: List<SurveyOdpView>,
        cables: List<SurveyCableView>,
        radius: Double,
    ): String {
        val siap = ready.firstOrNull()
        if (siap != null) {
            return "Bisa dipasang. ${siap.code} berdiri ${jarak(siap.distanceMeters)} dari titik ini " +
                "dengan ${siap.freePorts} port kosong."
        }
        val selubung = cables.firstOrNull()
        if (selubung != null) {
            return "Belum ada kotak siap pakai, tapi kabel ${selubung.code} lewat " +
                "${jarak(selubung.distanceMeters)} dari sini dengan ${selubung.freeCores} core menganggur — " +
                "kotak baru bisa dikupas di situ tanpa menarik kabel dari kabinet."
        }
        if (rows.isNotEmpty()) {
            return "${rows.size} kotak dalam jangkauan, semuanya penuh, dan tak ada kabel lewat dengan " +
                "core menganggur. Perlu kabel baru dari kabinet terdekat."
        }
        return "Tak ada kotak maupun kabel dalam ${jarak(radius)} dari titik ini — alamat ini di luar " +
            "jangkauan jaringan yang terdata."
    }

    private fun warnings(ready: List<SurveyOdpView>, cables: List<SurveyCableView>): List<String> = buildList {
        val siap = ready.firstOrNull()
        if (siap != null && siap.distanceMeters > DROP_COMFORT_M) {
            add(
                "Kotak terdekat yang siap pakai ${jarak(siap.distanceMeters)} dari titik ini — drop sepanjang " +
                    "itu perlu tiang tambahan dan sebaiknya dihitung redamannya dulu.",
            )
        }
        if (ready.isEmpty() && cables.any { it.freeCores <= LAST_CORES }) {
            add(
                "Core menganggur di kabel yang lewat tinggal sedikit. Sisakan untuk perbaikan darurat, " +
                    "atau rencanakan kabel penggantinya sekarang.",
            )
        }
    }

    private fun jarak(meters: Double): String =
        if (meters >= 1_000) "${(meters / 100).roundToInt() / 10.0} km" else "${meters.roundToInt()} m"

    private fun meters(value: Double): Double = (value * 10).roundToInt() / 10.0

    private companion object {
        const val MIN_RADIUS_M = 25.0
        const val MAX_RADIUS_M = 2_000.0
        const val MAX_ROWS = 20
        const val MAX_CORE_NUMBERS = 12
        /** Panjang drop yang masih wajar dipasang satu tim tanpa tiang tambahan. */
        const val DROP_COMFORT_M = 250.0
        const val LAST_CORES = 2
    }
}
