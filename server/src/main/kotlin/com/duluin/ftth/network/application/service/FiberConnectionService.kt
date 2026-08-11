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
import com.duluin.ftth.network.application.port.inbound.UpdateFiberConnectionCommand
import com.duluin.ftth.network.application.port.outbound.CableCoreRepository
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.application.port.outbound.JointBoxRepository
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdfRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableCore
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPoint
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.CoreStatus
import com.duluin.ftth.network.domain.model.FiberConnection
import com.duluin.ftth.network.domain.model.OdfPortSide
import com.duluin.ftth.network.domain.model.PonPort
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
    private val odcRepository: OdcRepository,
    private val odpRepository: OdpRepository,
    private val jointBoxRepository: JointBoxRepository,
    private val odfRepository: OdfRepository,
    private val ponPortRepository: PonPortRepository,
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

    override fun disconnectAllOfCable(cableId: UUID) {
        val doomed = connections.findByCableId(cableId)
        if (doomed.isEmpty()) return
        connections.deleteAll(doomed)
        // Core sisi seberang (milik kabel LAIN) ikut dibebaskan; core kabel ini
        // hilang bersama kabelnya, jadi statusnya tak perlu dirapikan.
        val own = cableCoreRepository.findByCableId(cableId).map { it.id }.toSet()
        release(doomed.flatMap { it.coreIds }.filterNot { it in own })
    }

    // ------------------------------------------------------------------
    // Aturan
    // ------------------------------------------------------------------

    /**
     * Memeriksa satu ujung, mengembalikan core yang dipakainya (null untuk titik
     * non-core). Dua hal yang dijaga: titiknya masuk akal untuk closure ini, dan
     * titiknya belum dipakai sambungan lain.
     */
    private fun validate(point: ConnectionPoint, closure: Closure): CableCore? = when (point.kind) {
        ConnectionPointKind.CORE -> validateCore(point, closure)

        ConnectionPointKind.SPLITTER_IN -> {
            requireSplitter(closure)
            requireOwnNode(point, closure)
            assertFree(point, closure)
            null
        }

        ConnectionPointKind.SPLITTER_OUT -> {
            requireSplitter(closure)
            requireOwnNode(point, closure)
            val leg = point.portNumber ?: 0
            if (leg !in 1..closure.splitterLegs) {
                throw ValidationException(
                    "Kaki splitter $leg di luar kapasitas ${closure.code} (1-${closure.splitterLegs})",
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
            val odf = odfRepository.findById(odfId) ?: throw NotFoundException("ODF $odfId tidak ditemukan")
            val port = point.portNumber ?: 0
            if (!odf.hasPort(port)) {
                throw ValidationException("Port $port di luar kapasitas ODF ${odf.code} (1-${odf.portCount})")
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

    private fun validateCore(point: ConnectionPoint, closure: Closure): CableCore {
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
    private fun assertCableReaches(cable: Cable, closure: Closure) {
        if (cable.from.id == closure.id || cable.to.id == closure.id) return
        val distance = cable.route.distanceTo(closure.location)
        if (distance > MID_SPAN_TOLERANCE_METERS) {
            throw ValidationException(
                "Kabel ${cable.code} tak lewat ${closure.code} — jaraknya ${distance.roundToInt()} m dari rute. " +
                    "Perbaiki dulu rute kabelnya bila memang lewat sini.",
            )
        }
    }

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
    private fun requireRack(closure: Closure, what: String) {
        if (closure.kind != ClosureKind.ODF) {
            throw ValidationException(
                "$what disambung di dalam rak ODF — ${closure.code} adalah ${closure.kind.label}",
            )
        }
    }

    /**
     * Joint box tak berisi splitter — di dalamnya cuma tray dan sambungan serat ke
     * serat. Menolaknya di sini, bukan lewat "kapasitas 0", supaya pesannya
     * menerangkan bendanya dan bukan angkanya.
     */
    private fun requireSplitter(closure: Closure) {
        if (!closure.kind.hasSplitter) {
            throw ValidationException(
                "${closure.code} adalah ${closure.kind.label} yang tak berisi splitter — " +
                    "di dalamnya serat disambung langsung ke serat",
            )
        }
    }

    /**
     * Splitter belum jadi entitas sendiri (potongan E), jadi untuk sekarang satu
     * simpul = satu splitter dan id-nya adalah id simpul itu. Menerima id lain
     * berarti menyimpan rujukan ke splitter yang tak ada.
     */
    private fun requireOwnNode(point: ConnectionPoint, closure: Closure) {
        if (point.nodeId != closure.id) {
            throw ValidationException("${point.kind.label} yang ditunjuk bukan milik ${closure.code}")
        }
    }

    private fun assertFree(point: ConnectionPoint, closure: Closure) {
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

    /** Closure yang sudah pasti ada, diratakan jadi satu bentuk apa pun jenisnya. */
    private data class Closure(
        val kind: ClosureKind,
        val id: UUID,
        val code: String,
        val name: String,
        val location: Coordinate,
        /** Jumlah kaki keluar splitter di simpul ini; 0 untuk closure tanpa splitter. */
        val splitterLegs: Int,
        /**
         * Batas jumlah sambungan yang muat di dalam kotaknya; null = tak dibatasi.
         * ODC/ODP dibatasi oleh kaki splitternya, joint box oleh jumlah tray.
         */
        val spliceCapacity: Int? = null,
    )

    private fun requireClosure(kind: ClosureKind, id: UUID): Closure = when (kind) {
        ClosureKind.ODC -> odcRepository.findById(id)?.let {
            Closure(kind, it.id, it.code, it.name, it.location, it.capacity)
        }
        ClosureKind.ODP -> odpRepository.findById(id)?.let {
            Closure(kind, it.id, it.code, it.name, it.location, it.capacity)
        }
        ClosureKind.JOINT_BOX -> jointBoxRepository.findById(id)?.let {
            Closure(kind, it.id, it.code, it.name, it.location, splitterLegs = 0, spliceCapacity = it.capacity)
        }
        // Batas rak dihitung dari SISI, bukan port: tiap adapter memang menampung
        // dua sambungan, belakang dan depan.
        ClosureKind.ODF -> odfRepository.findById(id)?.let {
            Closure(kind, it.id, it.code, it.name, it.location, splitterLegs = 0, spliceCapacity = it.portCount * 2)
        }
    } ?: throw NotFoundException("${kind.label} $id tidak ditemukan")

    /**
     * Kotak sambung punya batas fisik: tray-nya habis. Diperiksa saat menyambung,
     * bukan saat menggambar kabel, karena yang memakan tempat memang sambungannya
     * — core yang cuma lewat tak menghabiskan apa pun.
     */
    private fun assertRoomLeft(closure: Closure) {
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
