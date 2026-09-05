package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.workorder.application.port.outbound.EvidenceObjectRegistryRepository
import com.duluin.ftth.workorder.domain.model.EvidenceRevisionState
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class EvidenceObjectRegistryPersistenceAdapter(
    private val jpa: EvidenceObjectRegistryJpaRepository,
) : EvidenceObjectRegistryRepository {
    override fun registerPending(revisionId: UUID, objectKey: String, sha256: String?, size: Long, contentType: String, actorId: UUID, tenantId: UUID) {
        jpa.save(EvidenceObjectRegistryJpaEntity(UUID.randomUUID(), revisionId, objectKey, sha256, size, contentType, actorId, "RAW_EVIDENCE_24M", EvidenceRevisionState.PENDING, null))
    }

    @Transactional
    override fun markCommitted(revisionId: UUID, etag: String?) {
        jpa.findByRevisionId(revisionId)?.let { current ->
            requireActive(current)
            current.state = EvidenceRevisionState.COMMITTED
            current.etag = etag
            jpa.save(current)
        }
    }

    @Transactional
    override fun transition(revisionId: UUID, state: EvidenceRevisionState, reason: String?) {
        jpa.findByRevisionId(revisionId)?.let { current ->
            requireActive(current)
            current.state = state
            current.etag = reason
            jpa.save(current)
        }
    }

    private fun requireActive(current: EvidenceObjectRegistryJpaEntity) {
        if (current.purgeState != "ACTIVE") {
            throw ConflictException("Registry evidence sedang diproses retensi: ${current.purgeState}")
        }
    }
}
