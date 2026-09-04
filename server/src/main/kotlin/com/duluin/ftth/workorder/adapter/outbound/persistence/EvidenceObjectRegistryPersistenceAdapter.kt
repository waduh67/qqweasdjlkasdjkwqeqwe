package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.workorder.application.port.outbound.EvidenceObjectRegistryRepository
import com.duluin.ftth.workorder.domain.model.EvidenceRevisionState
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class EvidenceObjectRegistryPersistenceAdapter(
    private val jpa: EvidenceObjectRegistryJpaRepository,
) : EvidenceObjectRegistryRepository {
    override fun registerPending(revisionId: UUID, objectKey: String, sha256: String?, size: Long, contentType: String, actorId: UUID, tenantId: UUID) {
        jpa.save(EvidenceObjectRegistryJpaEntity(UUID.randomUUID(), revisionId, objectKey, sha256, size, contentType, actorId, "RAW_EVIDENCE_24M", EvidenceRevisionState.PENDING, null))
    }

    override fun markCommitted(revisionId: UUID, etag: String?) {
        jpa.findByRevisionId(revisionId)?.apply { state = EvidenceRevisionState.COMMITTED; this.etag = etag }?.let(jpa::save)
    }

    override fun transition(revisionId: UUID, state: EvidenceRevisionState, reason: String?) {
        jpa.findByRevisionId(revisionId)?.apply { this.state = state; this.etag = reason }?.let(jpa::save)
    }
}
