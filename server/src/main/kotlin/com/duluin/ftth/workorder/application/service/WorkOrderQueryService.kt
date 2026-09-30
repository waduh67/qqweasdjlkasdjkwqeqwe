package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.common.security.areaScope
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.workorder.application.port.inbound.*
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class WorkOrderQueryService(private val repository: WorkOrderRepository, private val iamApi: IamApi,
    private val customerApi: CustomerApi, private val currentUser: CurrentUserProvider,
    private val views: WorkOrderViewMapper) : WorkOrderQuery {

    override fun search(filter: WorkOrderFilter, page: PageRequest): Page<WorkOrderView> {
        requireActiveActor()
        val result = repository.search(query = filter.query?.trim()?.takeIf { it.isNotEmpty() }, type = filter.type,
            status = filter.status, assignedTo = filter.assignedTo, approvalStatus = filter.approvalStatus,
            customerId = filter.customerId, pageRequest = page, areaIds = currentUser.current().areaScope())
        val userIds = result.content.flatMap { it.assignees + listOfNotNull(it.approvedBy) }.toSet()
        val userNames = iamApi.usersByIds(userIds).associate { it.id to it.name }
        val customers = customerApi.findCustomersByIds(result.content.mapNotNullTo(HashSet()) { it.customerId }).associateBy { it.id }
        return result.map { views.view(it, WorkOrderViewContext(customers[it.customerId], userNames, userNames[it.approvedBy])) }
    }

    override fun searchMine(status: WorkOrderStatus?, page: PageRequest): Page<WorkOrderView> =
        search(WorkOrderFilter(query = null, type = null, status = status, assignedTo = currentUser.current().userId, approvalStatus = null), page)

    override fun get(id: java.util.UUID): WorkOrderDetail {
        val workOrder = repository.findById(id) ?: throw NotFoundException("Work order $id tidak ditemukan")
        requireReadAccess(workOrder)
        val timeline = repository.timelineOf(id).map { WorkOrderEventView(type = it.type.name, message = it.message, at = it.at) }
        return WorkOrderDetail(views.single(workOrder), timeline)
    }

    override fun dashboard(): WorkOrderDashboardView {
        requireActiveActor()
        val areaIds = currentUser.current().areaScope()
        val byStatus = repository.countByStatus(areaIds)
        val byType = repository.countByType(areaIds)
        val openByTechnician = repository.countOpenByTechnician(areaIds)
        val technicianNames = iamApi.usersByIds(openByTechnician.keys.filterNotNullTo(HashSet())).associate { it.id to it.name }
        val workloads = openByTechnician.mapNotNull { (technicianId, count) ->
            technicianId?.let { TechnicianWorkloadView(it, technicianNames[it], count) }
        }.sortedByDescending { it.openCount }
        return WorkOrderDashboardView(total = byStatus.values.sum(), open = WorkOrderStatus.entries.filter { it.open }.sumOf { byStatus[it] ?: 0L },
            unassignedOpen = openByTechnician[null] ?: 0L, pendingApproval = repository.countPendingApproval(areaIds),
            byStatus = WorkOrderStatus.entries.associate { it.name to (byStatus[it] ?: 0L) },
            byType = com.duluin.ftth.workorder.domain.model.WorkOrderType.entries.associate { it.name to (byType[it] ?: 0L) }, workloads = workloads)
    }

    private fun requireReadAccess(workOrder: WorkOrder) {
        requireActiveActor()
        val scope = currentUser.current().areaScope()
        if (scope != null && (workOrder.areaId == null || workOrder.areaId !in scope)) throw AccessDeniedException("Work order di luar area Anda")
        val actor = currentUser.current()
        if (actor.hasPermission("workorder.order.view")) return
        if (actor.hasPermission("workorder.order.field") && iamApi.findUser(actor.userId)?.technician == true && workOrder.isAssignedTo(actor.userId)) return
        throw NotFoundException("Work order ${workOrder.id} tidak ditemukan")
    }

    private fun requireActiveActor() {
        if (iamApi.findUser(currentUser.current().userId)?.active != true) throw AccessDeniedException("Akun tidak aktif")
    }
}
