package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.monitoring.application.port.outbound.CollectorRepository
import com.duluin.ftth.monitoring.domain.model.Collector
import com.duluin.ftth.monitoring.domain.model.CollectorStatus
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CollectorPersistenceAdapter(
    private val jpa: CollectorJpaRepository,
) : CollectorRepository {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun save(collector: Collector): Collector {
        val entity = jpa.findById(collector.id).orElse(null)?.apply {
            name = collector.name
            status = collector.status
            pollIntervalSeconds = collector.pollIntervalSeconds
            agentVersion = collector.agentVersion
            lastSeenAt = collector.lastSeenAt
            lastCycleSummary = collector.lastCycleSummary
        } ?: CollectorJpaEntity(
            id = collector.id,
            tenantId = collector.tenantId,
            name = collector.name,
            apiKeyHash = collector.apiKeyHash,
            apiKeyHint = collector.apiKeyHint,
            status = collector.status,
            pollIntervalSeconds = collector.pollIntervalSeconds,
            agentVersion = collector.agentVersion,
            lastSeenAt = collector.lastSeenAt,
            lastCycleSummary = collector.lastCycleSummary,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Collector? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByApiKeyHash(apiKeyHash: String): Collector? =
        jpa.findByApiKeyHash(apiKeyHash)?.toDomain()

    override fun findAllByTenant(tenantId: UUID): List<Collector> =
        jpa.findByTenantIdOrderByName(tenantId).map { it.toDomain() }

    override fun findAllActive(): List<Collector> =
        jpa.findByStatus(CollectorStatus.ACTIVE).map { it.toDomain() }

    override fun existsByName(tenantId: UUID, name: String): Boolean =
        jpa.existsByTenantIdAndName(tenantId, name.trim())

    override fun deleteById(id: UUID) = jpa.deleteById(id)

    override fun findAssignedOltIds(collectorId: UUID): Set<UUID> {
        @Suppress("UNCHECKED_CAST")
        val rows = entityManager
            .createNativeQuery("SELECT olt_id FROM collector_olt WHERE collector_id = CAST(:id AS uuid)")
            .setParameter("id", collectorId.toString())
            .resultList as List<UUID>
        return rows.toSet()
    }

    /**
     * Ditulis ulang seluruhnya, bukan di-diff: penugasan OLT selalu datang sebagai
     * daftar utuh dari UI, dan jumlahnya puluhan — bukan sesuatu yang perlu
     * dioptimalkan dengan risiko salah sinkron.
     */
    override fun replaceAssignedOltIds(collectorId: UUID, oltIds: Set<UUID>) {
        entityManager
            .createNativeQuery("DELETE FROM collector_olt WHERE collector_id = CAST(:id AS uuid)")
            .setParameter("id", collectorId.toString())
            .executeUpdate()

        oltIds.forEach { oltId ->
            entityManager
                .createNativeQuery(
                    "INSERT INTO collector_olt (collector_id, olt_id) VALUES (CAST(:c AS uuid), CAST(:o AS uuid))",
                )
                .setParameter("c", collectorId.toString())
                .setParameter("o", oltId.toString())
                .executeUpdate()
        }
    }
}

private fun CollectorJpaEntity.toDomain(): Collector = Collector.rehydrate(
    id = id,
    tenantId = tenantId,
    name = name,
    apiKeyHash = apiKeyHash,
    apiKeyHint = apiKeyHint,
    status = status,
    pollIntervalSeconds = pollIntervalSeconds,
    agentVersion = agentVersion,
    lastSeenAt = lastSeenAt,
    lastCycleSummary = lastCycleSummary,
)
