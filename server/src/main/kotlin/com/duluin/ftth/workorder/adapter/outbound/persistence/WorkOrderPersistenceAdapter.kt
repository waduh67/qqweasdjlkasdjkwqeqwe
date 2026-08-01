package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderEvent
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class WorkOrderPersistenceAdapter(
    private val jpa: WorkOrderJpaRepository,
    private val eventJpa: WorkOrderEventJpaRepository,
    private val assigneeJpa: WorkOrderAssigneeJpaRepository,
) : WorkOrderRepository {

    override fun save(workOrder: WorkOrder): WorkOrder {
        val entity = jpa.findById(workOrder.id).orElse(null)?.apply {
            title = workOrder.title
            description = workOrder.description
            priority = workOrder.priority
            status = workOrder.status
            customerId = workOrder.customerId
            incidentId = workOrder.incidentId
            areaId = workOrder.areaId
            assignedAt = workOrder.assignedAt
            scheduledAt = workOrder.scheduledAt
            startedAt = workOrder.startedAt
            completedAt = workOrder.completedAt
            resolutionNote = workOrder.resolutionNote
            cancelReason = workOrder.cancelReason
            rxBeforeDbm = workOrder.rxBeforeDbm
            rxAfterDbm = workOrder.rxAfterDbm
            approvalStatus = workOrder.approvalStatus
            approvedBy = workOrder.approvedBy
            approvedAt = workOrder.approvedAt
            approvalNote = workOrder.approvalNote
        } ?: WorkOrderJpaEntity(
            id = workOrder.id,
            code = workOrder.code,
            type = workOrder.type,
            title = workOrder.title,
            description = workOrder.description,
            priority = workOrder.priority,
            status = workOrder.status,
            customerId = workOrder.customerId,
            subscriptionId = workOrder.subscriptionId,
            incidentId = workOrder.incidentId,
            areaId = workOrder.areaId,
            assignedAt = workOrder.assignedAt,
            scheduledAt = workOrder.scheduledAt,
            startedAt = workOrder.startedAt,
            completedAt = workOrder.completedAt,
            resolutionNote = workOrder.resolutionNote,
            cancelReason = workOrder.cancelReason,
            rxBeforeDbm = workOrder.rxBeforeDbm,
            rxAfterDbm = workOrder.rxAfterDbm,
            approvalStatus = workOrder.approvalStatus,
            approvedBy = workOrder.approvedBy,
            approvedAt = workOrder.approvedAt,
            approvalNote = workOrder.approvalNote,
            createdBy = workOrder.createdBy,
        )
        val saved = jpa.save(entity)
        reconcileAssignees(workOrder)

        // Simpan event timeline yang tertunda, lalu kosongkan agar tidak tersimpan ganda.
        workOrder.pendingEvents().forEach { ev ->
            eventJpa.save(
                WorkOrderEventJpaEntity(
                    id = ev.id,
                    workOrderId = ev.workOrderId,
                    type = ev.type,
                    message = ev.message,
                    actorId = ev.actorId,
                    at = ev.at,
                ),
            )
        }
        workOrder.clearPending()
        return saved.toDomain(workOrder.assignees)
    }

    /** Selaraskan baris roster ke [WorkOrder.assignees]: buang yang lepas, tambah yang baru. */
    private fun reconcileAssignees(workOrder: WorkOrder) {
        val existing = assigneeJpa.findByWorkOrderId(workOrder.id).associateBy { it.technicianId }
        val desired = workOrder.assignees
        existing.filterKeys { it !in desired }.values.forEach { assigneeJpa.delete(it) }
        (desired - existing.keys).forEach { technicianId ->
            assigneeJpa.save(
                WorkOrderAssigneeJpaEntity(
                    id = UuidV7.generate(),
                    workOrderId = workOrder.id,
                    technicianId = technicianId,
                    assignedAt = workOrder.assignedAt ?: Instant.now(),
                ),
            )
        }
    }

    override fun findById(id: UUID): WorkOrder? =
        jpa.findById(id).orElse(null)?.let { it.toDomain(rosterOf(it.id)) }

    override fun search(
        query: String?,
        type: WorkOrderType?,
        status: WorkOrderStatus?,
        assignedTo: UUID?,
        approvalStatus: WorkOrderApprovalStatus?,
        customerId: UUID?,
        pageRequest: PageRequest,
    ): Page<WorkOrder> {
        val spec = matchesText(query)
            .and(hasType(type))
            .and(hasStatus(status))
            .and(assignedToIs(assignedTo))
            .and(hasApprovalStatus(approvalStatus))
            .and(hasCustomer(customerId))
        val page = jpa.findAll(spec, pageRequest.toPageable())
        val rosters = rostersOf(page.content.map { it.id })
        return page.toDomainPage().map { it.toDomain(rosters[it.id].orEmpty()) }
    }

    override fun timelineOf(workOrderId: UUID): List<WorkOrderEvent> =
        eventJpa.findByWorkOrderIdOrderByAt(workOrderId).map { it.toDomain() }

    override fun countByStatus(): Map<WorkOrderStatus, Long> =
        jpa.countGroupedByStatus().associate { it.status to it.total }

    override fun countByType(): Map<WorkOrderType, Long> =
        jpa.countGroupedByType().associate { it.type to it.total }

    override fun countOpenByTechnician(): Map<UUID?, Long> {
        val openStatuses = WorkOrderStatus.entries.filter { it.open }
        val byTechnician: Map<UUID?, Long> =
            jpa.countOpenGroupedByTechnician(openStatuses).associate { it.assignedTo to it.total }
        val unassigned = jpa.countOpenUnassigned(openStatuses)
        // Kunci null = antrean WO terbuka tanpa satu pun teknisi (dipakai dashboard "belum ditugaskan").
        return if (unassigned > 0) byTechnician + (null to unassigned) else byTechnician
    }

    override fun countPendingApproval(): Long = jpa.countByApprovalStatus(WorkOrderApprovalStatus.PENDING)

    override fun existsOpenPreventiveForCustomer(customerId: UUID): Boolean {
        val openStatuses = WorkOrderStatus.entries.filter { it.open }
        val spec = Specification<WorkOrderJpaEntity> { root, _, cb ->
            cb.and(
                cb.equal(root.get<UUID>("customerId"), customerId),
                cb.equal(root.get<WorkOrderType>("type"), WorkOrderType.PREVENTIVE),
                root.get<WorkOrderStatus>("status").`in`(openStatuses),
            )
        }
        return jpa.count(spec) > 0
    }

    override fun findOpenByType(type: WorkOrderType): List<WorkOrder> {
        val openStatuses = WorkOrderStatus.entries.filter { it.open }
        val spec = Specification<WorkOrderJpaEntity> { root, _, cb ->
            cb.and(
                cb.equal(root.get<WorkOrderType>("type"), type),
                root.get<WorkOrderStatus>("status").`in`(openStatuses),
            )
        }
        val entities = jpa.findAll(spec)
        val rosters = rostersOf(entities.map { it.id })
        return entities.map { it.toDomain(rosters[it.id].orEmpty()) }
    }

    override fun deleteById(id: UUID) = jpa.deleteById(id)

    /** Roster teknisi satu WO. */
    private fun rosterOf(workOrderId: UUID): Set<UUID> =
        assigneeJpa.findByWorkOrderId(workOrderId).mapTo(mutableSetOf()) { it.technicianId }

    /** Roster teknisi banyak WO sekaligus (hindari N+1 di daftar/halaman). */
    private fun rostersOf(workOrderIds: List<UUID>): Map<UUID, Set<UUID>> {
        if (workOrderIds.isEmpty()) return emptyMap()
        return assigneeJpa.findByWorkOrderIdIn(workOrderIds)
            .groupBy({ it.workOrderId }, { it.technicianId })
            .mapValues { (_, ids) -> ids.toSet() }
    }

    /** Cari lewat kode atau judul; kosong = cocokkan semua. */
    private fun matchesText(query: String?) = Specification<WorkOrderJpaEntity> { root, _, cb ->
        val needle = query?.trim()?.lowercase().orEmpty()
        if (needle.isEmpty()) {
            cb.conjunction()
        } else {
            val pattern = "%$needle%"
            cb.or(
                cb.like(cb.lower(root.get("code")), pattern),
                cb.like(cb.lower(root.get("title")), pattern),
            )
        }
    }

    private fun hasType(type: WorkOrderType?) = Specification<WorkOrderJpaEntity> { root, _, cb ->
        if (type == null) cb.conjunction() else cb.equal(root.get<WorkOrderType>("type"), type)
    }

    private fun hasStatus(status: WorkOrderStatus?) = Specification<WorkOrderJpaEntity> { root, _, cb ->
        if (status == null) cb.conjunction() else cb.equal(root.get<WorkOrderStatus>("status"), status)
    }

    // Filter "WO teknisi ini" = ada baris roster untuk teknisi tsb (tim datar, keanggotaan).
    private fun assignedToIs(assignedTo: UUID?) = Specification<WorkOrderJpaEntity> { root, query, cb ->
        if (assignedTo == null || query == null) {
            cb.conjunction()
        } else {
            val sub = query.subquery(UUID::class.java)
            val a = sub.from(WorkOrderAssigneeJpaEntity::class.java)
            sub.select(a.get("workOrderId"))
            sub.where(
                cb.equal(a.get<UUID>("workOrderId"), root.get<UUID>("id")),
                cb.equal(a.get<UUID>("technicianId"), assignedTo),
            )
            cb.exists(sub)
        }
    }

    private fun hasCustomer(customerId: UUID?) = Specification<WorkOrderJpaEntity> { root, _, cb ->
        if (customerId == null) cb.conjunction() else cb.equal(root.get<UUID>("customerId"), customerId)
    }

    private fun hasApprovalStatus(approvalStatus: WorkOrderApprovalStatus?) = Specification<WorkOrderJpaEntity> { root, _, cb ->
        if (approvalStatus == null) cb.conjunction() else cb.equal(root.get<WorkOrderApprovalStatus>("approvalStatus"), approvalStatus)
    }
}

private fun WorkOrderJpaEntity.toDomain(assignees: Set<UUID>): WorkOrder = WorkOrder.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    code = code,
    type = type,
    subscriptionId = subscriptionId,
    title = title,
    description = description,
    priority = priority,
    customerId = customerId,
    incidentId = incidentId,
    areaId = areaId,
    status = status,
    assignees = assignees,
    assignedAt = assignedAt,
    scheduledAt = scheduledAt,
    startedAt = startedAt,
    completedAt = completedAt,
    resolutionNote = resolutionNote,
    cancelReason = cancelReason,
    rxBeforeDbm = rxBeforeDbm,
    rxAfterDbm = rxAfterDbm,
    approvalStatus = approvalStatus,
    approvedBy = approvedBy,
    approvedAt = approvedAt,
    approvalNote = approvalNote,
    createdBy = createdBy,
    createdAt = createdAt,
)

private fun WorkOrderEventJpaEntity.toDomain(): WorkOrderEvent = WorkOrderEvent.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    workOrderId = workOrderId,
    type = type,
    message = message,
    actorId = actorId,
    at = at,
)
