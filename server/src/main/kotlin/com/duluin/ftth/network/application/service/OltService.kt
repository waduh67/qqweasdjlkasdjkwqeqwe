package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.network.OltDeletedEvent
import com.duluin.ftth.network.application.port.inbound.ManageOltUseCase
import com.duluin.ftth.network.application.port.inbound.OltView
import com.duluin.ftth.network.application.port.inbound.PonPortView
import com.duluin.ftth.network.application.port.inbound.SaveOltCommand
import com.duluin.ftth.network.application.port.inbound.SavePonPortCommand
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.application.port.outbound.SiteRepository
import com.duluin.ftth.network.domain.model.AssetStatus
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import com.duluin.ftth.network.domain.model.Olt
import com.duluin.ftth.network.domain.model.PonPort
import com.duluin.ftth.network.domain.model.Site
import com.duluin.ftth.network.domain.model.vo.ManagementIp
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class OltService(
    private val oltRepository: OltRepository,
    private val siteRepository: SiteRepository,
    private val ponPortRepository: PonPortRepository,
    private val odcRepository: OdcRepository,
    private val cableAttachment: CableAttachmentService,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
    private val events: ApplicationEventPublisher,
) : ManageOltUseCase {

    @Transactional(readOnly = true)
    override fun search(query: String, siteId: UUID?, pageRequest: PageRequest): Page<OltView> {
        val page = oltRepository.search(query, siteId, pageRequest)
        val siteNames = siteRepository.findAllByIds(page.content.mapTo(HashSet()) { it.siteId })
            .associate { it.id to it.name }
        val portCounts = ponPortRepository.countByOltIds(page.content.mapTo(HashSet()) { it.id })
        return page.map { it.toView(siteNames[it.siteId], (portCounts[it.id] ?: 0).toInt()) }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): OltView {
        val olt = requireOlt(id)
        return olt.toView(siteRepository.findById(olt.siteId)?.name, ponPortRepository.findByOltId(id).size)
    }

    override fun create(command: SaveOltCommand): OltView {
        val code = command.code.trim().uppercase()
        if (oltRepository.existsByCode(code)) throw ConflictException("Kode OLT '$code' sudah dipakai")
        val site = requireSite(command.siteId)
        val olt = oltRepository.save(
            Olt.create(
                tenantId = currentUser.current().tenantId,
                siteId = command.siteId,
                code = command.code,
                name = command.name,
                vendor = command.vendor,
                model = command.model,
                managementIp = ManagementIp.ofNullable(command.managementIp),
                snmpCommunity = command.snmpCommunity,
                // Tanpa koordinat eksplisit, OLT berdiri di lokasi site-nya; area
                // scope selalu ikut site (OLT tinggal di dalamnya).
                location = command.location ?: site.location,
                areaId = site.areaId,
                snmpPort = command.snmpPort,
                description = command.description,
                snmpEnabled = command.snmpEnabled,
                snmpVersion = command.snmpVersion,
                webEnabled = command.webEnabled,
                webProtocol = command.webProtocol,
                webPort = command.webPort,
                webUsername = command.webUsername,
                webPassword = command.webPassword,
            ),
        )
        auditor.record("olt.created", "Olt", olt.id, olt.tenantId, mapOf("code" to olt.code, "vendor" to olt.vendor.name))
        return olt.toView(site.name, 0)
    }

    override fun update(id: UUID, command: SaveOltCommand): OltView {
        val olt = requireOlt(id)
        val site = requireSite(command.siteId)
        // Koordinat kosong = pertahankan yang tersimpan; kabel hanya menempel ulang
        // bila titiknya benar-benar berpindah.
        val previousLocation = olt.location
        val newLocation = command.location ?: previousLocation
        olt.update(
            siteId = command.siteId,
            name = command.name,
            vendor = command.vendor,
            model = command.model,
            managementIp = ManagementIp.ofNullable(command.managementIp),
            snmpPort = command.snmpPort,
            // area re-inherit dari site (menampung kasus OLT dipindah ke site di area lain).
            location = newLocation,
            areaId = site.areaId,
            description = command.description,
            snmpEnabled = command.snmpEnabled,
            snmpVersion = command.snmpVersion,
            webEnabled = command.webEnabled,
            webProtocol = command.webProtocol,
            webPort = command.webPort,
            webUsername = command.webUsername,
        )
        olt.changeSnmpCommunity(command.snmpCommunity)
        olt.changeWebPassword(command.webPassword)
        val saved = oltRepository.save(olt)
        if (newLocation != previousLocation) {
            cableAttachment.resnapForMovedNode(NetworkNodeRef(NetworkNodeKind.OLT, id), saved.location)
        }
        auditor.record("olt.updated", "Olt", saved.id, saved.tenantId, mapOf("code" to saved.code))
        return saved.toView(site.name, ponPortRepository.findByOltId(id).size)
    }

    override fun relocate(id: UUID, location: Coordinate): OltView {
        val olt = requireOlt(id)
        olt.relocate(location)
        val saved = oltRepository.save(olt)
        cableAttachment.resnapForMovedNode(NetworkNodeRef(NetworkNodeKind.OLT, id), saved.location)
        auditor.record("olt.relocated", "Olt", saved.id, saved.tenantId, mapOf("code" to saved.code))
        return saved.toView(siteRepository.findById(saved.siteId)?.name, ponPortRepository.findByOltId(id).size)
    }

    override fun changeStatus(id: UUID, status: AssetStatus): OltView {
        val olt = requireOlt(id)
        olt.changeStatus(status)
        val saved = oltRepository.save(olt)
        auditor.record(
            "olt.status_changed", "Olt", saved.id, saved.tenantId,
            mapOf("code" to saved.code, "status" to status.name),
        )
        return saved.toView(siteRepository.findById(saved.siteId)?.name, ponPortRepository.findByOltId(id).size)
    }

    override fun delete(id: UUID) {
        val olt = requireOlt(id)
        val ponPortIds = ponPortRepository.findByOltId(id).mapTo(HashSet()) { it.id }
        val attachedOdc = odcRepository.countByPonPortIds(ponPortIds).values.sum()
        if (attachedOdc > 0) {
            throw ConflictException("OLT ${olt.code} masih melayani $attachedOdc ODC, lepas feeder-nya dulu")
        }
        oltRepository.deleteById(id)
        auditor.record("olt.deleted", "Olt", id, olt.tenantId, mapOf("code" to olt.code))
        // Module lain yang merujuk OLT tanpa foreign key (mis. kotak masuk ONU
        // terdeteksi di monitoring) membersihkan sisa yatimnya setelah commit.
        events.publishEvent(OltDeletedEvent(olt.tenantId, olt.id, olt.code))
    }

    @Transactional(readOnly = true)
    override fun listPonPorts(oltId: UUID): List<PonPortView> {
        requireOlt(oltId)
        val ports = ponPortRepository.findByOltId(oltId)
        val odcCounts = odcRepository.countByPonPortIds(ports.mapTo(HashSet()) { it.id })
        return ports.map { it.toView(odcCounts[it.id] ?: 0) }
    }

    override fun addPonPort(oltId: UUID, command: SavePonPortCommand): PonPortView {
        val olt = requireOlt(oltId)
        if (ponPortRepository.existsByOltIdAndLabel(oltId, command.label.trim())) {
            throw ConflictException("PON port '${command.label}' sudah ada pada OLT ${olt.code}")
        }
        val port = ponPortRepository.save(
            PonPort.create(
                tenantId = olt.tenantId,
                oltId = oltId,
                label = command.label,
                description = command.description,
                status = command.status,
            ),
        )
        auditor.record(
            "olt.pon_port_added", "PonPort", port.id, port.tenantId,
            mapOf("olt" to olt.code, "label" to port.label),
        )
        return port.toView(0)
    }

    override fun updatePonPort(id: UUID, command: SavePonPortCommand): PonPortView {
        val port = requirePonPort(id)
        if (command.label.trim() != port.label &&
            ponPortRepository.existsByOltIdAndLabel(port.oltId, command.label.trim())
        ) {
            throw ConflictException("PON port '${command.label}' sudah ada pada OLT ini")
        }
        port.update(command.label, command.description, command.status)
        val saved = ponPortRepository.save(port)
        auditor.record("olt.pon_port_updated", "PonPort", saved.id, saved.tenantId, mapOf("label" to saved.label))
        return saved.toView(odcRepository.countByPonPortId(id))
    }

    override fun deletePonPort(id: UUID) {
        val port = requirePonPort(id)
        val attached = odcRepository.countByPonPortId(id)
        if (attached > 0) {
            throw ConflictException("PON port ${port.label} masih melayani $attached ODC")
        }
        ponPortRepository.deleteById(id)
        auditor.record("olt.pon_port_deleted", "PonPort", id, port.tenantId, mapOf("label" to port.label))
    }

    private fun requireOlt(id: UUID): Olt =
        oltRepository.findById(id) ?: throw NotFoundException("OLT $id tidak ditemukan")

    private fun requirePonPort(id: UUID): PonPort =
        ponPortRepository.findById(id) ?: throw NotFoundException("PON port $id tidak ditemukan")

    private fun requireSite(siteId: UUID): Site =
        siteRepository.findById(siteId) ?: throw NotFoundException("Site $siteId tidak ditemukan")
}

private fun Olt.toView(siteName: String?, ponPortCount: Int) = OltView(
    id = id,
    code = code,
    name = name,
    siteId = siteId,
    siteName = siteName,
    vendor = vendor,
    model = model,
    managementIp = managementIp?.value,
    status = status,
    snmpConfigured = !snmpCommunity.isNullOrBlank(),
    snmpPort = snmpPort,
    pollable = isPollable(),
    ponPortCount = ponPortCount,
    location = location,
    areaId = areaId,
    description = description,
    snmpEnabled = snmpEnabled,
    snmpVersion = snmpVersion,
    webEnabled = webEnabled,
    webProtocol = webProtocol,
    webPort = webPort,
    webUsername = webUsername,
    webPasswordConfigured = !webPassword.isNullOrBlank(),
)

private fun PonPort.toView(odcCount: Long) = PonPortView(
    id = id,
    oltId = oltId,
    label = label,
    description = description,
    status = status,
    odcCount = odcCount,
)
