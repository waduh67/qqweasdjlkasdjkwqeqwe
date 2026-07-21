package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.monitoring.application.port.inbound.CollectorCreated
import com.duluin.ftth.monitoring.application.port.inbound.CollectorView
import com.duluin.ftth.monitoring.application.port.inbound.ManageCollectorUseCase
import com.duluin.ftth.monitoring.application.port.inbound.SaveCollectorCommand
import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.domain.model.Collector
import com.duluin.ftth.network.NetworkApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class CollectorService(
    private val collectorRepository: CollectorRepository,
    private val networkApi: NetworkApi,
    private val currentUser: CurrentUserProvider,
    private val auditor: AuditRecorder,
) : ManageCollectorUseCase {

    @Transactional(readOnly = true)
    override fun list(): List<CollectorView> =
        collectorRepository.findAllByTenant(currentUser.current().tenantId).map { it.toView() }

    @Transactional(readOnly = true)
    override fun get(id: UUID): CollectorView = require(id).toView()

    override fun create(command: SaveCollectorCommand): CollectorCreated {
        val tenantId = currentUser.current().tenantId
        if (collectorRepository.existsByName(tenantId, command.name)) {
            throw ConflictException("Collector bernama '${command.name}' sudah ada")
        }
        val (collector, apiKey) = Collector.create(tenantId, command.name, command.pollIntervalSeconds)
        val saved = collectorRepository.save(collector)
        auditor.record(
            "collector.created", "Collector", saved.id, saved.tenantId,
            // API key sengaja TIDAK dicatat di audit: jejak audit bisa dibaca
            // siapa pun yang punya izin audit.log.view.
            mapOf("name" to saved.name, "apiKeyHint" to saved.apiKeyHint),
        )
        return CollectorCreated(saved.toView(), apiKey)
    }

    override fun update(id: UUID, command: SaveCollectorCommand): CollectorView {
        val collector = require(id)
        collector.update(command.name, command.pollIntervalSeconds, command.status)
        val saved = collectorRepository.save(collector)
        auditor.record(
            "collector.updated", "Collector", saved.id, saved.tenantId,
            mapOf("name" to saved.name, "status" to saved.status.name),
        )
        return saved.toView()
    }

    override fun assignOlts(id: UUID, oltIds: Set<UUID>): CollectorView {
        val collector = require(id)
        // Menolak id OLT yang tidak dikenal supaya salah ketik tidak berakhir
        // sebagai collector yang diam-diam tidak mem-polling apa pun.
        val known = networkApi.findOltsByIds(oltIds).mapTo(HashSet()) { it.id }
        val unknown = oltIds - known
        if (unknown.isNotEmpty()) throw NotFoundException("OLT tidak ditemukan: ${unknown.joinToString()}")

        collectorRepository.replaceAssignedOltIds(id, oltIds)
        auditor.record(
            "collector.olts_assigned", "Collector", collector.id, collector.tenantId,
            mapOf("count" to oltIds.size),
        )
        return require(id).toView()
    }

    override fun delete(id: UUID) {
        val collector = require(id)
        collectorRepository.deleteById(id)
        auditor.record("collector.deleted", "Collector", id, collector.tenantId, mapOf("name" to collector.name))
    }

    private fun require(id: UUID): Collector {
        val collector = collectorRepository.findById(id)
            ?: throw NotFoundException("Collector $id tidak ditemukan")
        // Tabel collector sengaja tanpa RLS demi autentikasi API key, sehingga
        // pemeriksaan tenant harus dilakukan di sini — tanpa ini, id collector
        // milik tenant lain bisa dibaca dan diubah.
        if (collector.tenantId != currentUser.current().tenantId) {
            throw NotFoundException("Collector $id tidak ditemukan")
        }
        return collector
    }

    private fun Collector.toView() = CollectorView(
        id = id,
        name = name,
        status = status,
        pollIntervalSeconds = pollIntervalSeconds,
        apiKeyHint = apiKeyHint,
        agentVersion = agentVersion,
        lastSeenAt = lastSeenAt,
        lastCycleSummary = lastCycleSummary,
        silent = isSilent(),
        assignedOltIds = collectorRepository.findAssignedOltIds(id),
    )
}
