package com.duluin.ftth.iam.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.iam.application.port.inbound.AreaView
import com.duluin.ftth.iam.application.port.inbound.CreateAreaCommand
import com.duluin.ftth.iam.application.port.inbound.ManageAreaUseCase
import com.duluin.ftth.iam.application.port.inbound.UpdateAreaCommand
import com.duluin.ftth.iam.application.port.outbound.AreaRepository
import com.duluin.ftth.iam.domain.model.Area
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class AreaService(
    private val areaRepository: AreaRepository,
    private val currentUser: CurrentUserProvider,
) : ManageAreaUseCase {

    override fun create(command: CreateAreaCommand): AreaView {
        val code = command.code.trim().uppercase()
        if (areaRepository.existsByCode(code)) throw ConflictException("Kode area '$code' sudah ada")
        command.parentId?.let { requireArea(it) }
        val area = areaRepository.save(
            Area.create(currentUser.current().tenantId, command.code, command.name, command.parentId),
        )
        return area.toView()
    }

    override fun update(id: UUID, command: UpdateAreaCommand): AreaView {
        val area = requireArea(id)
        if (command.parentId != null) {
            if (command.parentId == id) throw ValidationException("Area tidak boleh menjadi induk dirinya sendiri")
            requireArea(command.parentId)
        }
        area.update(command.name, command.parentId)
        return areaRepository.save(area).toView()
    }

    override fun delete(id: UUID) {
        requireArea(id)
        areaRepository.deleteById(id)
    }

    @Transactional(readOnly = true)
    override fun list(): List<AreaView> =
        areaRepository.findAll().map { it.toView() }.sortedBy { it.code }

    private fun requireArea(id: UUID): Area =
        areaRepository.findById(id) ?: throw NotFoundException("Area $id tidak ditemukan")
}
