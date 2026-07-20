package com.duluin.ftth.audit.adapter.outbound.persistence

import com.duluin.ftth.audit.application.port.outbound.AuditRepository
import com.duluin.ftth.audit.domain.model.AuditEntry
import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Adapter persistence audit. `entityId` di-embed ke dalam JSON [detail] di bawah
 * kunci khusus [ENTITY_ID_KEY] (bukan kolom tersendiri) dan dipisahkan kembali
 * saat baca, sehingga kontrak domain tetap punya entityId eksplisit.
 */
@Component
class AuditPersistenceAdapter(
    private val jpa: AuditLogJpaRepository,
    private val objectMapper: ObjectMapper,
) : AuditRepository {

    override fun save(entry: AuditEntry): AuditEntry {
        val detailWithEntity: Map<String, Any?> =
            if (entry.entityId != null) entry.detail + (ENTITY_ID_KEY to entry.entityId) else entry.detail

        val entity = AuditLogJpaEntity(
            id = entry.id,
            tenantId = entry.tenantId,
            actorId = entry.actorId,
            actorEmail = entry.actorEmail,
            action = entry.action,
            entityType = entry.entityType,
            detail = if (detailWithEntity.isEmpty()) null else objectMapper.writeValueAsString(detailWithEntity),
            occurredAt = entry.occurredAt,
        )
        return jpa.save(entity).toDomain(objectMapper)
    }

    override fun findAll(pageRequest: PageRequest): Page<AuditEntry> =
        jpa.findAll(pageRequest.toPageable()).map { it.toDomain(objectMapper) }.toDomainPage()

    companion object {
        const val ENTITY_ID_KEY = "@entityId"
    }
}

@Suppress("UNCHECKED_CAST")
private fun AuditLogJpaEntity.toDomain(objectMapper: ObjectMapper): AuditEntry {
    val fullMap: Map<String, Any?> = detail
        ?.let { objectMapper.readValue(it, Map::class.java) as Map<String, Any?> }
        ?: emptyMap()
    val entityId = fullMap[AuditPersistenceAdapter.ENTITY_ID_KEY] as String?
    val detailMap = fullMap - AuditPersistenceAdapter.ENTITY_ID_KEY

    return AuditEntry.rehydrate(
        id = id,
        tenantId = tenantId,
        actorId = actorId,
        actorEmail = actorEmail,
        action = action,
        entityType = entityType,
        entityId = entityId,
        detail = detailMap,
        occurredAt = occurredAt,
    )
}
