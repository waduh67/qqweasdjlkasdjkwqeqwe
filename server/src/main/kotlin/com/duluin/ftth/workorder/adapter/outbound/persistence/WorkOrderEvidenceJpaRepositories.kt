package com.duluin.ftth.workorder.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WorkOrderEvidenceJpaRepository : JpaRepository<WorkOrderEvidenceJpaEntity, UUID> {
    fun findByWorkOrderIdOrderByCreatedAt(workOrderId: UUID): List<WorkOrderEvidenceJpaEntity>
}

interface WorkOrderSignatureJpaRepository : JpaRepository<WorkOrderSignatureJpaEntity, UUID> {
    fun findByWorkOrderId(workOrderId: UUID): List<WorkOrderSignatureJpaEntity>
}
