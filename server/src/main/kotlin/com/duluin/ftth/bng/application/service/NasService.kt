package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.inbound.ManageNasUseCase
import com.duluin.ftth.bng.application.port.inbound.NasView
import com.duluin.ftth.bng.application.port.inbound.SaveNasCommand
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class NasService(
    private val nasRepository: NasRepository,
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageNasUseCase {

    @Transactional(readOnly = true)
    override fun list(): List<NasView> = nasRepository.findAll().map { it.toView() }

    @Transactional(readOnly = true)
    override fun get(id: UUID): NasView = require(id).toView()

    override fun create(command: SaveNasCommand): NasView {
        val name = command.name.trim()
        if (nasRepository.existsByName(name)) throw ConflictException("BRAS '$name' sudah ada")
        val nas = nasRepository.save(
            Nas.create(
                tenantId = currentUser.current().tenantId,
                name = command.name,
                vendor = command.vendor,
                address = command.address,
                nasIdentifier = command.nasIdentifier,
                coaSecret = command.coaSecret,
                collectorId = command.collectorId,
            ),
        )
        auditor.record("bng.nas.created", "Nas", nas.id, nas.tenantId, mapOf("name" to nas.name, "vendor" to nas.vendor.name))
        return nas.toView()
    }

    override fun update(id: UUID, command: SaveNasCommand): NasView {
        val nas = require(id)
        val newName = command.name.trim()
        if (newName != nas.name && nasRepository.existsByName(newName)) {
            throw ConflictException("BRAS '$newName' sudah ada")
        }
        nas.update(
            name = command.name,
            vendor = command.vendor,
            address = command.address,
            nasIdentifier = command.nasIdentifier,
            coaSecret = command.coaSecret,
            collectorId = command.collectorId,
            enabled = command.enabled,
        )
        val saved = nasRepository.save(nas)
        auditor.record("bng.nas.updated", "Nas", saved.id, saved.tenantId, mapOf("name" to saved.name, "vendor" to saved.vendor.name))
        return saved.toView()
    }

    override fun delete(id: UUID) {
        val nas = require(id)
        val inUse = subscriberAccessRepository.countByNasId(id)
        if (inUse > 0) {
            throw ConflictException("BRAS '${nas.name}' masih menaungi $inUse akun PPPoE, pindahkan dulu")
        }
        nasRepository.deleteById(id)
        auditor.record("bng.nas.deleted", "Nas", id, nas.tenantId, mapOf("name" to nas.name))
    }

    private fun require(id: UUID): Nas =
        nasRepository.findById(id) ?: throw NotFoundException("BRAS $id tidak ditemukan")
}

private fun Nas.toView() = NasView(
    id = id,
    name = name,
    vendor = vendor.name,
    address = address,
    nasIdentifier = nasIdentifier,
    // Secret tak pernah dibocorkan; UI hanya perlu tahu sudah diisi atau belum.
    hasCoaSecret = coaSecret != null,
    collectorId = collectorId,
    enabled = enabled,
)
