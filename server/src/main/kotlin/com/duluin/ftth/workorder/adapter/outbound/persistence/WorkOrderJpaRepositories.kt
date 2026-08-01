package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus
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

/** Beban WO terbuka per teknisi (dari roster tim datar). */
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
        "select a.technicianId as assignedTo, count(a) as total from WorkOrderAssigneeJpaEntity a " +
            "where a.workOrderId in (select w.id from WorkOrderJpaEntity w where w.status in :statuses) " +
            "group by a.technicianId",
    )
    fun countOpenGroupedByTechnician(statuses: Collection<WorkOrderStatus>): List<WorkOrderTechnicianCount>

    /** WO terbuka yang belum punya satu pun teknisi di roster (antrean dispatch). */
    @Query(
        "select count(w) from WorkOrderJpaEntity w where w.status in :statuses " +
            "and not exists (select 1 from WorkOrderAssigneeJpaEntity a where a.workOrderId = w.id)",
    )
    fun countOpenUnassigned(statuses: Collection<WorkOrderStatus>): Long

    fun countByApprovalStatus(approvalStatus: WorkOrderApprovalStatus): Long
}

interface WorkOrderAssigneeJpaRepository : JpaRepository<WorkOrderAssigneeJpaEntity, UUID> {
    fun findByWorkOrderId(workOrderId: UUID): List<WorkOrderAssigneeJpaEntity>

    fun findByWorkOrderIdIn(workOrderIds: Collection<UUID>): List<WorkOrderAssigneeJpaEntity>
}

interface WorkOrderEventJpaRepository : JpaRepository<WorkOrderEventJpaEntity, UUID> {
    fun findByWorkOrderIdOrderByAt(workOrderId: UUID): List<WorkOrderEventJpaEntity>
}
