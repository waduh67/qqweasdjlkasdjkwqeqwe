package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/** Jumlah work order per status — untuk ringkasan dashboard tanpa menarik barisnya. */
interface WorkOrderStatusCount {
    val status: WorkOrderStatus
    val total: Long
}

interface WorkOrderTypeCount {
    val type: WorkOrderType
    val total: Long
}

/** Beban WO terbuka per teknisi; `assignedTo` null = kelompok yang belum ditugaskan. */
interface WorkOrderTechnicianCount {
    val assignedTo: UUID?
    val total: Long
}

interface WorkOrderJpaRepository :
    JpaRepository<WorkOrderJpaEntity, UUID>,
    JpaSpecificationExecutor<WorkOrderJpaEntity> {

    @Query("select w.status as status, count(w) as total from WorkOrderJpaEntity w group by w.status")
    fun countGroupedByStatus(): List<WorkOrderStatusCount>

    @Query("select w.type as type, count(w) as total from WorkOrderJpaEntity w group by w.type")
    fun countGroupedByType(): List<WorkOrderTypeCount>

    @Query(
        "select w.assignedTo as assignedTo, count(w) as total from WorkOrderJpaEntity w " +
            "where w.status in :statuses group by w.assignedTo",
    )
    fun countOpenGroupedByTechnician(statuses: Collection<WorkOrderStatus>): List<WorkOrderTechnicianCount>
}

interface WorkOrderEventJpaRepository : JpaRepository<WorkOrderEventJpaEntity, UUID> {
    fun findByWorkOrderIdOrderByAt(workOrderId: UUID): List<WorkOrderEventJpaEntity>
}
