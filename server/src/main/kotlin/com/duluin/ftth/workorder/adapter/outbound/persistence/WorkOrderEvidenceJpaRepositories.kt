package com.duluin.ftth.workorder.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import jakarta.persistence.LockModeType
import java.util.UUID

interface WorkOrderEvidenceJpaRepository : JpaRepository<WorkOrderEvidenceJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select evidence from WorkOrderEvidenceJpaEntity evidence where evidence.id = :revisionId")
    fun findByRevisionId(@Param("revisionId") revisionId: UUID): WorkOrderEvidenceJpaEntity?

    fun findByWorkOrderIdOrderByCreatedAt(workOrderId: UUID): List<WorkOrderEvidenceJpaEntity>
}

interface WorkOrderSignatureJpaRepository : JpaRepository<WorkOrderSignatureJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select signature from WorkOrderSignatureJpaEntity signature where signature.id = :revisionId")
    fun findByRevisionId(@Param("revisionId") revisionId: UUID): WorkOrderSignatureJpaEntity?

    fun findByWorkOrderId(workOrderId: UUID): List<WorkOrderSignatureJpaEntity>
}
