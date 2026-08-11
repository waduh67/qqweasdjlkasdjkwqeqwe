package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.network.application.port.inbound.ClosureSpliceView
import com.duluin.ftth.network.application.port.inbound.ConnectFiberCommand
import com.duluin.ftth.network.application.port.inbound.ConnectionPointCommand
import com.duluin.ftth.network.application.port.inbound.FiberConnectionPointView
import com.duluin.ftth.network.application.port.inbound.FiberConnectionView
import com.duluin.ftth.network.application.port.inbound.ManageFiberConnectionUseCase
import com.duluin.ftth.network.application.port.inbound.SpliceCableView
import com.duluin.ftth.network.application.port.inbound.SpliceCoreView
import com.duluin.ftth.network.application.port.inbound.SplicePointView
import com.duluin.ftth.network.application.port.inbound.SpliceWorkbenchView
import com.duluin.ftth.network.application.port.inbound.UpdateFiberConnectionCommand
import com.duluin.ftth.network.application.port.outbound.CableCoreRepository
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.application.port.outbound.SplitterRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableCore
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPoint
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.CoreStatus
import com.duluin.ftth.network.domain.model.FiberConnection
import com.duluin.ftth.network.domain.model.OdfPortSide
import com.duluin.ftth.network.domain.model.PonPort
import com.duluin.ftth.network.domain.model.Splitter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Sambung & putus serat di dalam closure.
 *
 * Di sinilah janji utama desain ulang ini ditepati: yang tersambung adalah CORE,
 * bukan kabel. Karena itu semua aturan di berkas ini berputar pada satu
 * pertanyaan — "apakah serat ini benar-benar ada di kotak yang sedang dibuka?" —
 * dan bukan pada bentuk topologi kabel A→B yang lama.
 */
