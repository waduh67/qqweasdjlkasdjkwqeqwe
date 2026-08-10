package com.duluin.ftth.network.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.network.application.port.inbound.CableCoreListView
import com.duluin.ftth.network.application.port.inbound.CableCoreView
import com.duluin.ftth.network.application.port.inbound.ManageCableCoreUseCase
import com.duluin.ftth.network.application.port.inbound.UpdateCableCoresCommand
import com.duluin.ftth.network.application.port.outbound.CableCoreRepository
import com.duluin.ftth.network.application.port.outbound.CableRepository
import com.duluin.ftth.network.domain.model.Cable
import com.duluin.ftth.network.domain.model.CableCore
import com.duluin.ftth.network.domain.model.CoreStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class CableCoreService(
    private val cableRepository: CableRepository,
    private val cableCoreRepository: CableCoreRepository,
    private val auditor: AuditRecorder,
) : ManageCableCoreUseCase {

    @Transactional(readOnly = true)
    override fun list(cableId: UUID): CableCoreListView = requireCable(cableId).toListView()

    override fun update(cableId: UUID, command: UpdateCableCoresCommand): CableCoreListView {
        val cable = requireCable(cableId)
        if (command.coreNumbers.isEmpty()) throw ValidationException("Pilih minimal satu core")
        if (command.status == null && command.note == null && !command.clearNote) {
            throw ValidationException("Tak ada yang diubah: isi status atau catatan")
        }

        val cores = cableCoreRepository.findByCableId(cableId)
        val byNumber = cores.associateBy { it.coreNumber }
        val targets = command.coreNumbers.distinct().map { number ->
            byNumber[number] ?: throw NotFoundException("Core $number tidak ada di kabel ${cable.code}")
        }

        targets.forEach { core ->
            core.update(
                status = command.status ?: core.status,
                // clearNote menang atas note: keduanya terkirim hanya bila klien bingung.
                note = if (command.clearNote) null else command.note ?: core.note,
            )
        }
        cableCoreRepository.saveAll(targets)
        auditor.record(
            "cable.core.updated", "Cable", cable.id, cable.tenantId,
            mapOf(
                "code" to cable.code,
                "cores" to targets.map { it.coreNumber },
                "status" to (command.status?.name ?: "-"),
            ),
        )
        return cable.toListView()
    }

    private fun requireCable(cableId: UUID): Cable =
        cableRepository.findById(cableId) ?: throw NotFoundException("Kabel $cableId tidak ditemukan")

    private fun Cable.toListView(): CableCoreListView {
        val cores = cableCoreRepository.findByCableId(id)
        val perStatus = cores.groupingBy { it.status }.eachCount()
        return CableCoreListView(
            cableId = id,
            cableCode = code,
            cableName = name,
            coreCount = coreCount,
            coresPerTube = CableCore.CORES_PER_TUBE,
            free = perStatus[CoreStatus.FREE] ?: 0,
            used = perStatus[CoreStatus.USED] ?: 0,
            reserved = perStatus[CoreStatus.RESERVED] ?: 0,
            damaged = perStatus[CoreStatus.DAMAGED] ?: 0,
            cores = cores.map { it.toView() },
        )
    }

    private fun CableCore.toView() = CableCoreView(
        id = id,
        tubeNumber = tubeNumber,
        coreNumber = coreNumber,
        positionInTube = positionInTube,
        color = color.label,
        colorHex = color.hex,
        tubeColor = tubeColor.label,
        tubeColorHex = tubeColor.hex,
        status = status,
        note = note,
    )
}
