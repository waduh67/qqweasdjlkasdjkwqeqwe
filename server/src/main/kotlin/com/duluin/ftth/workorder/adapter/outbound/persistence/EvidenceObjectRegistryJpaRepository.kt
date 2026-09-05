package com.duluin.ftth.workorder.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import java.util.UUID
import java.time.Instant

interface EvidenceObjectRegistryJpaRepository : JpaRepository<EvidenceObjectRegistryJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByRevisionId(revisionId: UUID): EvidenceObjectRegistryJpaEntity?
    fun findByTenantIdAndState(tenantId: UUID, state: com.duluin.ftth.workorder.domain.model.EvidenceRevisionState): List<EvidenceObjectRegistryJpaEntity>
    fun findByTenantIdAndPurgeState(tenantId: UUID, purgeState: String): List<EvidenceObjectRegistryJpaEntity>
}
