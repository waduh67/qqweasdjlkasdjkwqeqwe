package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.network.CableCutImpact
import com.duluin.ftth.network.CablePath
import com.duluin.ftth.network.DownstreamIds
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OdcBranch
import com.duluin.ftth.network.OdcRef
import com.duluin.ftth.network.OdpRef
import com.duluin.ftth.network.OltPollingTarget
import com.duluin.ftth.network.PonPortTopology
import com.duluin.ftth.network.OltRef
import com.duluin.ftth.network.SiteRef
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import com.duluin.ftth.network.domain.model.Odc
import com.duluin.ftth.network.domain.model.Olt
import com.duluin.ftth.network.domain.model.Site
import com.duluin.ftth.network.UpstreamHop
import com.duluin.ftth.network.UpstreamPath
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.NetworkTileRenderer
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.application.port.outbound.SiteRepository
import com.duluin.ftth.network.domain.model.Odp
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Implementasi kontrak lintas-module. Sengaja tipis: tugasnya menerjemahkan
 * agregat internal menjadi bentuk publik dan mendelegasikan aturan bisnis ke
 * domain — bukan menjadi tempat aturan baru bertumbuh.
 */
@Service
@Transactional(readOnly = true)
class NetworkApiService(
    private val odpRepository: OdpRepository,
    private val odcRepository: OdcRepository,
    private val ponPortRepository: PonPortRepository,
    private val oltRepository: OltRepository,
    private val siteRepository: SiteRepository,
    private val cableRepository: CableRepository,
    private val tileRenderer: NetworkTileRenderer,
) : NetworkApi {

    override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?): ByteArray =
        tileRenderer.render(z, x, y, areaIds)

    override fun findOltByCode(code: String): OltRef? =
        oltRepository.findByCode(code.trim().uppercase())?.toRef()

    override fun findOltsByIds(ids: Set<UUID>): List<OltRef> =
        if (ids.isEmpty()) emptyList() else oltRepository.findAllByIds(ids).map { it.toRef() }

    override fun listAllOltIds(): Set<UUID> = oltRepository.findAllIds()

    override fun findSite(id: UUID): SiteRef? = siteRepository.findById(id)?.toRef()

    override fun oltsAtSite(siteId: UUID): List<OltRef> = oltRepository.findBySiteId(siteId).map { it.toRef() }

    override fun downstreamDeviceIds(oltIds: Set<UUID>, odcIds: Set<UUID>): DownstreamIds {
        // OLT → PON port → ODC. ODC yang menempel di ODC alarm langsung ikut juga.
        val ponPortIds = oltIds.flatMap { ponPortRepository.findByOltId(it) }.mapTo(HashSet()) { it.id }
        val allOdcIds = odcIds + odcRepository.findIdsByPonPortIds(ponPortIds)
        val odpIds = odpRepository.findIdsByOdcIds(allOdcIds)
        return DownstreamIds(odcIds = allOdcIds, odpIds = odpIds)
    }

    override fun odpIdsUnderPonPort(ponPortId: UUID): Set<UUID> {
        val odcIds = odcRepository.findIdsByPonPortIds(setOf(ponPortId))
        return if (odcIds.isEmpty()) emptySet() else odpRepository.findIdsByOdcIds(odcIds)
    }

    override fun topologyUnderPonPort(ponPortId: UUID): PonPortTopology? {
        val ponPort = ponPortRepository.findById(ponPortId) ?: return null
        // Sedikit ODC per PON port (biasanya 1-8) dan ini panel on-demand, bukan
        // jalur render panas — jadi memuat ODP per ODC dalam query terpisah masih pantas.
        return PonPortTopology(
            ponPortId = ponPort.id,
            label = ponPort.label,
            oltId = ponPort.oltId,
            odcs = odcRepository.findByPonPortId(ponPortId).map { odc ->
                OdcBranch(
                    odc = odc.toRef(),
                    odps = odpRepository.findByOdcId(odc.id).map { it.toRef() },
                )
            },
        )
    }

    override fun candidateOdpsUnderPonPort(oltId: UUID, ponPortLabel: String?): List<OdpRef> {
        val label = ponPortLabel?.trim().orEmpty()
        if (label.isEmpty()) return emptyList()
        val ponPort = ponPortRepository.findByOltIdAndLabel(oltId, label) ?: return emptyList()
        return findOdpsByIds(odpIdsUnderPonPort(ponPort.id))
    }

    override fun cablesTouchingNodes(nodeIds: Set<UUID>): List<CablePath> =
        cableRepository.findByEndpointNodeIds(nodeIds).map { it.toCablePath() }

    override fun cutImpact(cableId: UUID): CableCutImpact {
        val cable = cableRepository.findById(cableId)
            ?: throw NotFoundException("Kabel $cableId tidak ditemukan")

        // Telusuri subpohon fisik di hilir ujung bawah kabel lewat graf kabel:
        // dari sebuah simpul, ikuti HANYA kabel yang berawal darinya (arah hilir),
        // kumpulkan ujung berikutnya, ulangi. Ini menangkap ODP berantai maupun
        // drop yang tergambar — apa pun jenis kabelnya. `visited` menjaga dari
        // rangkaian melingkar. Ini panel on-demand saat kabel diklik, bukan jalur
        // render yang panas, jadi query per-simpul dapat diterima.
        val severedCables = LinkedHashMap<UUID, Cable>()
        severedCables[cable.id] = cable
        val odcIds = LinkedHashSet<UUID>()
        val odpIds = LinkedHashSet<UUID>()
        val customerIds = LinkedHashSet<UUID>()

        val visited = HashSet<UUID>()
        val queue = ArrayDeque<NetworkNodeRef>()
        queue += cable.to.ref
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!visited.add(node.id)) continue
            when (node.kind) {
                NetworkNodeKind.ODC -> odcIds += node.id
                NetworkNodeKind.ODP -> odpIds += node.id
                NetworkNodeKind.CUSTOMER -> customerIds += node.id
                else -> Unit
            }
            cableRepository.findByEndpoint(node)
                .filter { it.from.ref == node }
                .forEach { downstream ->
                    severedCables.putIfAbsent(downstream.id, downstream)
                    queue += downstream.to.ref
                }
        }

        // Keanggotaan ODP di bawah sebuah ODC dicatat lewat `odp.odcId`, tidak
        // selalu digambar sebagai kabel distribusi — jadi putus feeder melengkapi
        // daftar ODP lewat pohon perangkat agar tak ada pelanggan yang terlewat.
        if (odcIds.isNotEmpty()) {
            odpIds += downstreamDeviceIds(emptySet(), odcIds).odpIds
        }

        return CableCutImpact(
            cableId = cable.id,
            cableCode = cable.code,
            cableType = cable.cableType.name,
            severedRootKind = cable.to.kind.name,
            severedRootId = cable.to.id,
            odcIds = odcIds,
            odpIds = odpIds,
            customerIds = customerIds,
            severedCables = severedCables.values.map { it.toCablePath() },
        )
    }

    override fun findPollingTargets(oltIds: Set<UUID>): List<OltPollingTarget> {
        if (oltIds.isEmpty()) return emptyList()
        return oltRepository.findAllByIds(oltIds)
            // OLT yang sedang dinonaktifkan tidak perlu di-polling; alarmnya justru
            // akan menutupi gangguan sungguhan di perangkat lain.
            .filter { it.status.acceptsService() }
            .map { olt ->
                OltPollingTarget(
                    id = olt.id,
                    code = olt.code,
                    vendor = olt.vendor.name,
                    host = olt.managementIp?.value,
                    snmpCommunity = olt.snmpCommunity,
                    snmpPort = olt.snmpPort,
                )
            }
    }

    override fun findOdp(id: UUID): OdpRef? = odpRepository.findById(id)?.toRef()

    override fun requireOdp(id: UUID): OdpRef =
        findOdp(id) ?: throw NotFoundException("ODP $id tidak ditemukan")

    override fun findOdc(id: UUID): OdcRef? = odcRepository.findById(id)?.toRef()

    override fun requireOdc(id: UUID): OdcRef =
        findOdc(id) ?: throw NotFoundException("ODC $id tidak ditemukan")

    override fun findOdpsByIds(ids: Set<UUID>): List<OdpRef> =
        if (ids.isEmpty()) emptyList() else odpRepository.findAllByIds(ids).map { it.toRef() }

    override fun odpsInArea(areaIds: Set<UUID>?): List<OdpRef> =
        odpRepository.findAllInAreas(areaIds).map { it.toRef() }

    override fun assertOdpPortAssignable(odpId: UUID, portNumber: Int, occupiedPorts: Set<Int>) {
        val odp = odpRepository.findById(odpId) ?: throw NotFoundException("ODP $odpId tidak ditemukan")
        odp.assertPortAssignable(portNumber, occupiedPorts)
    }

    /**
     * Menelusuri jalur ke hulu satu tingkat demi satu tingkat. Berhenti tanpa
     * galat begitu sambungan berikutnya belum ada — jaringan yang sedang dibangun
     * memang normal setengah jadi, dan justru itu yang perlu terlihat di UI.
     */
    override fun upstreamOf(odpId: UUID): UpstreamPath {
        val odp = odpRepository.findById(odpId) ?: throw NotFoundException("ODP $odpId tidak ditemukan")
        val odc = odp.odcId?.let { odcRepository.findById(it) }
        val ponPort = odc?.ponPortId?.let { ponPortRepository.findById(it) }
        val olt = ponPort?.let { oltRepository.findById(it.oltId) }
        val site = olt?.let { siteRepository.findById(it.siteId) }

        val splitterLoss = odp.splitterRatio.insertionLossDb + (odc?.splitterRatio?.insertionLossDb ?: 0.0)

        return UpstreamPath(
            odp = odp.toRef(),
            odc = odc?.let { UpstreamHop(it.id, it.code, it.name) },
            ponPort = ponPort?.let { UpstreamHop(it.id, it.label, it.label) },
            olt = olt?.let { UpstreamHop(it.id, it.code, it.name) },
            site = site?.let { UpstreamHop(it.id, it.code, it.name) },
            splitterLossDb = splitterLoss,
        )
    }
}

private fun Site.toRef() = SiteRef(
    id = id,
    code = code,
    name = name,
    address = address,
    location = location,
    areaId = areaId,
)

private fun Olt.toRef() = OltRef(
    id = id,
    code = code,
    name = name,
    vendor = vendor.name,
    siteId = siteId,
    active = status.acceptsService(),
)

private fun Odp.toRef() = OdpRef(
    id = id,
    code = code,
    name = name,
    location = location,
    capacity = capacity,
    areaId = areaId,
    odcId = odcId,
    active = status.acceptsService(),
)

private fun Odc.toRef() = OdcRef(
    id = id,
    code = code,
    name = name,
    location = location,
    capacity = capacity,
    ponPortId = ponPortId,
    energized = isEnergized(),
)

private fun Cable.toCablePath() = CablePath(
    id = id,
    code = code,
    cableType = cableType.name,
    points = route.points,
    fromId = from.id,
    toId = to.id,
)
