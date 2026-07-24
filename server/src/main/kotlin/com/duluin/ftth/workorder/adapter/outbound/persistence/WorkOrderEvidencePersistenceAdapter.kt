package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderEvidenceRepository
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderSignatureRepository
import com.duluin.ftth.workorder.domain.model.WorkOrderEvidence
import com.duluin.ftth.workorder.domain.model.WorkOrderSignature
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class WorkOrderEvidencePersistenceAdapter(
    private val jpa: WorkOrderEvidenceJpaRepository,
) : WorkOrderEvidenceRepository {

    override fun save(evidence: WorkOrderEvidence): WorkOrderEvidence =
        jpa.save(
            WorkOrderEvidenceJpaEntity(
                id = evidence.id,
                workOrderId = evidence.workOrderId,
                kind = evidence.kind,
                caption = evidence.caption,
                storageKey = evidence.storageKey,
                contentType = evidence.contentType,
                sizeBytes = evidence.sizeBytes,
                latitude = evidence.latitude,
                longitude = evidence.longitude,
                capturedAt = evidence.capturedAt,
                uploadedBy = evidence.uploadedBy,
            ),
        ).toDomain()

    override fun findById(id: UUID): WorkOrderEvidence? = jpa.findById(id).orElse(null)?.toDomain()

    override fun listByWorkOrder(workOrderId: UUID): List<WorkOrderEvidence> =
        jpa.findByWorkOrderIdOrderByCreatedAt(workOrderId).map { it.toDomain() }

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

@Component
class WorkOrderSignaturePersistenceAdapter(
    private val jpa: WorkOrderSignatureJpaRepository,
) : WorkOrderSignatureRepository {

    override fun save(signature: WorkOrderSignature): WorkOrderSignature =
        jpa.save(
            WorkOrderSignatureJpaEntity(
                id = signature.id,
                workOrderId = signature.workOrderId,
                signerName = signature.signerName,
                storageKey = signature.storageKey,
                contentType = signature.contentType,
                sizeBytes = signature.sizeBytes,
                signedBy = signature.signedBy,
                signedAt = signature.signedAt,
            ),
        ).toDomain()

    override fun findByWorkOrder(workOrderId: UUID): WorkOrderSignature? =
        jpa.findByWorkOrderId(workOrderId)?.toDomain()

    // Flush langsung: saat tanda tangan diganti (hapus lama → simpan baru dalam satu
    // transaksi), Hibernate secara default mengurutkan INSERT sebelum DELETE dan
    // menabrak indeks unik (tenant_id, work_order_id). Paksa DELETE turun lebih dulu.
    override fun deleteById(id: UUID) {
        jpa.deleteById(id)
        jpa.flush()
    }
}

private fun WorkOrderEvidenceJpaEntity.toDomain(): WorkOrderEvidence = WorkOrderEvidence.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    workOrderId = workOrderId,
    kind = kind,
    caption = caption,
    storageKey = storageKey,
    contentType = contentType,
    sizeBytes = sizeBytes,
    latitude = latitude,
    longitude = longitude,
    capturedAt = capturedAt,
    uploadedBy = uploadedBy,
    createdAt = createdAt,
)

private fun WorkOrderSignatureJpaEntity.toDomain(): WorkOrderSignature = WorkOrderSignature.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    workOrderId = workOrderId,
    signerName = signerName,
    storageKey = storageKey,
    contentType = contentType,
    sizeBytes = sizeBytes,
    signedBy = signedBy,
    signedAt = signedAt,
    createdAt = createdAt,
)
