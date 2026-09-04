package com.duluin.ftth.workorder.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EvidenceObjectRegistryJpaRepository : JpaRepository<EvidenceObjectRegistryJpaEntity, UUID> {
    fun findByRevisionId(revisionId: UUID): EvidenceObjectRegistryJpaEntity?
    fun findByTenantIdAndState(tenantId: UUID, state: com.duluin.ftth.workorder.domain.model.EvidenceRevisionState): List<EvidenceObjectRegistryJpaEntity>
}
