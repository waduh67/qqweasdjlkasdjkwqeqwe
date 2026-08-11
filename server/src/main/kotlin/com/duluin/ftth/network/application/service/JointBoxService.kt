package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.geo.Coordinate
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.network.application.port.inbound.JointBoxView
import com.duluin.ftth.network.application.port.inbound.ManageJointBoxUseCase
import com.duluin.ftth.network.application.port.inbound.SaveJointBoxCommand
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.application.port.outbound.FiberConnectionRepository
import com.duluin.ftth.network.application.port.outbound.JointBoxRepository
import com.duluin.ftth.network.domain.model.JointBox
import com.duluin.ftth.network.domain.model.NetworkNodeKind
import com.duluin.ftth.network.domain.model.NetworkNodeRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class JointBoxService(
    private val jointBoxRepository: JointBoxRepository,
    private val connections: FiberConnectionRepository,
    private val cableRepository: CableRepository,
    private val cableAttachment: CableAttachmentService,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageJointBoxUseCase {

    @Transactional(readOnly = true)
    override fun search(query: String, pageRequest: PageRequest): Page<JointBoxView> {
        val page = jointBoxRepository.search(query, currentUser.current().areaScope(), pageRequest)
        val spliceCounts = connections.countByClosureIds(page.content.mapTo(HashSet()) { it.id })
        return page.map { it.toView(spliceCounts[it.id] ?: 0L) }
    }

    @Transactional(readOnly = true)
    override fun get(id: UUID): JointBoxView = requireJointBox(id).toView()

    override fun create(command: SaveJointBoxCommand): JointBoxView {
        val code = command.code.trim().uppercase()
        if (jointBoxRepository.existsByCode(code)) throw ConflictException("Kode joint box '$code' sudah dipakai")
        val jointBox = jointBoxRepository.save(
            JointBox.create(
                tenantId = currentUser.current().tenantId,
                code = command.code,
                name = command.name,
                address = command.address,
                location = command.location,
                areaId = command.areaId,
                trayCount = command.trayCount,
                capacity = command.capacity,
                status = command.status,
                installedOn = command.installedOn,
                mounting = command.mounting,
                notes = command.notes,
            ),
        )
        auditor.record(
            "jointbox.created", "JointBox", jointBox.id, jointBox.tenantId,
            mapOf("code" to jointBox.code, "capacity" to jointBox.capacity),
        )
        return jointBox.toView()
    }

    override fun update(id: UUID, command: SaveJointBoxCommand): JointBoxView {
        val jointBox = requireJointBox(id)
        val moved = jointBox.location != command.location
        assertCapacityFitsContents(jointBox, command.capacity)
        jointBox.update(
            name = command.name,
            address = command.address,
            location = command.location,
            areaId = command.areaId,
            trayCount = command.trayCount,
            capacity = command.capacity,
            status = command.status,
            installedOn = command.installedOn,
            mounting = command.mounting,
            notes = command.notes,
        )
        jointBoxRepository.save(jointBox)
        if (moved) cableAttachment.resnapForMovedNode(NetworkNodeRef(NetworkNodeKind.JOINT_BOX, id), jointBox.location)
        auditor.record("jointbox.updated", "JointBox", jointBox.id, jointBox.tenantId, mapOf("code" to jointBox.code))
        return jointBox.toView()
    }

    override fun relocate(id: UUID, location: Coordinate): JointBoxView {
        val jointBox = requireJointBox(id)
        jointBox.relocate(location)
        jointBoxRepository.save(jointBox)
        cableAttachment.resnapForMovedNode(NetworkNodeRef(NetworkNodeKind.JOINT_BOX, id), jointBox.location)
        auditor.record("jointbox.relocated", "JointBox", jointBox.id, jointBox.tenantId, mapOf("code" to jointBox.code))
        return jointBox.toView()
    }

    /**
     * Menolak penghapusan selama kotaknya masih "hidup".
     *
     * Dua penjagaan, dua akibat yang berbeda. Sambungan di dalamnya: menghapus JB
     * berarti menghapus catatan sambungan yang di lapangan masih menyalurkan
     * layanan. Kabel yang berujung padanya: ujung kabel tak ber-foreign-key, jadi
     * penghapusan diam-diam menyisakan kabel yang menunjuk simpul yang tak pernah
     * ada — persis kegagalan senyap yang baru ketahuan saat telusur jalur.
     */
    override fun delete(id: UUID) {
        val jointBox = requireJointBox(id)
        val splices = connections.countByClosureId(id)
        if (splices > 0) {
            throw ConflictException(
                "Joint box ${jointBox.code} masih berisi $splices sambungan, lepas dulu sebelum dihapus",
            )
        }
        val attached = cableRepository.findByEndpoint(NetworkNodeRef(NetworkNodeKind.JOINT_BOX, id))
        if (attached.isNotEmpty()) {
            val codes = attached.joinToString(", ") { it.code }
            throw ConflictException(
                "Joint box ${jointBox.code} masih jadi ujung kabel $codes, ubah dulu ujung kabelnya",
            )
        }
        jointBoxRepository.deleteById(id)
        auditor.record("jointbox.deleted", "JointBox", id, jointBox.tenantId, mapOf("code" to jointBox.code))
    }

    /**
     * Kapasitas boleh dinaikkan kapan saja, tapi tak boleh diturunkan di bawah isi
     * yang sudah ada — kotak yang di data terlihat muat padahal di lapangan sudah
     * penuh membuat perencanaan salah sejak awal.
     */
    private fun assertCapacityFitsContents(jointBox: JointBox, capacity: Int) {
        val splices = connections.countByClosureId(jointBox.id)
        if (capacity < splices) {
            throw ConflictException(
                "Kapasitas tak bisa diturunkan jadi $capacity: joint box ${jointBox.code} sudah berisi $splices sambungan",
            )
        }
    }

    private fun requireJointBox(id: UUID): JointBox =
        jointBoxRepository.findById(id) ?: throw NotFoundException("Joint box $id tidak ditemukan")

    private fun JointBox.toView(spliceCount: Long = connections.countByClosureId(id)) = JointBoxView(
        id = id,
        code = code,
        name = name,
        address = address,
        location = location,
        areaId = areaId,
        trayCount = trayCount,
        capacity = capacity,
        spliceCount = spliceCount,
        status = status,
        installedOn = installedOn,
        mounting = mounting,
        notes = notes,
    )
}
