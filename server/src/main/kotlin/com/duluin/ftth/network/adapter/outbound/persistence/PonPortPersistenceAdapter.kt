package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.PonPortRepository
import com.duluin.ftth.network.domain.model.PonPort
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class PonPortPersistenceAdapter(
    private val jpa: PonPortJpaRepository,
) : PonPortRepository {

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
