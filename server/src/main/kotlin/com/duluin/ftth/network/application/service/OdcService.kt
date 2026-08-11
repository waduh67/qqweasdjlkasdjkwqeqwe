package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.network.application.port.inbound.ManageOdcUseCase
import com.duluin.ftth.network.application.port.inbound.OdcView
import com.duluin.ftth.network.application.port.inbound.SaveOdcCommand
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.domain.model.ClosureKind
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import com.duluin.ftth.network.domain.model.Odc
import com.duluin.ftth.network.domain.model.Splitter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class OdcService(
    private val odcRepository: OdcRepository,
    private val odpRepository: OdpRepository,
    private val ponPortRepository: PonPortRepository,
    private val oltRepository: OltRepository,
    private val cableAttachment: CableAttachmentService,
    private val splitters: SplitterService,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageOdcUseCase {

    @Transactional(readOnly = true)
    override fun search(query: String, pageRequest: PageRequest): Page<OdcView> {
        val page = odcRepository.search(query, currentUser.current().areaScope(), pageRequest)
        val uplinks = resolveUplinks(page.content.mapNotNullTo(HashSet()) { it.ponPortId })
        val ids = page.content.mapTo(HashSet()) { it.id }
        val odpCounts = odpRepository.countByOdcIds(ids)
        val contents = splitters.contentsOf(ids)
        return page.map {
            it.toView(uplinks[it.ponPortId], odpCounts[it.id] ?: 0, contents[it.id].orEmpty())
        }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): OdcView {
        val odc = requireOdc(id)
        val uplink = odc.ponPortId?.let { resolveUplinks(setOf(it))[it] }
        return odc.toView(uplink, odpRepository.countByOdcId(id), splitters.contentsOf(setOf(id))[id].orEmpty())
    }

    override fun create(command: SaveOdcCommand): OdcView {
        val code = command.code.trim().uppercase()
        if (odcRepository.existsByCode(code)) throw ConflictException("Kode ODC '$code' sudah dipakai")
        command.ponPortId?.let { requirePonPortExists(it) }
        val odc = odcRepository.save(
            Odc.create(
                tenantId = currentUser.current().tenantId,
                code = command.code,
                name = command.name,
                address = command.address,
                location = command.location,
                areaId = command.areaId,
                ponPortId = command.ponPortId,
                capacity = command.capacity,
                status = command.status,
            ),
        )
        splitters.applyPrimaryRatio(ClosureKind.ODC, odc.id, command.splitterRatio)
        auditor.record("odc.created", "Odc", odc.id, odc.tenantId, mapOf("code" to odc.code))
        return get(odc.id)
    }

    override fun update(id: UUID, command: SaveOdcCommand): OdcView {
        val odc = requireOdc(id)
        val moved = odc.location != command.location
        odc.update(
            name = command.name,
            address = command.address,
            location = command.location,
            areaId = command.areaId,
            capacity = command.capacity,
            status = command.status,
        )
        odcRepository.save(odc)
        splitters.applyPrimaryRatio(ClosureKind.ODC, odc.id, command.splitterRatio)
        if (moved) cableAttachment.resnapForMovedNode(NetworkNodeRef(NetworkNodeKind.ODC, id), odc.location)
        auditor.record("odc.updated", "Odc", odc.id, odc.tenantId, mapOf("code" to odc.code))
        return get(id)
    }

    override fun relocate(id: UUID, location: Coordinate): OdcView {
        val odc = requireOdc(id)
        odc.relocate(location)
        odcRepository.save(odc)
        cableAttachment.resnapForMovedNode(NetworkNodeRef(NetworkNodeKind.ODC, id), odc.location)
        auditor.record("odc.relocated", "Odc", odc.id, odc.tenantId, mapOf("code" to odc.code))
        return get(id)
    }

    override fun connect(id: UUID, ponPortId: UUID?): OdcView {
        val odc = requireOdc(id)
        ponPortId?.let { requirePonPortExists(it) }
        odc.connectTo(ponPortId)
        odcRepository.save(odc)
        auditor.record(
            "odc.connected", "Odc", odc.id, odc.tenantId,
            mapOf("code" to odc.code, "ponPortId" to ponPortId?.toString()),
        )
        return get(id)
    }

    override fun delete(id: UUID) {
        val odc = requireOdc(id)
        val odpCount = odpRepository.countByOdcId(id)
        if (odpCount > 0) {
            throw ConflictException("ODC ${odc.code} masih menyuplai $odpCount ODP, lepas dulu sambungannya")
        }
        splitters.removeAllOf(id)
        odcRepository.deleteById(id)
        auditor.record("odc.deleted", "Odc", id, odc.tenantId, mapOf("code" to odc.code))
    }

    /** Melabeli PON port beserta nama OLT-nya dalam dua query, bukan per baris. */
    private fun resolveUplinks(ponPortIds: Set<UUID>): Map<UUID, Uplink> {
        if (ponPortIds.isEmpty()) return emptyMap()
        val ports = ponPortRepository.findAllByIds(ponPortIds)
        val oltNames = oltRepository.findAllByIds(ports.mapTo(HashSet()) { it.oltId })
            .associate { it.id to it.name }
        return ports.associate { it.id to Uplink(it.label, oltNames[it.oltId]) }
    }

    private fun requireOdc(id: UUID): Odc =
        odcRepository.findById(id) ?: throw NotFoundException("ODC $id tidak ditemukan")

    private fun requirePonPortExists(ponPortId: UUID) {
        ponPortRepository.findById(ponPortId) ?: throw NotFoundException("PON port $ponPortId tidak ditemukan")
    }
}

private data class Uplink(val ponPortLabel: String, val oltName: String?)

private fun Odc.toView(uplink: Uplink?, odpCount: Long, contents: List<Splitter>) = OdcView(
    id = id,
    code = code,
    name = name,
    address = address,
    location = location,
    areaId = areaId,
    ponPortId = ponPortId,
    ponPortLabel = uplink?.ponPortLabel,
    oltName = uplink?.oltName,
    splitterRatio = Splitter.summarize(contents),
    splitterCount = contents.size,
    splitterLegs = contents.sumOf { it.legCount },
    capacity = capacity,
    odpCount = odpCount,
    status = status,
    energized = isEnergized(),
)
