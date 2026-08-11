package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.network.OdpUsageProbe
import com.duluin.ftth.network.application.port.inbound.ConnectionPointCommand
import com.duluin.ftth.network.application.port.inbound.FiberHopView
import com.duluin.ftth.network.application.port.inbound.FiberPathView
import com.duluin.ftth.network.application.port.inbound.PonClosureLoadView
import com.duluin.ftth.network.application.port.inbound.PonPortLoadView
import com.duluin.ftth.network.application.port.inbound.TraceFiberPathUseCase
import com.duluin.ftth.network.application.port.outbound.CableCoreRepository
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.application.port.outbound.SplitterRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableCore
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPoint
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.FiberConnection
import com.duluin.ftth.network.domain.model.FiberHopKind
import com.duluin.ftth.network.domain.model.FiberTraceEnd
import com.duluin.ftth.network.domain.model.OdfPortSide
import com.duluin.ftth.network.domain.model.OpticalBudget
import com.duluin.ftth.network.domain.model.PonCapacity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Penelusur jalur serat & penghitung anggaran redaman.
 *
 * Jalannya persis seperti teknisi menelusuri gangguan: berdiri di satu titik,
 * lihat ia disambung ke apa, pindah ke sana, ulangi — sampai bertemu port PON
 * atau sampai tak ada sambungan berikutnya. Yang membuatnya bisa dikerjakan
 * mesin adalah aturan potongan B: satu titik dipakai satu sambungan, jadi dari
 * mana pun kita berdiri, langkah berikutnya tunggal.
 *
 * Dua macam langkah bergantian, dan membedakannya adalah kunci seluruh berkas
 * ini:
 *
 * - lewat SAMBUNGAN — dua serat yang disatukan orang di dalam sebuah kotak;
 * - lewat BENDA — kaki splitter ke kaki masuknya, sisi belakang port ODF ke sisi
 *   depannya, atau sepanjang sehelai core dari kotak yang satu ke kotak berikutnya.
 *
 * Karena itu "sudah sampai mana" tak cukup disimpan sebagai titik saja; harus
 * ikut dicatat kita tiba di situ lewat sambungan atau lewat bendanya. Tanpa itu,
 * penelusuran yang tiba di kaki splitter akan bingung: masuk ke modulnya, atau
 * balik ke serat yang barusan dilewati.
 */
