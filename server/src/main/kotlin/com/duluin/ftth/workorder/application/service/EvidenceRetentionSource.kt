package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.workorder.adapter.outbound.persistence.EvidenceObjectRegistryJpaEntity
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderEvidenceJpaEntity
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderEvidenceJpaRepository
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderSignatureJpaEntity
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderSignatureJpaRepository
import com.duluin.ftth.workorder.domain.model.EvidenceRevisionState
import java.util.UUID

internal data class EvidenceRetentionSource(
    val kind: String,
    val revisionId: UUID,
    val workOrderId: UUID,
    val objectKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String?,
    val state: EvidenceRevisionState,
    val purgeState: String,
    val claimId: UUID?,
    val rowVersion: Long,
    val tombstone: () -> Unit,
    val reconcile: () -> Unit = {},
) {
    fun matches(candidate: EvidenceObjectRegistryJpaEntity, tenantId: UUID, expectedPurgeState: String): Boolean =
        revisionId == candidate.revisionId && objectKey == candidate.objectKey &&
            contentType == candidate.expectedContentType && sizeBytes == candidate.expectedSizeBytes &&
            sha256 == candidate.expectedSha256 && state == candidate.state &&
            purgeState == expectedPurgeState &&
            objectKey == "$tenantId/wo/$workOrderId/${kind.lowercase()}/$revisionId"

    fun sameClaim(other: EvidenceRetentionSource): Boolean =
        kind == other.kind && revisionId == other.revisionId && workOrderId == other.workOrderId &&
            objectKey == other.objectKey && contentType == other.contentType &&
            sizeBytes == other.sizeBytes && sha256 == other.sha256 && state == other.state &&
            purgeState == other.purgeState && claimId == other.claimId

    companion object {
        fun resolve(
            revisionId: UUID,
            evidence: WorkOrderEvidenceJpaRepository,
            signatures: WorkOrderSignatureJpaRepository,
        ): EvidenceRetentionSource? {
            val photo = evidence.findByRevisionId(revisionId)
            val signature = signatures.findByRevisionId(revisionId)
            if ((photo != null) == (signature != null)) return null
            return photo?.let { fromEvidence(it, evidence) } ?: signature?.let { fromSignature(it, signatures) }
        }

        private fun fromEvidence(entity: WorkOrderEvidenceJpaEntity, repository: WorkOrderEvidenceJpaRepository) = EvidenceRetentionSource(
            "EVIDENCE", entity.id, entity.workOrderId, entity.storageKey, entity.expectedContentType,
            entity.expectedSizeBytes, entity.sha256, entity.revisionState, entity.purgeState, entity.purgeClaimId, entity.rowVersion,
            tombstone = { entity.revisionState = EvidenceRevisionState.TOMBSTONED; entity.purgeState = "DELETED"; entity.purgeClaimId = null; entity.purgeClaimedAt = null; repository.save(entity) },
        )
            .copy(reconcile = { entity.purgeState = "RECONCILE"; repository.save(entity) })

        private fun fromSignature(entity: WorkOrderSignatureJpaEntity, repository: WorkOrderSignatureJpaRepository) = EvidenceRetentionSource(
            "SIGNATURE", entity.id, entity.workOrderId, entity.storageKey, entity.expectedContentType,
            entity.expectedSizeBytes, entity.sha256, entity.revisionState, entity.purgeState, entity.purgeClaimId, entity.rowVersion,
            tombstone = { entity.revisionState = EvidenceRevisionState.TOMBSTONED; entity.purgeState = "DELETED"; entity.purgeClaimId = null; entity.purgeClaimedAt = null; repository.save(entity) },
        )
            .copy(reconcile = { entity.purgeState = "RECONCILE"; repository.save(entity) })
    }
}
