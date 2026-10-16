package com.karuhun.ftth.network.adapter.outbound.persistence

import com.karuhun.ftth.common.tenant.TenantContext
import com.karuhun.ftth.network.application.port.outbound.PonPortRepository
import com.karuhun.ftth.network.domain.model.PonPort
import org.springframework.stereotype.Component
import jakarta.persistence.EntityManager
import java.util.UUID

@Component
class PonPortPersistenceAdapter(
    private val jpa: PonPortJpaRepository,
    private val entityManager: EntityManager,
) : PonPortRepository {

    override fun insertIfAbsent(ponPort: PonPort): Boolean = entityManager.createNativeQuery("""
        INSERT INTO pon_port(id,tenant_id,olt_id,label,description,status)
        VALUES (:id,:tenant,:olt,:label,:description,:status)
        ON CONFLICT (tenant_id,olt_id,label) DO NOTHING
    """).setParameter("id", ponPort.id).setParameter("tenant", TenantContext.tenantId())
        .setParameter("olt", ponPort.oltId).setParameter("label", ponPort.label)
        .setParameter("description", ponPort.description).setParameter("status", ponPort.status.name)
        .executeUpdate() == 1

    override fun save(ponPort: PonPort): PonPort {
        val entity = jpa.findById(ponPort.id).orElse(null)?.apply {
            label = ponPort.label
            description = ponPort.description
            status = ponPort.status
        } ?: PonPortJpaEntity(
            id = ponPort.id,
            oltId = ponPort.oltId,
            label = ponPort.label,
            description = ponPort.description,
            status = ponPort.status,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): PonPort? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAllByIds(ids: Set<UUID>): List<PonPort> = jpa.findAllById(ids).map { it.toDomain() }

    override fun findByOltId(oltId: UUID): List<PonPort> =
        jpa.findByOltIdOrderByLabel(oltId).map { it.toDomain() }

    override fun findByOltIdAndLabel(oltId: UUID, label: String): PonPort? =
        jpa.findByOltIdAndLabel(oltId, label)?.toDomain()

    override fun existsByOltIdAndLabel(oltId: UUID, label: String): Boolean =
        jpa.existsByOltIdAndLabel(oltId, label)

    override fun countByOltIds(oltIds: Set<UUID>): Map<UUID, Long> =
        if (oltIds.isEmpty()) emptyMap()
        else jpa.countGroupedByOlt(oltIds).associate { it.parentId to it.total }

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun PonPortJpaEntity.toDomain(): PonPort = PonPort.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    oltId = oltId,
    label = label,
    description = description,
    status = status,
)