@Service
@Transactional(readOnly = true)
class FiberTraceService(
    private val connections: FiberConnectionRepository,
    private val cableRepository: CableRepository,
    private val cableCoreRepository: CableCoreRepository,
    private val splitters: SplitterRepository,
    private val ponPortRepository: PonPortRepository,
    private val oltRepository: OltRepository,
    private val odcRepository: OdcRepository,
    private val odpRepository: OdpRepository,
    private val closures: ClosureLookup,
    /** Diisi module lain (customer) — lihat [OdpUsageProbe] soal arah dependensinya. */
    private val usageProbes: List<OdpUsageProbe>,
) : TraceFiberPathUseCase {

    override fun traceUpstream(point: ConnectionPointCommand): FiberPathView {
        val start = point.toDomain()
        return when (start.kind) {
            // Sehelai core punya dua ujung dan keduanya sah jadi titik berangkat;
            // yang hulu adalah yang bermuara di OLT, dan satu-satunya cara tahu
            // adalah mencoba. Tiap ujung dicoba dengan berpura-pura DATANG dari
            // ujung seberangnya, supaya langkah pertama menyeberangi sambungan
            // yang sedang diuji. Kalau dua-duanya sampai OLT, datanya yang salah —
            // sebuah core tak mungkin disuapi dari dua sumber.
            ConnectionPointKind.CORE -> {
                val touching = connections.findByCoreIds(listOf(requireNotNull(start.coreId)))
                val attempts = touching.map { via ->
                    walk(start, arrivedByConnection = false, lastConnectionId = touching.firstOrNull { it.id != via.id }?.id)
                }
                val reaching = attempts.filter { it.end == FiberTraceEnd.SOURCE }
                when {
                    reaching.size > 1 -> reaching.first().copy(end = FiberTraceEnd.AMBIGUOUS)
                    reaching.size == 1 -> reaching.first()
                    else -> attempts.maxByOrNull { it.hops.size }
                        ?: walk(start, arrivedByConnection = false, lastConnectionId = null)
                }
            }
            // Sisi BELAKANG port ODF menghadap kabel luar, jadi hulunya ada di
            // seberang adapter — sisi depan. Titik lain berangkat lewat
            // sambungannya sendiri.
            ConnectionPointKind.ODF_PORT ->
                walk(start, arrivedByConnection = start.portSide == OdfPortSide.BACK, lastConnectionId = null)

            ConnectionPointKind.SPLITTER_OUT ->
                walk(start, arrivedByConnection = true, lastConnectionId = null)

            else -> walk(start, arrivedByConnection = false, lastConnectionId = null)
        }
    }

    override fun traceClosure(closureKind: ClosureKind, closureId: UUID): List<FiberPathView> {
        val closure = closures.require(closureKind, closureId)
        return when (closure.kind) {
            // Satu jalur per modul: kaki masuknya adalah satu-satunya pintu hulu
            // sebuah splitter, apa pun jumlah kakinya di sisi hilir.
            ClosureKind.ODC, ClosureKind.ODP -> splitters.findByOwnerId(closure.id).map { splitter ->
                traceUpstream(
                    ConnectionPointCommand(kind = ConnectionPointKind.SPLITTER_IN, nodeId = splitter.id),
                )
            }
            // Satu jalur per port yang benar-benar dipakai — rak 144 port yang
            // baru terisi tiga tak boleh menghasilkan 144 telusur kosong.
            ClosureKind.ODF -> {
                val used = connections.usedPortNumbersOfNodes(ConnectionPointKind.ODF_PORT, setOf(closure.id))
                used[closure.id].orEmpty().sorted().map { port ->
                    traceUpstream(
                        ConnectionPointCommand(
                            kind = ConnectionPointKind.ODF_PORT,
                            nodeId = closure.id,
                            portNumber = port,
                            portSide = OdfPortSide.BACK,
                        ),
                    )
                }
            }
            ClosureKind.JOINT_BOX -> emptyList()
        }
    }

    override fun tracePonPortLoad(ponPortId: UUID): PonPortLoadView {
        val ponPort = ponPortRepository.findById(ponPortId)
            ?: throw NotFoundException("PON port $ponPortId tidak ditemukan")
        val olt = oltRepository.findById(ponPort.oltId)

        // Dua sumber kebenaran, sengaja dibaca dua-duanya. Graf sambungan adalah
        // yang benar; tautan ODC→PON adalah yang ADA di hampir semua tenant
        // sekarang. Membandingkannya justru yang paling berguna: selisihnya
        // menunjuk persis kabinet mana yang catatan splicingnya belum masuk.
        val spliced = descend(ponPortId)
        val linkedOdpIds = linkedOdpIds(ponPortId)

        val fromSplicing = spliced.isNotEmpty()
        val reached = if (fromSplicing) spliced else fallback(ponPortId, linkedOdpIds)

        val splittersByOwner = splitters.findByOwnerIds(reached.mapTo(HashSet()) { it.ref.id })
        val usedLegs = connections.usedPortNumbersOfNodes(
            ConnectionPointKind.SPLITTER_OUT,
            splittersByOwner.values.flatMapTo(HashSet()) { modules -> modules.map { it.id } },
        )
        val odpIds = reached.filterTo(HashSet()) { it.ref.kind == ClosureKind.ODP }.mapTo(HashSet()) { it.ref.id }
        val onuByOdp = countOnus(odpIds)

        val rows = reached.map { hit ->
            val modules = splittersByOwner[hit.ref.id].orEmpty()
            PonClosureLoadView(
                closureKind = hit.ref.kind,
                closureId = hit.ref.id,
                code = hit.ref.code,
                name = hit.ref.name,
                depth = hit.depth,
                splitterLegs = modules.sumOf { it.legCount },
                usedLegs = modules.sumOf { usedLegs[it.id].orEmpty().size },
                onuCount = (onuByOdp[hit.ref.id] ?: 0L).toInt(),
            )
        }

        val onuCount = rows.sumOf { it.onuCount }
        return PonPortLoadView(
            ponPortId = ponPort.id,
            label = ponPort.label,
            oltId = ponPort.oltId,
            oltCode = olt?.code,
            oltName = olt?.name,
            closures = rows,
            splitterLegs = rows.sumOf { it.splitterLegs },
            usedLegs = rows.sumOf { it.usedLegs },
            onuCount = onuCount,
            onuLimit = PonCapacity.GPON_MAX_ONU,
            loadPercent = (onuCount * 100.0 / PonCapacity.GPON_MAX_ONU).roundToInt(),
            fromSplicing = fromSplicing,
            warnings = loadWarnings(onuCount, fromSplicing, odpIds, linkedOdpIds),
        )
    }

    // ------------------------------------------------------------------
    // Penelusuran
    // ------------------------------------------------------------------

    /**
     * Batas langkah. Jalur terpanjang yang masuk akal — OLT → ODF → feeder →
     * ODC → distribusi → ODP → drop, dengan beberapa joint box di tiap ruas —
     * masih jauh di bawah angka ini. Yang melebihinya bukan jaringan besar,
     * melainkan data yang berputar; dan penelusuran yang tak pernah berhenti
     * lebih buruk daripada jawaban "dihentikan".
     */
    private val maxHops = 64

    private fun walk(
        start: ConnectionPoint,
        arrivedByConnection: Boolean,
        lastConnectionId: UUID?,
    ): FiberPathView {
        val trail = ArrayList<Trace>()
        val seen = HashSet<ConnectionPoint>()
        var current = start
        var viaConnection = arrivedByConnection
        var lastConnection = lastConnectionId

        var steps = 0
        while (steps++ < maxHops) {
            if (!seen.add(current)) return build(start, trail, FiberTraceEnd.LOOP)
            when (val step = step(current, viaConnection, lastConnection)) {
                is Step.Stop -> {
                    trail += step.trail
                    return build(start, trail, step.reason)
                }
                is Step.Go -> {
                    trail += step.trail
                    current = step.next
                    viaConnection = step.viaConnection
                    lastConnection = step.lastConnection ?: lastConnection
                }
            }
        }
        return build(start, trail, FiberTraceEnd.TOO_LONG)
    }

    private sealed interface Step {
        data class Go(
            val trail: List<Trace>,
            val next: ConnectionPoint,
            val viaConnection: Boolean,
            val lastConnection: UUID?,
        ) : Step

        data class Stop(val trail: List<Trace>, val reason: FiberTraceEnd) : Step
    }

    private fun step(point: ConnectionPoint, viaConnection: Boolean, lastConnection: UUID?): Step =
        when (point.kind) {
            ConnectionPointKind.PON_PORT -> Step.Stop(listOf(ponHop(point)), FiberTraceEnd.SOURCE)

            // ONU cuma jadi titik BERANGKAT: kalau penelusuran tiba di sini dari
            // arah hulu, berarti kita berjalan ke hilir dan itu bukan tugas
            // penelusur ini.
            ConnectionPointKind.ONU ->
                if (viaConnection) Step.Stop(emptyList(), FiberTraceEnd.SUBSCRIBER)
                else crossConnection(point, lastConnection)

            ConnectionPointKind.SPLITTER_OUT ->
                if (viaConnection) crossSplitter(point) else crossConnection(point, lastConnection)

            ConnectionPointKind.SPLITTER_IN ->
                if (viaConnection) Step.Stop(emptyList(), FiberTraceEnd.SUBSCRIBER)
                else crossConnection(point, lastConnection)

            ConnectionPointKind.ODF_PORT ->
                if (viaConnection) crossAdapter(point) else crossConnection(point, lastConnection)

            ConnectionPointKind.CORE -> crossFiber(point, lastConnection)
        }

    /** Kaki keluar → kaki masuk modul yang sama; di sinilah rugi sisipan dibayar. */
    private fun crossSplitter(point: ConnectionPoint): Step {
        val splitter = splitters.findById(requireNotNull(point.nodeId))
            ?: return Step.Stop(emptyList(), FiberTraceEnd.DEAD_END)
        val closure = closures.find(splitter.ownerKind, splitter.ownerId)
        return Step.Go(
            trail = listOf(
                Trace(
                    kind = FiberHopKind.SPLITTER,
                    label = "${splitter.code} kaki ${point.portNumber}",
                    detail = "${splitter.ratio.label} · rugi sisipan ${format(splitter.insertionLossDb)} dB",
                    lossDb = splitter.insertionLossDb,
                    measured = false,
                    closure = closure,
                    nodeId = splitter.id,
                ),
            ),
            next = ConnectionPoint.node(ConnectionPointKind.SPLITTER_IN, splitter.id),
            viaConnection = false,
            lastConnection = null,
        )
    }

    /**
     * Menyeberangi sebuah adapter ODF: belakang ke depan, atau sebaliknya. Tak
     * dibebani rugi karena ongkos kawin konektornya sudah ikut terhitung pada
     * sambungan patchcord di sisi depan — membebankannya di sini berarti
     * menghitung benda yang sama dua kali.
     */
    private fun crossAdapter(point: ConnectionPoint): Step {
        val odfId = requireNotNull(point.nodeId)
        val odf = closures.find(ClosureKind.ODF, odfId)
        val other = if (point.portSide == OdfPortSide.BACK) OdfPortSide.FRONT else OdfPortSide.BACK
        return Step.Go(
            trail = listOf(
                Trace(
                    kind = FiberHopKind.ODF_PORT,
                    label = "${odf?.code ?: "ODF"} port ${point.portNumber}",
                    detail = "${point.portSide?.label} → ${other.label}",
                    lossDb = 0.0,
                    measured = false,
                    closure = odf,
                    nodeId = odfId,
                ),
            ),
            next = ConnectionPoint.odfPort(odfId, requireNotNull(point.portNumber), other),
            viaConnection = false,
            lastConnection = null,
        )
    }

    /** Menyeberangi sambungan yang memakai titik ini — satu langkah di dalam kotak. */
    private fun crossConnection(point: ConnectionPoint, lastConnection: UUID?): Step {
        val connection = connections.findByNodePoint(point.kind, requireNotNull(point.nodeId), point.portNumber, point.portSide)
        if (connection == null || connection.id == lastConnection) {
            return Step.Stop(emptyList(), FiberTraceEnd.DEAD_END)
        }
        val opposite = connection.opposite(point) ?: return Step.Stop(emptyList(), FiberTraceEnd.AMBIGUOUS)
        return Step.Go(
            trail = listOf(spliceHop(connection)),
            next = opposite,
            viaConnection = true,
            lastConnection = connection.id,
        )
    }

    /**
     * Menyusuri sehelai core dari kotak tempat kita berdiri sampai kotak
     * berikutnya, lalu langsung menyeberangi sambungan di sana.
     *
     * Dua hop sekaligus karena core memang tak punya "titik tengah" yang bisa
     * disinggahi: begitu masuk seratnya, perhentian berikutnya adalah tray di
     * ujung sana. Panjang ruasnya diukur dari selisih titik kupas kedua kotak di
     * sepanjang rute — bukan panjang kabel utuh, sebab kabel 8 core yang
     * melewati delapan ODP dikupas di tempat berbeda-beda dan tiap ruas punya
     * redamannya sendiri.
     */
    private fun crossFiber(point: ConnectionPoint, lastConnection: UUID?): Step {
        val coreId = requireNotNull(point.coreId)
        val far = connections.findByCoreIds(listOf(coreId)).filter { it.id != lastConnection }
        if (far.isEmpty()) return Step.Stop(emptyList(), FiberTraceEnd.DEAD_END)
        if (far.size > 1) return Step.Stop(emptyList(), FiberTraceEnd.AMBIGUOUS)

        val onward = far.first()
        val core = cableCoreRepository.findById(coreId)
        val cable = core?.let { cableRepository.findById(it.cableId) }
        val here = lastConnection?.let { connections.findById(it) }
        val fiber = fiberHop(core, cable, here, onward)
        val opposite = onward.opposite(point) ?: return Step.Stop(listOf(fiber), FiberTraceEnd.AMBIGUOUS)
        return Step.Go(
            trail = listOf(fiber, spliceHop(onward)),
            next = opposite,
            viaConnection = true,
            lastConnection = onward.id,
        )
    }

    // ------------------------------------------------------------------
    // Muatan port PON — penelusuran ke arah HILIR
    // ------------------------------------------------------------------

    /**
     * Sebuah kotak yang tercapai dari port PON, beserta jaraknya dalam ruas serat.
     */
    private data class Reached(val ref: ClosureRef, val depth: Int)

    /** Posisi kerja penelusuran hilir; sama seperti telusur hulu, "di mana" itu sepasang. */
    private data class Cursor(
        val point: ConnectionPoint,
        val viaConnection: Boolean,
        val lastConnection: UUID?,
        val depth: Int,
    )

    /**
     * Batas simpul yang disinggahi saat menuruni satu port PON. Port yang sehat
     * menaungi puluhan kotak, bukan ribuan; angka yang melebihinya berarti data
     * sambungannya bercabang tak wajar, dan penelusuran yang tak berhenti lebih
     * buruk daripada jawaban yang terpotong.
     */
    private val maxDownstreamVisits = 512

    /**
     * Menuruni graf sambungan dari sebuah port PON dan mengumpulkan kotak yang
     * benar-benar tersuapi olehnya.
     *
     * Bedanya dengan telusur hulu cuma satu, tapi mendasar: di kaki MASUK
     * splitter, jalur ke hilir tidak tunggal — ia pecah ke seluruh kaki keluar
     * yang tersambung. Karena itu bentuknya antrean (BFS), bukan rantai; dan
     * karena itu pula hasilnya rekap, bukan daftar hop.
     *
     * Sisa langkahnya — menyeberangi sambungan, adapter ODF, dan sehelai core —
     * memakai potongan yang sama persis dengan telusur hulu. Serat memang tak
     * peduli ke arah mana cahaya lewat.
     */
    private fun descend(ponPortId: UUID): List<Reached> {
        val found = LinkedHashMap<UUID, Reached>()
        val seen = HashSet<ConnectionPoint>()
        val queue = ArrayDeque<Cursor>()
        queue += Cursor(
            point = ConnectionPoint.node(ConnectionPointKind.PON_PORT, ponPortId),
            viaConnection = false,
            lastConnection = null,
            depth = 0,
        )

        var visits = 0
        while (queue.isNotEmpty() && visits++ < maxDownstreamVisits) {
            val cursor = queue.removeFirst()
            if (!seen.add(cursor.point)) continue
            for (next in descendStep(cursor, found)) {
                if (next.point !in seen) queue += next
            }
        }
        // Terurut dari yang terdekat: rak dulu, kabinet, lalu kotak pelanggan —
        // urutan yang sama dengan orang menyusuri jaringannya di lapangan.
        return found.values.sortedBy { it.depth }
    }

    /**
     * Satu langkah ke hilir. Mengembalikan lanjutan yang mungkin (kosong = ujung)
     * dan menitipkan kotak yang dilewati ke [found].
     */
    private fun descendStep(cursor: Cursor, found: MutableMap<UUID, Reached>): List<Cursor> {
        val point = cursor.point
        // Kaki masuk splitter: satu-satunya titik yang bercabang. Kaki keluar yang
        // menganggur sengaja tak diantre — ia memang belum menyuapi apa pun.
        if (point.kind == ConnectionPointKind.SPLITTER_IN && cursor.viaConnection) {
            val splitterId = requireNotNull(point.nodeId)
            val legs = connections.usedPortNumbersOfNodes(ConnectionPointKind.SPLITTER_OUT, setOf(splitterId))
            return legs[splitterId].orEmpty().map { leg ->
                cursor.copy(
                    point = ConnectionPoint.node(ConnectionPointKind.SPLITTER_OUT, splitterId, leg),
                    viaConnection = false,
                    lastConnection = null,
                )
            }
        }
        // Kaki KELUAR yang didatangi lewat sambungan berarti kita menaiki serat,
        // bukan menuruninya — itu urusan telusur hulu, bukan di sini.
        if (point.kind == ConnectionPointKind.SPLITTER_OUT && cursor.viaConnection) return emptyList()
        if (point.kind == ConnectionPointKind.ONU) return emptyList()
        if (point.kind == ConnectionPointKind.PON_PORT && cursor.viaConnection) return emptyList()

        val step = when (point.kind) {
            ConnectionPointKind.CORE -> crossFiber(point, cursor.lastConnection)
            ConnectionPointKind.ODF_PORT ->
                if (cursor.viaConnection) crossAdapter(point) else crossConnection(point, cursor.lastConnection)
            else -> crossConnection(point, cursor.lastConnection)
        }
        val trail = when (step) {
            is Step.Go -> step.trail
            is Step.Stop -> step.trail
        }
        // Kedalaman kotak = jumlah ruas serat sampai ke sana, jadi ia bertambah
        // TEPAT saat hop serat dilewati — bukan sesudah seluruh langkah selesai.
        // Satu langkah menyeberangi core memuat dua hop sekaligus (seratnya dan
        // sambungan di ujung sana), dan yang di ujung sana itu sudah satu ruas jauhnya.
        var depth = cursor.depth
        trail.forEach { hop ->
            if (hop.kind == FiberHopKind.FIBER) depth++
            hop.closure?.let { ref ->
                // Kotak yang tercapai lewat dua kaki splitter berbeda tetap satu
                // baris, dengan jarak yang paling dekat.
                val existing = found[ref.id]
                if (existing == null || depth < existing.depth) found[ref.id] = Reached(ref, depth)
            }
        }
        if (step !is Step.Go) return emptyList()
        return listOf(
            Cursor(
                point = step.next,
                viaConnection = step.viaConnection,
                lastConnection = step.lastConnection ?: cursor.lastConnection,
                depth = depth,
            ),
        )
    }

    /** ODP yang menurut tautan lama (ODC→PON port) menggantung di port ini. */
    private fun linkedOdpIds(ponPortId: UUID): Set<UUID> {
        val odcIds = odcRepository.findIdsByPonPortIds(setOf(ponPortId))
        return if (odcIds.isEmpty()) emptySet() else odpRepository.findIdsByOdcIds(odcIds)
    }

    /**
     * Isi port menurut tautan lama, dipakai HANYA bila catatan splicing belum ada
     * sama sekali. Tanpa ini, tenant yang belum sempat mendata sambungannya akan
     * membaca "0 ONU" pada port yang sebenarnya sudah penuh — dan angka nol yang
     * salah tak akan pernah memicu peringatan apa pun.
     */
    private fun fallback(ponPortId: UUID, odpIds: Set<UUID>): List<Reached> {
        val odcs = odcRepository.findAllByIds(odcRepository.findIdsByPonPortIds(setOf(ponPortId)))
            .map { Reached(ClosureRef(ClosureKind.ODC, it.id, it.code, it.name, it.location), depth = 1) }
        val odps = odpRepository.findAllByIds(odpIds)
            .map { Reached(ClosureRef(ClosureKind.ODP, it.id, it.code, it.name, it.location), depth = 2) }
        return odcs + odps
    }

    /**
     * Berapa ONU pelanggan menggantung di tiap ODP. Ditanyakan lewat [OdpUsageProbe]
     * karena module network tak boleh — dan tak perlu — tahu apa itu pelanggan;
     * yang ia tahu cuma "ada sesuatu milik orang lain yang menempel di sini".
     */
    private fun countOnus(odpIds: Set<UUID>): Map<UUID, Long> {
        if (odpIds.isEmpty()) return emptyMap()
        val total = HashMap<UUID, Long>()
        usageProbes.forEach { probe ->
            probe.countAttachedTo(odpIds).forEach { (odpId, count) ->
                total.merge(odpId, count, Long::plus)
            }
        }
        return total
    }

    private fun loadWarnings(
        onuCount: Int,
        fromSplicing: Boolean,
        splicedOdpIds: Set<UUID>,
        linkedOdpIds: Set<UUID>,
    ): List<String> = buildList {
        when {
            PonCapacity.overflowing(onuCount) ->
                add(
                    "Port ini sudah menaungi $onuCount ONU, melewati plafon ${PonCapacity.GPON_MAX_ONU} " +
                        "milik GPON. ONU di atas batas tak akan pernah dapat giliran bicara — pindahkan " +
                        "sebagian kabinetnya ke port PON lain.",
                )
            PonCapacity.crowded(onuCount) ->
                add(
                    "Tinggal ${PonCapacity.GPON_MAX_ONU - onuCount} slot lagi sebelum plafon " +
                        "${PonCapacity.GPON_MAX_ONU} ONU. Satu ODP baru saja sudah cukup menembusnya — " +
                        "rencanakan port PON berikutnya sekarang, bukan saat teknisi sudah di rumah pelanggan.",
                )
        }
        if (!fromSplicing && linkedOdpIds.isNotEmpty()) {
            add(
                "Angka di atas dihitung dari tautan ODC→PON port, bukan dari catatan splicing — " +
                    "belum ada satu pun sambungan tercatat yang bermuara ke port ini.",
            )
        }
        // Selisih antara dua sumber itulah temuannya: kabinet yang tertaut tapi
        // tak pernah muncul di graf berarti seratnya belum didata, dan muatan
        // port ini terhitung lebih kecil daripada kenyataannya.
        if (fromSplicing) {
            val missing = linkedOdpIds - splicedOdpIds
            if (missing.isNotEmpty()) {
                add(
                    "${missing.size} ODP tertaut ke port ini lewat kabinetnya tapi tak tersambung di " +
                        "catatan splicing. Muatan sebenarnya kemungkinan lebih besar dari $onuCount ONU.",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Hop
    // ------------------------------------------------------------------

    private fun ponHop(point: ConnectionPoint): Trace {
        val ponPort = ponPortRepository.findById(requireNotNull(point.nodeId))
        val olt = ponPort?.let { oltRepository.findById(it.oltId) }
        return Trace(
            kind = FiberHopKind.PON_PORT,
            label = ponPort?.let { "PON ${it.label}" } ?: "PON port",
            detail = olt?.let { "${it.code} · ${it.name}" } ?: "OLT tak dikenal",
            lossDb = 0.0,
            measured = false,
            closure = null,
            nodeId = point.nodeId,
        )
    }

    /**
     * Rugi sambungan: pakai hasil UKUR bila ada, kalau tidak pakai angka tipikal
     * cara pasangnya. Bedanya ditandai supaya orang tahu anggaran ini seberapa
     * bisa dipercaya — jalur yang mepet dengan angka perkiraan semua adalah
     * alasan untuk mengukur, bukan untuk panik.
     */
    private fun spliceHop(connection: FiberConnection): Trace {
        val closure = closures.find(connection.closureKind, connection.closureId)
        val measured = connection.lossDb
        return Trace(
            kind = FiberHopKind.SPLICE,
            label = closure?.code ?: connection.closureKind.label,
            detail = connection.method.label + if (measured != null) " · hasil ukur" else " · perkiraan",
            lossDb = measured ?: connection.method.typicalLossDb,
            measured = measured != null,
            closure = closure,
            nodeId = null,
        )
    }

    private fun fiberHop(core: CableCore?, cable: Cable?, here: FiberConnection?, onward: FiberConnection): Trace {
        val meters = segmentMeters(cable, here, onward)
        return Trace(
            kind = FiberHopKind.FIBER,
            label = if (cable != null && core != null) "${cable.code} core ${core.coreNumber}" else "Serat",
            detail = "${meters.roundToInt()} m" + (core?.let { " · ${it.color.label}" } ?: ""),
            lossDb = OpticalBudget.fiberLoss(meters),
            measured = false,
            closure = null,
            nodeId = null,
            fiberMeters = meters,
            cableId = cable?.id,
        )
    }

    /**
     * Panjang ruas serat antara dua kotak. Titik kupas masing-masing kotak
     * dihitung menyusuri rute, lalu selisihnya diskalakan ke panjang OPTIS kabel
     * — yang memuat slack di tiang dan closure, dan karena itu selalu lebih
     * panjang dari garis yang tergambar di peta.
     *
     * Bila salah satu kotak tak diketahui (mis. penelusuran berangkat dari core
     * tanpa ujung asal), panjang kabel utuh yang dipakai. Itu menaksir terlalu
     * besar, dan itu memang arah salah yang aman: anggaran jadi lebih ketat,
     * bukan lebih longgar.
     */
    private fun segmentMeters(cable: Cable?, here: FiberConnection?, onward: FiberConnection): Double {
        if (cable == null) return 0.0
        val optical = cable.lengthMeters
        val drawn = cable.route.lengthMeters()
        val from = here?.let { closures.find(it.closureKind, it.closureId) } ?: return optical
        val to = closures.find(onward.closureKind, onward.closureId) ?: return optical
        if (drawn <= 0.0) return optical
        val span = abs(cable.route.distanceAlongTo(from.location) - cable.route.distanceAlongTo(to.location))
        return span / drawn * optical
    }

    // ------------------------------------------------------------------
    // Penyusunan hasil
    // ------------------------------------------------------------------

    private fun build(start: ConnectionPoint, trail: List<Trace>, end: FiberTraceEnd): FiberPathView {
        // Dikumpulkan dari titik telusur ke arah OLT, dibalik supaya terbaca
        // searah CAHAYA. Rugi kumulatif baru punya arti setelah dibalik: tiap
        // angka menjawab "sampai di sini, berapa yang sudah habis".
        val ordered = trail.reversed()
        var cumulative = 0.0
        val hops = ordered.map { hop ->
            cumulative += hop.lossDb
            FiberHopView(
                kind = hop.kind,
                kindLabel = hop.kind.label,
                label = hop.label,
                detail = hop.detail,
                lossDb = round(hop.lossDb),
                cumulativeLossDb = round(cumulative),
                measured = hop.measured,
                closureKind = hop.closure?.kind,
                closureId = hop.closure?.id,
                closureCode = hop.closure?.code,
                cableId = hop.cableId,
                nodeId = hop.nodeId,
            )
        }
        val total = round(cumulative)
        val margin = round(OpticalBudget.margin(cumulative))
        val estimated = ordered.count { !it.measured && it.kind == FiberHopKind.SPLICE }
        return FiberPathView(
            startLabel = startLabel(start),
            end = end,
            endLabel = end.label,
            hops = hops,
            totalLossDb = total,
            budgetDb = OpticalBudget.CLASS_B_PLUS_DB,
            marginDb = margin,
            fiberMeters = round(ordered.sumOf { it.fiberMeters }),
            splitterCount = ordered.count { it.kind == FiberHopKind.SPLITTER },
            spliceCount = ordered.count { it.kind == FiberHopKind.SPLICE },
            estimatedHops = estimated,
            warnings = warningsFor(end, margin, estimated),
        )
    }

    /**
     * Nama titik berangkat seperti yang tertulis di badan bendanya, bukan sekadar
     * jenisnya. Orang yang membuka layar ini sedang memegang kabel bertuliskan
     * "FDR-01" — "Core kabel" saja tak memberi tahu apa pun.
     */
    private fun startLabel(point: ConnectionPoint): String = when (point.kind) {
        ConnectionPointKind.CORE -> {
            val core = cableCoreRepository.findById(requireNotNull(point.coreId))
            val cable = core?.let { cableRepository.findById(it.cableId) }
            if (cable != null && core != null) "${cable.code} core ${core.coreNumber}" else point.description
        }
        ConnectionPointKind.SPLITTER_IN, ConnectionPointKind.SPLITTER_OUT -> {
            val splitter = splitters.findById(requireNotNull(point.nodeId))
            val owner = splitter?.let { closures.find(it.ownerKind, it.ownerId) }
            val leg = point.portNumber?.let { " kaki $it" } ?: " (input)"
            if (splitter != null) "${owner?.code ?: ""} ${splitter.code}$leg".trim() else point.description
        }
        ConnectionPointKind.ODF_PORT -> {
            val odf = closures.find(ClosureKind.ODF, requireNotNull(point.nodeId))
            "${odf?.code ?: "ODF"} port ${point.portNumber} ${point.portSide?.label?.lowercase()}"
        }
        ConnectionPointKind.PON_PORT ->
            ponPortRepository.findById(requireNotNull(point.nodeId))?.let { "PON ${it.label}" } ?: point.description
        ConnectionPointKind.ONU -> point.description
    }

    private fun warningsFor(end: FiberTraceEnd, margin: Double, estimated: Int): List<String> = buildList {
        if (end != FiberTraceEnd.SOURCE) add("Jalur tak sampai ke OLT — ${end.label.lowercase()}.")
        when {
            // Anggaran cuma punya arti bila jalurnya utuh: jalur yang buntu di
            // tengah selalu terlihat "hemat" justru karena separuhnya belum ada.
            end != FiberTraceEnd.SOURCE -> Unit
            margin < 0 ->
                add(
                    "Anggaran redaman terlampaui ${format(-margin)} dB. Jalur ini secara hitungan " +
                        "sudah di luar jangkauan ONU kelas B+ — kurangi tingkat splitter atau perpendek jalurnya.",
                )
            margin < OpticalBudget.WARN_MARGIN_DB ->
                add(
                    "Sisa anggaran cuma ${format(margin)} dB. Jalur semepet ini menyala hari ini dan padam " +
                        "begitu konektor kotor atau ada sambungan darurat ditambahkan.",
                )
        }
        if (estimated > 0) {
            add("$estimated sambungan belum diukur — angkanya masih perkiraan tipikal, bukan hasil ukur.")
        }
    }

    /** Dua angka di belakang koma: lebih dari itu ketelitian yang tak dimiliki alat ukurnya. */
    private fun round(value: Double): Double = (value * 100).roundToInt() / 100.0

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun ConnectionPointCommand.toDomain() =
        ConnectionPoint(kind = kind, coreId = coreId, nodeId = nodeId, portNumber = portNumber, portSide = portSide)

    /** Hop mentah — bentuk kerja sebelum arah & rugi kumulatifnya ditentukan. */
    private data class Trace(
        val kind: FiberHopKind,
        val label: String,
        val detail: String,
        val lossDb: Double,
        val measured: Boolean,
        val closure: ClosureRef?,
        val nodeId: UUID?,
        val fiberMeters: Double = 0.0,
        val cableId: UUID? = null,
    )
}
