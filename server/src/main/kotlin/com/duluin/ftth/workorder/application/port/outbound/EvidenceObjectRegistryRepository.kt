package com.duluin.ftth.workorder.application.port.outbound

import com.duluin.ftth.workorder.domain.model.EvidenceRevisionState
import java.util.UUID

interface EvidenceObjectRegistryRepository {
    fun registerPending(revisionId: UUID, objectKey: String, sha256: String?, size: Long, contentType: String, actorId: UUID, tenantId: UUID)
    fun markCommitted(revisionId: UUID, etag: String?)
    fun transition(revisionId: UUID, state: EvidenceRevisionState, reason: String? = null)
}
