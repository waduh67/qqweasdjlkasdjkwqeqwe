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
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableCore
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.ConnectionPoint
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.CoreStatus
import com.duluin.ftth.network.domain.model.FiberConnection
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
        val cores = listOf(a, b).mapNotNull { point -> validate(point, closure) }

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
            requireOwnNode(point, closure)
            assertFree(point, closure)
            null
        }

        ConnectionPointKind.SPLITTER_OUT -> {
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

        // Sengaja ditolak, bukan diam-diam diterima: simpulnya belum ada di sistem,
        // jadi menerimanya berarti menyimpan id yang tak menunjuk apa pun.
        ConnectionPointKind.ODF_PORT ->
            throw ValidationException("Port ODF menyusul bersama simpul ODF; sementara ini core feeder langsung ke ODC")

        ConnectionPointKind.PON_PORT ->
            throw ValidationException("PON port disambung lewat ODF; simpul ODF menyusul")

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
        connections.findByNodePoint(point.kind, requireNotNull(point.nodeId), point.portNumber)?.let {
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
        /** Jumlah kaki keluar splitter di simpul ini. */
        val splitterLegs: Int,
    )

    private fun requireClosure(kind: ClosureKind, id: UUID): Closure {
        if (!kind.available) {
            throw ValidationException("Sambungan di ${kind.label} belum didukung — simpulnya belum ada di sistem")
        }
        return when (kind) {
            ClosureKind.ODC -> odcRepository.findById(id)?.let {
                Closure(kind, it.id, it.code, it.name, it.location, it.capacity)
            }
            ClosureKind.ODP -> odpRepository.findById(id)?.let {
                Closure(kind, it.id, it.code, it.name, it.location, it.capacity)
            }
            else -> null
        } ?: throw NotFoundException("${kind.label} $id tidak ditemukan")
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
        return map { connection ->
            FiberConnectionView(
                id = connection.id,
                closureKind = connection.closureKind,
                closureId = connection.closureId,
                a = connection.a.toView(cores, cables),
                b = connection.b.toView(cores, cables),
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
    ): FiberConnectionPointView {
        val core = coreId?.let { cores[it] }
        val cable = core?.let { cables[it.cableId] }
        return FiberConnectionPointView(
            kind = kind,
            kindLabel = kind.label,
            label = core?.let { "Core ${it.coreNumber} · ${it.color.label}" + (cable?.let { c -> " · ${c.code}" } ?: "") }
                ?: description,
            coreId = coreId,
            cableId = core?.cableId,
            cableCode = cable?.code,
            coreNumber = core?.coreNumber,
            colorHex = core?.color?.hex,
            nodeId = nodeId,
            portNumber = portNumber,
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

private fun ConnectionPointCommand.toPoint() = ConnectionPoint(kind, coreId, nodeId, portNumber)
