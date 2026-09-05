package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.workorder.domain.model.EvidenceRevisionState
import jakarta.persistence.Column
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "evidence_object_registry")
class EvidenceObjectRegistryJpaEntity(
    id: UUID,
    @Column(name = "revision_id", nullable = false, updatable = false) var revisionId: UUID,
    @Column(name = "object_key", nullable = false, length = 300, updatable = false) var objectKey: String,
    @Column(name = "expected_sha256", length = 64, updatable = false) var expectedSha256: String?,
    @Column(name = "expected_size_bytes", nullable = false, updatable = false) var expectedSizeBytes: Long,
    @Column(name = "expected_content_type", nullable = false, length = 100, updatable = false) var expectedContentType: String,
    @Column(name = "actor_id", nullable = false, updatable = false) var actorId: UUID,
    @Column(name = "retention_class", nullable = false, length = 40, updatable = false) var retentionClass: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) var state: EvidenceRevisionState,
    @Column(length = 200) var etag: String?,
    @Column(name = "purge_state", nullable = false, length = 20) var purgeState: String = "ACTIVE",
    @Column(name = "purge_claim_id") var purgeClaimId: UUID? = null,
    @Column(name = "purge_claimed_at") var purgeClaimedAt: Instant? = null,
    @jakarta.persistence.Version @Column(name = "row_version", nullable = false) var rowVersion: Long = 0,
) : TenantAwareJpaEntity(id)
