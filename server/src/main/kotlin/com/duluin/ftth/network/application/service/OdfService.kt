package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.network.application.port.inbound.ManageOdfUseCase
import com.duluin.ftth.network.application.port.inbound.OdfUplinkView
import com.duluin.ftth.network.application.port.inbound.OdfView
import com.duluin.ftth.network.application.port.inbound.SaveOdfCommand
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.application.port.outbound.OdfRepository
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.application.port.outbound.SiteRepository
import com.duluin.ftth.network.domain.model.ConnectionPointKind
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import com.duluin.ftth.network.domain.model.Odf
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class OdfService(
    private val odfRepository: OdfRepository,
    private val siteRepository: SiteRepository,
    private val connections: FiberConnectionRepository,
    private val cableRepository: CableRepository,
    private val ponPortRepository: PonPortRepository,
    private val oltRepository: OltRepository,
    private val cableAttachment: CableAttachmentService,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageOdfUseCase {

    @Transactional(readOnly = true)
    override fun search(query: String, pageRequest: PageRequest): Page<OdfView> {
        val page = odfRepository.search(query, currentUser.current().areaScope(), pageRequest)
        val ids = page.content.mapTo(HashSet()) { it.id }
        val splices = connections.countByClosureIds(ids)
        val usedPorts = connections.countUsedPortsOfNodes(ConnectionPointKind.ODF_PORT, ids)
        val siteNames = page.content.mapTo(HashSet()) { it.siteId }
            .mapNotNull { siteRepository.findById(it) }
            .associate { it.id to it.name }
        val uplinks = uplinksOf(ids)
        return page.map {
            it.toView(
                spliceCount = splices[it.id] ?: 0L,
                usedPortCount = usedPorts[it.id] ?: 0L,
                siteName = siteNames[it.siteId],
                olts = uplinks[it.id].orEmpty(),
            )
        }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): OdfView = requireOdf(id).toView()

    override fun create(command: SaveOdfCommand): OdfView {
        val code = command.code.trim().uppercase()
        if (odfRepository.existsByCode(code)) throw ConflictException("Kode ODF '$code' sudah dipakai")
        requireSite(command.siteId)
        val odf = odfRepository.save(
            Odf.create(
                tenantId = currentUser.current().tenantId,
                code = command.code,
                name = command.name,
                siteId = command.siteId,
                location = command.location,
                areaId = command.areaId,
                portCount = command.portCount,
                status = command.status,
            ),
        )
        auditor.record(
            "odf.created", "Odf", odf.id, odf.tenantId,
            mapOf("code" to odf.code, "portCount" to odf.portCount),
        )
        return odf.toView()
    }

    override fun update(id: UUID, command: SaveOdfCommand): OdfView {
        val odf = requireOdf(id)
        requireSite(command.siteId)
        val moved = odf.location != command.location
        assertPortCountFitsContents(odf, command.portCount)
        odf.update(
            name = command.name,
            siteId = command.siteId,
            location = command.location,
            areaId = command.areaId,
            portCount = command.portCount,
            status = command.status,
        )
        odfRepository.save(odf)
        if (moved) cableAttachment.resnapForMovedNode(NetworkNodeRef(NetworkNodeKind.ODF, id), odf.location)
        auditor.record("odf.updated", "Odf", odf.id, odf.tenantId, mapOf("code" to odf.code))
        return odf.toView()
    }

    override fun relocate(id: UUID, location: Coordinate): OdfView {
        val odf = requireOdf(id)
        odf.relocate(location)
        odfRepository.save(odf)
        cableAttachment.resnapForMovedNode(NetworkNodeRef(NetworkNodeKind.ODF, id), odf.location)
        auditor.record("odf.relocated", "Odf", odf.id, odf.tenantId, mapOf("code" to odf.code))
        return odf.toView()
    }

    /**
     * Sama seperti joint box: rak yang masih berisi sambungan atau masih jadi
     * ujung kabel tak boleh lenyap. Ujung kabel tak ber-foreign-key, jadi
     * penghapusan diam-diam menyisakan kabel yang menunjuk rak yang tak ada —
     * kegagalan senyap yang baru ketahuan saat telusur jalur.
     */
    override fun delete(id: UUID) {
        val odf = requireOdf(id)
        val splices = connections.countByClosureId(id)
        if (splices > 0) {
            throw ConflictException("ODF ${odf.code} masih berisi $splices sambungan, lepas dulu sebelum dihapus")
        }
        val attached = cableRepository.findByEndpoint(NetworkNodeRef(NetworkNodeKind.ODF, id))
        if (attached.isNotEmpty()) {
            val codes = attached.joinToString(", ") { it.code }
            throw ConflictException("ODF ${odf.code} masih jadi ujung kabel $codes, ubah dulu ujung kabelnya")
        }
        odfRepository.deleteById(id)
        auditor.record("odf.deleted", "Odf", id, odf.tenantId, mapOf("code" to odf.code))
    }

    /**
     * Rak boleh diperbesar kapan saja, tapi tak boleh menyusut melewati port yang
     * sudah tersambung: port 30 yang lenyap dari data tetap terpasang di rak, dan
     * sambungannya jadi menunjuk adapter yang menurut sistem tak pernah ada.
     */
    private fun assertPortCountFitsContents(odf: Odf, portCount: Int) {
        if (portCount >= odf.portCount) return
        val highest = connections.findByClosureId(odf.id)
            .flatMap { listOf(it.a, it.b) }
            .filter { it.kind == ConnectionPointKind.ODF_PORT && it.nodeId == odf.id }
            .mapNotNull { it.portNumber }
            .maxOrNull() ?: return
        if (portCount < highest) {
            throw ConflictException(
                "Jumlah port tak bisa dikurangi jadi $portCount: ODF ${odf.code} masih tersambung di port $highest",
            )
        }
    }

    /**
     * OLT mana yang tercolok ke tiap rak — dibaca dari patchcord yang tercatat,
     * bukan dari isian yang diketik orang. Lihat [OdfUplinkView] soal kenapa
     * jawabannya daftar dan bukan satu nama.
     *
     * Dihitung memakai tiga query untuk seluruh halaman, bukan per rak: daftar
     * ODF dibuka jauh lebih sering daripada isinya berubah, dan N+1 di sini
     * langsung terasa di layar yang paling sering dipakai.
     */
    private fun uplinksOf(odfIds: Set<UUID>): Map<UUID, List<OdfUplinkView>> {
        if (odfIds.isEmpty()) return emptyMap()
        // Satu port yang kedua sisinya tercatat tetap satu port: yang dihitung
        // adalah adapternya, sama seperti usedPortCount.
        val patches = connections.findByNodeIds(ConnectionPointKind.ODF_PORT, odfIds)
            .mapNotNull { connection ->
                val ends = listOf(connection.a, connection.b)
                val rack = ends.firstOrNull { it.kind == ConnectionPointKind.ODF_PORT && it.nodeId in odfIds }
                val pon = ends.firstOrNull { it.kind == ConnectionPointKind.PON_PORT }
                if (rack?.nodeId == null || pon?.nodeId == null) null
                else Triple(rack.nodeId, rack.portNumber, pon.nodeId)
            }
            .distinct()
        if (patches.isEmpty()) return emptyMap()
        val oltIdOfPonPort = ponPortRepository.findAllByIds(patches.mapTo(HashSet()) { it.third })
            .associate { it.id to it.oltId }
        val olts = oltRepository.findAllByIds(oltIdOfPonPort.values.toSet()).associateBy { it.id }
        return patches.groupBy { it.first }.mapValues { (_, rows) ->
            rows.mapNotNull { oltIdOfPonPort[it.third] }
                .groupingBy { it }
                .eachCount()
                .mapNotNull { (oltId, ports) ->
                    olts[oltId]?.let { OdfUplinkView(it.id, it.code, it.name, ports) }
                }
                // Yang memakai paling banyak port disebut duluan — itu yang
                // dimaksud orang saat bertanya "rak ini punya OLT mana".
                .sortedWith(compareByDescending<OdfUplinkView> { it.portCount }.thenBy { it.oltCode })
        }
    }

    private fun requireSite(siteId: UUID) {
        siteRepository.findById(siteId) ?: throw NotFoundException("Site $siteId tidak ditemukan")
    }

    private fun requireOdf(id: UUID): Odf =
        odfRepository.findById(id) ?: throw NotFoundException("ODF $id tidak ditemukan")

    private fun Odf.toView(
        spliceCount: Long = connections.countByClosureId(id),
        usedPortCount: Long = connections.countUsedPortsOfNode(ConnectionPointKind.ODF_PORT, id),
        siteName: String? = siteRepository.findById(siteId)?.name,
        olts: List<OdfUplinkView> = uplinksOf(setOf(id))[id].orEmpty(),
    ) = OdfView(
        id = id,
        code = code,
        name = name,
        siteId = siteId,
        siteName = siteName,
        location = location,
        areaId = areaId,
        portCount = portCount,
        usedPortCount = usedPortCount,
        spliceCount = spliceCount,
        status = status,
        olts = olts,
    )
}
