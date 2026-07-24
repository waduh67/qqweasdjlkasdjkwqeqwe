package com.duluin.ftth.workorder.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface WorkOrderJpaRepository :
    JpaRepository<WorkOrderJpaEntity, UUID>,
    JpaSpecificationExecutor<WorkOrderJpaEntity>

interface WorkOrderEventJpaRepository : JpaRepository<WorkOrderEventJpaEntity, UUID> {
    fun findByWorkOrderIdOrderByAt(workOrderId: UUID): List<WorkOrderEventJpaEntity>
}
