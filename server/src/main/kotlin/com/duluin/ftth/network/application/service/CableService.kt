package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.RoutePath
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.network.application.port.inbound.CableView
import com.duluin.ftth.network.application.port.inbound.ManageCableUseCase
import com.duluin.ftth.network.application.port.inbound.SaveCableCommand
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.OdcRepository
import com.duluin.ftth.network.application.port.outbound.OdpRepository
import com.duluin.ftth.network.application.port.outbound.OltRepository
import com.duluin.ftth.network.application.port.outbound.SiteRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableType
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class CableService(
    private val cableRepository: CableRepository,
    private val siteRepository: SiteRepository,
    private val oltRepository: OltRepository,
    private val odcRepository: OdcRepository,
    private val odpRepository: OdpRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageCableUseCase {

    @Transactional(readOnly = true)
    override fun search(query: String, cableType: CableType?, pageRequest: PageRequest): Page<CableView> =
        cableRepository.search(query, cableType, pageRequest).map { it.toView() }

    @Transactional(readOnly = true)
    override fun get(id: UUID): CableView = requireCable(id).toView()

    override fun create(command: SaveCableCommand): CableView {
        val code = command.code.trim().uppercase()
        if (cableRepository.existsByCode(code)) throw ConflictException("Kode kabel '$code' sudah dipakai")
        val from = NetworkNodeRef(command.fromKind, command.fromId)
        val to = NetworkNodeRef(command.toKind, command.toId)
        assertNodesExist(from, to)
        val cable = cableRepository.save(
            Cable.create(
                tenantId = currentUser.current().tenantId,
                code = command.code,
                name = command.name,
                cableType = command.cableType,
                coreCount = command.coreCount,
                route = RoutePath(command.route),
                from = from,
                to = to,
                status = command.status,
            ),
        )
        auditor.record(
            "cable.created", "Cable", cable.id, cable.tenantId,
            mapOf("code" to cable.code, "type" to cable.cableType.name, "lengthMeters" to cable.lengthMeters),
        )
        return cable.toView()
    }

    override fun update(id: UUID, command: SaveCableCommand): CableView {
        val cable = requireCable(id)
        val from = NetworkNodeRef(command.fromKind, command.fromId)
        val to = NetworkNodeRef(command.toKind, command.toId)
        assertNodesExist(from, to)
        cable.update(
            name = command.name,
            cableType = command.cableType,
            coreCount = command.coreCount,
            route = RoutePath(command.route),
            from = from,
            to = to,
            status = command.status,
        )
        val saved = cableRepository.save(cable)
        auditor.record("cable.updated", "Cable", saved.id, saved.tenantId, mapOf("code" to saved.code))
        return saved.toView()
    }

    override fun delete(id: UUID) {
        val cable = requireCable(id)
        cableRepository.deleteById(id)
        auditor.record("cable.deleted", "Cable", id, cable.tenantId, mapOf("code" to cable.code))
    }

    /**
     * Ujung kabel tidak punya foreign key (bisa menunjuk tabel mana saja), jadi
     * keberadaannya diperiksa di sini. Tanpa ini, salah ketik id menghasilkan
     * kabel yang menggantung ke simpul yang tidak pernah ada — dan baru ketahuan
     * saat telusur jalur gagal berbulan-bulan kemudian.
     *
     * Pelanggan sengaja tidak diperiksa: datanya milik module customer, dan
     * network tidak boleh bergantung padanya. Integritasnya dijaga saat ONU
     * dipasang lewat [com.duluin.ftth.network.NetworkApi].
     */
    private fun assertNodesExist(vararg nodes: NetworkNodeRef) {
        nodes.forEach { node ->
            val exists = when (node.kind) {
                NetworkNodeKind.SITE -> siteRepository.findById(node.id) != null
                NetworkNodeKind.OLT -> oltRepository.findById(node.id) != null
                NetworkNodeKind.ODC -> odcRepository.findById(node.id) != null
                NetworkNodeKind.ODP -> odpRepository.findById(node.id) != null
                NetworkNodeKind.CUSTOMER -> true
            }
            if (!exists) throw NotFoundException("${node.kind} ${node.id} tidak ditemukan")
        }
    }

    private fun requireCable(id: UUID): Cable =
        cableRepository.findById(id) ?: throw NotFoundException("Kabel $id tidak ditemukan")
}

private fun Cable.toView() = CableView(
    id = id,
    code = code,
    name = name,
    cableType = cableType,
    coreCount = coreCount,
    route = route,
    lengthMeters = lengthMeters,
    fromKind = from.kind,
    fromId = from.id,
    toKind = to.kind,
    toId = to.id,
    status = status,
)
