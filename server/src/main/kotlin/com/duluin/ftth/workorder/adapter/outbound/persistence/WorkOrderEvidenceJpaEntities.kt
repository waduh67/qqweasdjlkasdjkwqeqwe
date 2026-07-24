package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.workorder.domain.model.EvidenceKind
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Metadata satu bukti foto; byte-nya di object storage pada [storageKey]. */
@Entity
@Table(name = "wo_evidence")
class WorkOrderEvidenceJpaEntity(
    id: UUID,

    @Column(name = "work_order_id", nullable = false, updatable = false)
    var workOrderId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    var kind: EvidenceKind,

    @Column(length = 300)
    var caption: String?,

    @Column(name = "storage_key", nullable = false, length = 300, updatable = false)
    var storageKey: String,

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    var contentType: String,

    @Column(name = "size_bytes", nullable = false, updatable = false)
    var sizeBytes: Long,

    @Column(updatable = false)
    var latitude: Double?,

    @Column(updatable = false)
    var longitude: Double?,

    @Column(name = "captured_at", updatable = false)
    var capturedAt: Instant?,

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    var uploadedBy: UUID,
) : TenantAwareJpaEntity(id)

/** Metadata tanda tangan pelanggan; byte-nya di object storage pada [storageKey]. */
@Entity
@Table(name = "wo_signature")
class WorkOrderSignatureJpaEntity(
    id: UUID,

    @Column(name = "work_order_id", nullable = false, updatable = false)
    var workOrderId: UUID,

    @Column(name = "signer_name", nullable = false, length = 200)
    var signerName: String,

    @Column(name = "storage_key", nullable = false, length = 300, updatable = false)
    var storageKey: String,

    @Column(name = "content_type", nullable = false, length = 100, updatable = false)
    var contentType: String,

    @Column(name = "size_bytes", nullable = false, updatable = false)
    var sizeBytes: Long,

    @Column(name = "signed_by", nullable = false, updatable = false)
    var signedBy: UUID,

    @Column(name = "signed_at", nullable = false, updatable = false)
    var signedAt: Instant,
) : TenantAwareJpaEntity(id)