@Service
@Transactional
class FiberConnectionService(
    private val connections: FiberConnectionRepository,
    private val cableRepository: CableRepository,
    private val cableCoreRepository: CableCoreRepository,
    private val closures: ClosureLookup,
    private val oltRepository: OltRepository,
    private val ponPortRepository: PonPortRepository,
    private val splitters: SplitterRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageFiberConnectionUseCase {

    @Transactional(readOnly = true)
    override fun list(closureKind: ClosureKind, closureId: UUID): ClosureSpliceView {
        val closure = requireClosure(closureKind, closureId)
        val rows = connections.findByClosureId(closureId)
        return ClosureSpliceView(
            closureKind = closure.kind,
            closureId = closure.id,
            closureCode = closure.code,
            closureName = closure.name,
            connections = rows.toViews(),
        )
    }

    /**
     * Meja kerja splicing sebuah kotak, dirakit dalam sekali jalan.
     *
     * Urutan kabelnya sengaja: yang BERUJUNG di sini duluan, lalu yang cuma lewat
     * menurut letak kupasannya. Di depan kotak yang terbuka, kabel yang berakhir
     * di situ adalah yang paling sering dicari — sisanya baru masuk hitungan saat
     * mencari core cadangan.
     */
    @Transactional(readOnly = true)
    override fun workbench(closureKind: ClosureKind, closureId: UUID): SpliceWorkbenchView {
        val closure = requireClosure(closureKind, closureId)
        val rows = connections.findByClosureId(closureId)
        val cables = reachableCables(closure)
        return SpliceWorkbenchView(
            closureKind = closure.kind,
            closureId = closure.id,
            closureCode = closure.code,
            closureName = closure.name,
            spliceCapacity = closure.spliceCapacity,
            spliceCount = rows.size,
            cables = cables.toCableViews(closure),
            points = pointsOf(closure),
            connections = rows.toViews(),
        )
    }

    override fun connect(command: ConnectFiberCommand): FiberConnectionView {
        val closure = requireClosure(command.closureKind, command.closureId)
        val a = command.a.toPoint()
        val b = command.b.toPoint()
        assertPairMakesSense(a, b)
        val cores = listOf(a, b).mapNotNull { point -> validate(point, closure) }
        assertRoomLeft(closure)

        val saved = connections.save(
            FiberConnection.create(
                tenantId = currentUser.current().tenantId,
                closureKind = closure.kind,
                closureId = closure.id,
                a = a,
                b = b,
                method = command.method,
                lossDb = command.lossDb,
                note = command.note,
            ),
        )
        occupy(cores)
        auditor.record(
            "fiber.connected", closure.kind.name, closure.id, saved.tenantId,
            mapOf("closure" to closure.code, "a" to a.description, "b" to b.description),
        )
        return listOf(saved).toViews().first()
    }

    /**
     * Semua atau tak sama sekali — dijamin oleh transaksi milik method ini:
     * penolakan pasangan ke-berapa pun melempar keluar, dan yang terlanjur masuk
     * ikut tergulung balik. Pemeriksaan "titik sudah dipakai" pun tetap sahih di
     * dalam satu batch, sebab query berikutnya memaksa Hibernate menyiram
     * sambungan yang baru ditulis lebih dulu.
     */
    override fun connectAll(commands: List<ConnectFiberCommand>): List<FiberConnectionView> {
        if (commands.isEmpty()) throw ValidationException("Tak ada pasangan yang disambung")
        return commands.map { connect(it) }
    }

    override fun update(id: UUID, command: UpdateFiberConnectionCommand): FiberConnectionView {
        val connection = requireConnection(id)
        connection.update(command.method, command.lossDb, command.note)
        val saved = connections.save(connection)
        auditor.record(
            "fiber.connection.updated", saved.closureKind.name, saved.closureId, saved.tenantId,
            mapOf("lossDb" to (saved.lossDb ?: "-"), "method" to saved.method.name),
        )
        return listOf(saved).toViews().first()
    }

    override fun disconnect(id: UUID) {
        val connection = requireConnection(id)
        connections.deleteAll(listOf(connection))
        release(connection.coreIds)
        auditor.record(
            "fiber.disconnected", connection.closureKind.name, connection.closureId, connection.tenantId,
            mapOf("a" to connection.a.description, "b" to connection.b.description),
        )
    }

    override fun disconnectAllOfCable(cableId: UUID, cableSurvives: Boolean): Int {
        val doomed = connections.findByCableId(cableId)
        if (doomed.isEmpty()) return 0
        connections.deleteAll(doomed)
        // Core sisi seberang (milik kabel LAIN) selalu ikut dibebaskan. Core kabel
        // ini sendiri hanya dirapikan bila kabelnya tetap ada — kalau ia sedang
        // dihapus, core-nya lenyap bersamanya.
        val own = cableCoreRepository.findByCableId(cableId).map { it.id }.toSet()
        val touched = doomed.flatMap { it.coreIds }
        release(if (cableSurvives) touched else touched.filterNot { it in own })
        return doomed.size
    }

    // ------------------------------------------------------------------
    // Aturan
    // ------------------------------------------------------------------

    /**
     * Memeriksa satu ujung, mengembalikan core yang dipakainya (null untuk titik
     * non-core). Dua hal yang dijaga: titiknya masuk akal untuk closure ini, dan
     * titiknya belum dipakai sambungan lain.
     */
    private fun validate(point: ConnectionPoint, closure: ClosureRef): CableCore? = when (point.kind) {
        ConnectionPointKind.CORE -> validateCore(point, closure)

        ConnectionPointKind.SPLITTER_IN -> {
            requireSplitterOf(point, closure)
            assertFree(point, closure)
            null
        }

        ConnectionPointKind.SPLITTER_OUT -> {
            val splitter = requireSplitterOf(point, closure)
            val leg = point.portNumber ?: 0
            if (!splitter.hasLeg(leg)) {
                throw ValidationException(
                    "Kaki $leg di luar splitter ${splitter.code} ${closure.code} " +
                        "yang rasionya ${splitter.ratio.label} (1-${splitter.legCount})",
                )
            }
            assertFree(point, closure)
            null
        }

        ConnectionPointKind.ODF_PORT -> {
            requireRack(closure, "Port ODF")
            // Port yang ditunjuk TAK harus milik rak closure-nya: sehelai
            // patchcord memang bisa membentang antara dua rak dalam satu POP.
            // Yang dicatat closure adalah di kotak mana pekerjaan itu dilakukan;
            // yang menjaga port tak dobel-pakai adalah [assertFree], dan itu
            // berlaku global.
            val odfId = requireNotNull(point.nodeId)
            val odf = closures.require(ClosureKind.ODF, odfId)
            val portCount = odf.portCount ?: 0
            val port = point.portNumber ?: 0
            if (port !in 1..portCount) {
                throw ValidationException("Port $port di luar kapasitas ODF ${odf.code} (1-$portCount)")
            }
            assertFree(point, closure)
            null
        }

        ConnectionPointKind.PON_PORT -> {
            requireRack(closure, "PON port")
            val ponPortId = requireNotNull(point.nodeId)
            ponPortRepository.findById(ponPortId) ?: throw NotFoundException("PON port $ponPortId tidak ditemukan")
            assertFree(point, closure)
            null
        }

        ConnectionPointKind.ONU ->
            throw ValidationException("Ujung ONU tercatat lewat pemasangan ONU pelanggan, bukan di layar sambungan")
    }

    private fun validateCore(point: ConnectionPoint, closure: ClosureRef): CableCore {
        val coreId = requireNotNull(point.coreId)
        val core = cableCoreRepository.findById(coreId) ?: throw NotFoundException("Core $coreId tidak ditemukan")
        val cable = cableRepository.findById(core.cableId)
            ?: throw NotFoundException("Kabel core ${core.coreNumber} tidak ditemukan")
        if (core.status == CoreStatus.DAMAGED) {
            throw ConflictException("Core ${core.coreNumber} kabel ${cable.code} ditandai rusak — perbaiki dulu")
        }
        assertCableReaches(cable, closure)
        connections.findByCoreInClosure(closure.id, coreId)?.let {
            throw ConflictException(
                "Core ${core.coreNumber} kabel ${cable.code} sudah disambung di ${closure.code}",
            )
        }
        return core
    }

    /**
     * Kabel harus benar-benar lewat closure ini.
     *
     * Ujung from/to saja tidak cukup: ODP menempel di TENGAH kabel distribusi
     * (mid-span tapping), jadi kabel ODC→ODP-8 sah disambung di ODP-3 yang
     * dilewatinya. Karena itu keanggotaan diuji dari GEOMETRI rutenya.
     *
     * Kelonggarannya sengaja lebar — ini penjaring salah-pilih-kabel yang kasar
     * (kabel di kecamatan sebelah), bukan penilai ketelitian survei. Rute yang
     * digambar kasar tetap lolos; yang ditolak adalah kabel yang memang tak ada
     * di sana.
     */
    private fun assertCableReaches(cable: Cable, closure: ClosureRef) {
        if (reaches(cable, closure)) return
        val distance = cable.route.distanceTo(closure.location)
        throw ValidationException(
            "Kabel ${cable.code} tak lewat ${closure.code} — jaraknya ${distance.roundToInt()} m dari rute. " +
                "Perbaiki dulu rute kabelnya bila memang lewat sini.",
        )
    }

    /**
     * Aturan yang sama dipakai dua arah: menolak sambungan yang mustahil, DAN
     * menyusun daftar kabel yang boleh dipilih di meja kerja. Satu sumber supaya
     * layar tak pernah menawarkan kabel yang akan ditolak sedetik kemudian.
     */
    private fun reaches(cable: Cable, closure: ClosureRef): Boolean =
        cable.from.id == closure.id ||
            cable.to.id == closure.id ||
            cable.route.distanceTo(closure.location) <= MID_SPAN_TOLERANCE_METERS

    /**
     * Pasangan yang benar-benar punya bentuk fisik di dalam rak.
     *
     * Sisi BELAKANG cuma bertemu core kabel luar — di situlah pigtail dilas, dan
     * setelah itu tak disentuh bertahun-tahun. Sisi DEPAN cuma menerima
     * patchcord: ke PON port OLT, atau ke sisi depan port lain saat sebuah POP
     * hanya DILEWATI feeder (masuk di satu port, keluar di port lain, tanpa
     * menyentuh OLT). PON port pun tak punya pasangan lain: kabel outdoor tak
     * pernah dicolok langsung ke badan OLT.
     *
     * Ini bukan kelonggaran yang belum sempat ditulis — menyambung core langsung
     * ke sisi depan berarti mengaku ada konektor di ujung kabel luar, dan jalur
     * yang ditelusuri dari data itu tak akan pernah cocok dengan raknya.
     */
    private fun assertPairMakesSense(a: ConnectionPoint, b: ConnectionPoint) {
        listOf(a to b, b to a).forEach { (point, other) ->
            when (point.kind) {
                ConnectionPointKind.ODF_PORT -> when (point.portSide) {
                    OdfPortSide.BACK -> if (other.kind != ConnectionPointKind.CORE) {
                        throw ValidationException(
                            "${point.description} hanya disambung ke core kabel — " +
                                "di sisi belakang itulah pigtail dilas, bukan ${other.description}",
                        )
                    }
                    OdfPortSide.FRONT -> {
                        val patchable = other.kind == ConnectionPointKind.PON_PORT ||
                            (other.kind == ConnectionPointKind.ODF_PORT && other.portSide == OdfPortSide.FRONT)
                        if (!patchable) {
                            throw ValidationException(
                                "${point.description} hanya menerima patchcord: ke PON port OLT, " +
                                    "atau ke sisi depan port ODF lain bila POP ini cuma dilewati",
                            )
                        }
                    }
                    null -> Unit // Tak mungkin: ConnectionPoint mewajibkan sisi untuk port ODF.
                }

                ConnectionPointKind.PON_PORT ->
                    if (other.kind != ConnectionPointKind.ODF_PORT || other.portSide != OdfPortSide.FRONT) {
                        throw ValidationException(
                            "PON port disambung lewat patchcord dari sisi depan port ODF, bukan ${other.description}",
                        )
                    }

                else -> Unit
            }
        }
    }

    /** Port ODF & PON port cuma ada artinya di dalam rak; di kotak lain tak ada raknya. */
    private fun requireRack(closure: ClosureRef, what: String) {
        if (closure.kind != ClosureKind.ODF) {
            throw ValidationException(
                "$what disambung di dalam rak ODF — ${closure.code} adalah ${closure.kind.label}",
            )
        }
    }

    /**
     * Modul splitter yang benar-benar ada DI DALAM kotak yang sedang dibuka.
     *
     * Sebuah kabinet bisa berisi beberapa modul dengan rasio berbeda, jadi titik
     * sambungnya menunjuk splitter — bukan kabinetnya. Dua penolakan di sini:
     * kotak yang memang tak pernah berisi splitter (joint box, ODF: isinya cuma
     * tray dan sambungan serat ke serat), dan modul milik kabinet sebelah yang
     * ikut terbawa karena salah pilih di layar.
     */
    private fun requireSplitterOf(point: ConnectionPoint, closure: ClosureRef): Splitter {
        if (!closure.kind.hasSplitter) {
            throw ValidationException(
                "${closure.code} adalah ${closure.kind.label} yang tak berisi splitter — " +
                    "di dalamnya serat disambung langsung ke serat",
            )
        }
        val splitterId = requireNotNull(point.nodeId)
        val splitter = splitters.findById(splitterId)
            ?: throw NotFoundException("Splitter $splitterId tidak ditemukan")
        if (splitter.ownerId != closure.id) {
            throw ValidationException("Splitter ${splitter.code} bukan isi ${closure.code}")
        }
        return splitter
    }

    private fun assertFree(point: ConnectionPoint, closure: ClosureRef) {
        connections.findByNodePoint(
            point.kind,
            requireNotNull(point.nodeId),
            point.portNumber,
            point.portSide,
        )?.let {
            throw ConflictException("${point.description} di ${closure.code} sudah dipakai sambungan lain")
        }
    }

    /** Core yang baru tersambung jadi TERPAKAI — termasuk yang tadinya dicadangkan. */
    private fun occupy(cores: List<CableCore>) {
        val changed = cores.filter { it.status != CoreStatus.USED }
        if (changed.isEmpty()) return
        changed.forEach { it.update(CoreStatus.USED, it.note) }
        cableCoreRepository.saveAll(changed)
    }

    /**
     * Mengembalikan core ke BEBAS setelah sambungan terakhirnya lepas. Core yang
     * masih punya sambungan di closure lain tetap terpakai — sehelai serat baru
     * benar-benar bebas kalau kedua ujungnya lepas. Core rusak dibiarkan rusak:
     * memutus sambungan tidak menyambung seratnya kembali.
     */
    private fun release(coreIds: List<UUID>) {
        if (coreIds.isEmpty()) return
        val stillUsed = connections.findByCoreIds(coreIds).flatMap { it.coreIds }.toSet()
        val freed = cableCoreRepository.findByIds(coreIds.distinct() - stillUsed)
            .filter { it.status == CoreStatus.USED }
        if (freed.isEmpty()) return
        freed.forEach { it.update(CoreStatus.FREE, it.note) }
        cableCoreRepository.saveAll(freed)
    }

    // ------------------------------------------------------------------
    // Pemuatan & pemetaan
    // ------------------------------------------------------------------

    private fun requireClosure(kind: ClosureKind, id: UUID): ClosureRef = closures.require(kind, id)

    // ------------------------------------------------------------------
    // Meja kerja
    // ------------------------------------------------------------------

    /**
     * Kabel yang boleh disentuh dari dalam kotak ini. Dua sumber digabung karena
     * keduanya sah dan tak saling menggantikan: yang BERUJUNG di sini (diambil
     * dari pasangan from/to) dan yang cuma LEWAT (diambil dari geometri rutenya).
     * Radius query dipakai sebagai penyaring kasar berindeks; putusan akhirnya
     * tetap [reaches], supaya daftar dan penolakan tak pernah berbeda pendapat.
     */
    private fun reachableCables(closure: ClosureRef): List<Cable> =
        (cableRepository.findByEndpointNodeIds(setOf(closure.id)) +
            cableRepository.findPassing(closure.location, MID_SPAN_TOLERANCE_METERS))
            .distinctBy { it.id }
            .filter { reaches(it, closure) }

    private fun List<Cable>.toCableViews(closure: ClosureRef): List<SpliceCableView> {
        if (isEmpty()) return emptyList()
        val coresByCable = cableCoreRepository.findByCableIds(map { it.id }).groupBy { it.cableId }
        // Satu query untuk seluruh core semua kabel: yang dicari adalah "core ini
        // sudah dilas di mana", dan jawabannya bisa berada di kotak mana pun.
        val touching = connections.findByCoreIds(coresByCable.values.flatten().map { it.id })
        val here = HashMap<UUID, UUID>()
        val elsewhere = HashSet<UUID>()
        touching.forEach { connection ->
            connection.coreIds.forEach { coreId ->
                if (connection.closureId == closure.id) here[coreId] = connection.id else elsewhere += coreId
            }
        }
        return map { cable ->
            val terminates = cable.from.id == closure.id || cable.to.id == closure.id
            SpliceCableView(
                cableId = cable.id,
                code = cable.code,
                name = cable.name,
                cableType = cable.cableType,
                coreCount = cable.coreCount,
                lengthMeters = cable.lengthMeters,
                terminatesHere = terminates,
                tapDistanceMeters = cable.route.distanceAlongTo(closure.location),
                offsetMeters = cable.route.distanceTo(closure.location),
                cores = coresByCable[cable.id].orEmpty().map { core ->
                    SpliceCoreView(
                        core = core.toView(),
                        connectionId = here[core.id],
                        connectedElsewhere = core.id in elsewhere,
                    )
                },
            )
        }.sortedWith(compareByDescending<SpliceCableView> { it.terminatesHere }.thenBy { it.tapDistanceMeters })
    }

    /**
     * Titik non-core yang tersedia di kotak ini — dan hanya yang memang ada
     * bentuk fisiknya. Joint box tak menghasilkan satu pun: di dalamnya serat
     * cuma bertemu serat, dan menawarkan "kaki splitter" di sana akan menyesatkan
     * orang yang berdiri di depan kotaknya.
     */
    private fun pointsOf(closure: ClosureRef): List<SplicePointView> = when (closure.kind) {
        ClosureKind.ODC, ClosureKind.ODP -> splitterPoints(closure)
        ClosureKind.ODF -> odfPoints(closure) + ponPoints(closure)
        ClosureKind.JOINT_BOX -> emptyList()
    }

    private fun splitterPoints(closure: ClosureRef): List<SplicePointView> {
        val modules = splitters.findByOwnerId(closure.id)
        if (modules.isEmpty()) return emptyList()
        val ids = modules.mapTo(HashSet()) { it.id }
        val inputs = occupancyOf(ConnectionPointKind.SPLITTER_IN, ids)
        val legs = occupancyOf(ConnectionPointKind.SPLITTER_OUT, ids)
        return modules.flatMap { module ->
            val group = "${module.code} · ${module.ratio.label}"
            // Input lebih dulu: modul tanpa masukan tak menyalurkan apa pun, jadi
            // itulah yang harus pertama terlihat saat kabinet baru dipasang.
            listOf(
                SplicePointView(
                    kind = ConnectionPointKind.SPLITTER_IN,
                    nodeId = module.id,
                    portNumber = null,
                    portSide = null,
                    label = "Input ${module.code}",
                    group = group,
                    connectionId = inputs[PointKey(module.id, null, null)],
                ),
            ) + (1..module.legCount).map { leg ->
                SplicePointView(
                    kind = ConnectionPointKind.SPLITTER_OUT,
                    nodeId = module.id,
                    portNumber = leg,
                    portSide = null,
                    label = "${module.code} kaki $leg",
                    group = group,
                    connectionId = legs[PointKey(module.id, leg, null)],
                )
            }
        }
    }

    /**
     * Kedua SISI tiap adapter dimunculkan terpisah, sebab keduanya memang dua
     * pekerjaan berbeda: belakang tempat pigtail dilas ke core kabel luar, depan
     * tempat patchcord dicolok ke OLT.
     */
    private fun odfPoints(closure: ClosureRef): List<SplicePointView> {
        val ports = closure.portCount ?: return emptyList()
        val used = occupancyOf(ConnectionPointKind.ODF_PORT, setOf(closure.id))
        val group = "Rak ${closure.code}"
        return (1..ports).flatMap { port ->
            OdfPortSide.entries.map { side ->
                SplicePointView(
                    kind = ConnectionPointKind.ODF_PORT,
                    nodeId = closure.id,
                    portNumber = port,
                    portSide = side,
                    label = "Port $port ${side.label.lowercase()}",
                    group = group,
                    connectionId = used[PointKey(closure.id, port, side)],
                )
            }
        }
    }

    /**
     * PON port OLT yang berdiri di POP yang sama dengan raknya. Dibatasi per POP
     * karena patchcord tak menyeberang gedung — menawarkan PON port kota sebelah
     * cuma memperbesar daftar dan peluang salah colok.
     */
    private fun ponPoints(closure: ClosureRef): List<SplicePointView> {
        val siteId = closure.siteId ?: return emptyList()
        val olts = oltRepository.findBySiteId(siteId)
        if (olts.isEmpty()) return emptyList()
        val ports = olts.associateWith { ponPortRepository.findByOltId(it.id) }
        val used = occupancyOf(
            ConnectionPointKind.PON_PORT,
            ports.values.flatMapTo(HashSet()) { list -> list.map { it.id } },
        )
        return ports.entries.flatMap { (olt, list) ->
            list.map { port ->
                SplicePointView(
                    kind = ConnectionPointKind.PON_PORT,
                    nodeId = port.id,
                    portNumber = null,
                    portSide = null,
                    label = "PON ${port.label}",
                    group = "${olt.code} · ${olt.name}",
                    connectionId = used[PointKey(port.id, null, null)],
                )
            }
        }
    }

    /** Identitas sebuah titik simpul: nomor & sisi ikut, sebab keduanya membedakan. */
    private data class PointKey(val nodeId: UUID, val portNumber: Int?, val portSide: OdfPortSide?)

    /** Siapa memakai titik yang mana — dicari global, seperti [assertFree] menilainya. */
    private fun occupancyOf(kind: ConnectionPointKind, nodeIds: Set<UUID>): Map<PointKey, UUID> =
        connections.findByNodeIds(kind, nodeIds)
            .flatMap { connection -> listOf(connection.a, connection.b).map { it to connection.id } }
            .filter { (point, _) -> point.kind == kind && point.nodeId in nodeIds }
            .associate { (point, id) -> PointKey(point.nodeId!!, point.portNumber, point.portSide) to id }

    /**
     * Kotak sambung punya batas fisik: tray-nya habis. Diperiksa saat menyambung,
     * bukan saat menggambar kabel, karena yang memakan tempat memang sambungannya
     * — core yang cuma lewat tak menghabiskan apa pun.
     */
    private fun assertRoomLeft(closure: ClosureRef) {
        val limit = closure.spliceCapacity ?: return
        val used = connections.countByClosureId(closure.id)
        if (used >= limit) {
            throw ConflictException(
                "${closure.code} sudah penuh: $used dari $limit sambungan terpakai",
            )
        }
    }

    private fun requireConnection(id: UUID): FiberConnection =
        connections.findById(id) ?: throw NotFoundException("Sambungan $id tidak ditemukan")

    /**
     * Memetakan sekaligus supaya asal-usul core (kabel, nomor, warna) diambil
     * dalam dua query, bukan dua query per baris sambungan.
     */
    private fun List<FiberConnection>.toViews(): List<FiberConnectionView> {
        if (isEmpty()) return emptyList()
        val cores = cableCoreRepository.findByIds(flatMap { it.coreIds }.distinct()).associateBy { it.id }
        val cables = cableRepository.findByIds(cores.values.map { it.cableId }.distinct()).associateBy { it.id }
        // "PON port OLT" saja tak menolong siapa pun di depan rak — yang dicari
        // teknisi adalah labelnya (1/1/3). Diambil sekali untuk seluruh daftar.
        val ponPorts = flatMap { listOf(it.a, it.b) }
            .filter { it.kind == ConnectionPointKind.PON_PORT }
            .mapNotNullTo(HashSet()) { it.nodeId }
            .let { if (it.isEmpty()) emptyMap() else ponPortRepository.findAllByIds(it).associateBy { p -> p.id } }
        return map { connection ->
            FiberConnectionView(
                id = connection.id,
                closureKind = connection.closureKind,
                closureId = connection.closureId,
                a = connection.a.toView(cores, cables, ponPorts),
                b = connection.b.toView(cores, cables, ponPorts),
                method = connection.method,
                methodLabel = connection.method.label,
                lossDb = connection.lossDb,
                note = connection.note,
            )
        }
    }

    private fun ConnectionPoint.toView(
        cores: Map<UUID, CableCore>,
        cables: Map<UUID, Cable>,
        ponPorts: Map<UUID, PonPort>,
    ): FiberConnectionPointView {
        val core = coreId?.let { cores[it] }
        val cable = core?.let { cables[it.cableId] }
        val ponPort = nodeId?.takeIf { kind == ConnectionPointKind.PON_PORT }?.let { ponPorts[it] }
        return FiberConnectionPointView(
            kind = kind,
            kindLabel = kind.label,
            label = core?.let { "Core ${it.coreNumber} · ${it.color.label}" + (cable?.let { c -> " · ${c.code}" } ?: "") }
                ?: ponPort?.let { "PON ${it.label}" }
                ?: description,
            coreId = coreId,
            cableId = core?.cableId,
            cableCode = cable?.code,
            coreNumber = core?.coreNumber,
            colorHex = core?.color?.hex,
            nodeId = nodeId,
            portNumber = portNumber,
            portSide = portSide,
        )
    }

    private companion object {
        /**
         * Sejauh mana sebuah simpul boleh meleset dari garis rute dan masih
         * dianggap dilewati kabel itu.
         */
        const val MID_SPAN_TOLERANCE_METERS = 500.0
    }
}

private fun ConnectionPointCommand.toPoint() = ConnectionPoint(kind, coreId, nodeId, portNumber, portSide)
