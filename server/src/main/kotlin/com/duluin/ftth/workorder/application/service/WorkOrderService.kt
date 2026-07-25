package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.workorder.application.port.inbound.ManageWorkOrderUseCase
import com.duluin.ftth.workorder.application.port.inbound.RecordOpticalCommand
import com.duluin.ftth.workorder.application.port.inbound.SaveWorkOrderCommand
import com.duluin.ftth.workorder.application.port.inbound.TechnicianWorkloadView
import com.duluin.ftth.workorder.application.port.inbound.UpdateWorkOrderCommand
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderDashboardView
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderDetail
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderEventView
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderFilter
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderQuery
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderView
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderEvent
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Orkestrasi work order. Nama pelanggan & teknisi bukan milik agregat ini —
 * diresolusi lewat kontrak module asal (customer, iam) saat menyusun view,
 * per-baris di detail dan sekali-batch di daftar agar tidak N+1.
 */
@Service
@Transactional(readOnly = true)
class WorkOrderService(
    private val repository: WorkOrderRepository,
    private val iamApi: IamApi,
    private val customerApi: CustomerApi,
    private val currentUser: CurrentUserProvider,
) : ManageWorkOrderUseCase, WorkOrderQuery {

    @Transactional
    override fun create(command: SaveWorkOrderCommand): WorkOrderView {
        requireCustomerExists(command.customerId)
        command.assignedTo?.let { requireActiveTechnician(it) }
        val actor = currentUser.current()
        val workOrder = WorkOrder.open(
            tenantId = actor.tenantId,
            type = command.type,
            title = command.title,
            description = command.description,
            priority = command.priority,
            customerId = command.customerId,
            incidentId = command.incidentId,
            areaId = command.areaId,
            scheduledAt = command.scheduledAt,
            assignedTo = command.assignedTo,
            createdBy = actor.userId,
        )
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun update(id: UUID, command: UpdateWorkOrderCommand): WorkOrderView {
        requireCustomerExists(command.customerId)
        val workOrder = require(id)
        workOrder.updateDetails(
            newTitle = command.title,
            newDescription = command.description,
            newPriority = command.priority,
            newCustomerId = command.customerId,
            newIncidentId = command.incidentId,
            newAreaId = command.areaId,
            newScheduledAt = command.scheduledAt,
            at = Instant.now(),
            actorId = currentUser.current().userId,
        )
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun assign(id: UUID, technicianId: UUID): WorkOrderView {
        requireActiveTechnician(technicianId)
        val workOrder = require(id)
        workOrder.assign(technicianId, Instant.now(), currentUser.current().userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun start(id: UUID): WorkOrderView {
        val workOrder = require(id)
        workOrder.start(Instant.now(), currentUser.current().userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun complete(id: UUID, resolutionNote: String?): WorkOrderView {
        val workOrder = require(id)
        workOrder.complete(resolutionNote, Instant.now(), currentUser.current().userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun cancel(id: UUID, reason: String?): WorkOrderView {
        val workOrder = require(id)
        workOrder.cancel(reason, Instant.now(), currentUser.current().userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun recordOptical(id: UUID, command: RecordOpticalCommand): WorkOrderView {
        val workOrder = require(id)
        workOrder.recordOptical(command.rxBeforeDbm, command.rxAfterDbm, Instant.now(), currentUser.current().userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun approve(id: UUID, note: String?): WorkOrderView {
        val workOrder = require(id)
        workOrder.approve(note, Instant.now(), currentUser.current().userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun reject(id: UUID, reason: String): WorkOrderView {
        val workOrder = require(id)
        workOrder.reject(reason, Instant.now(), currentUser.current().userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun delete(id: UUID) {
        val workOrder = require(id)
        // Sekali ditugaskan, work order punya jejak (assignment/timeline) yang tak boleh
        // hilang diam-diam; yang belum tersentuh boleh dihapus, sisanya dibatalkan saja.
        if (workOrder.status != WorkOrderStatus.DRAFT) {
            throw ConflictException("Hanya work order berstatus DRAFT yang bisa dihapus; batalkan sisanya")
        }
        repository.deleteById(id)
    }

    override fun search(filter: WorkOrderFilter, page: PageRequest): Page<WorkOrderView> {
        val result = repository.search(
            query = filter.query?.trim()?.takeIf { it.isNotEmpty() },
            type = filter.type,
            status = filter.status,
            assignedTo = filter.assignedTo,
            approvalStatus = filter.approvalStatus,
            pageRequest = page,
        )
        // Teknisi & penyetuju sama-sama pengguna iam → kumpulkan idnya lalu resolusi sekali-batch.
        val userIds = HashSet<UUID>()
        result.content.forEach { wo ->
            wo.assignedTo?.let { userIds += it }
            wo.approvedBy?.let { userIds += it }
        }
        val userNames = iamApi.usersByIds(userIds).associate { it.id to it.name }
        val customerNames = customerApi.findCustomersByIds(result.content.mapNotNullTo(HashSet()) { it.customerId })
            .associate { it.id to it.name }
        return result.map { it.toView(customerNames[it.customerId], userNames[it.assignedTo], userNames[it.approvedBy]) }
    }

    override fun get(id: UUID): WorkOrderDetail {
        val workOrder = require(id)
        val timeline = repository.timelineOf(id).map { it.toView() }
        return WorkOrderDetail(workOrder.toView(), timeline)
    }

    override fun dashboard(): WorkOrderDashboardView {
        val byStatus = repository.countByStatus()
        val byType = repository.countByType()
        val openByTechnician = repository.countOpenByTechnician()

        // Nama teknisi diresolusi sekali-batch lewat iam (hindari N+1); WO tanpa
        // teknisi masuk kunci null dan dihitung terpisah sebagai antrean dispatch.
        val technicianNames = iamApi.usersByIds(openByTechnician.keys.filterNotNullTo(HashSet()))
            .associate { it.id to it.name }
        val workloads = openByTechnician
            .filterKeys { it != null }
            .map { (technicianId, count) -> TechnicianWorkloadView(technicianId!!, technicianNames[technicianId], count) }
            .sortedByDescending { it.openCount }

        return WorkOrderDashboardView(
            total = byStatus.values.sum(),
            open = WorkOrderStatus.entries.filter { it.open }.sumOf { byStatus[it] ?: 0L },
            unassignedOpen = openByTechnician[null] ?: 0L,
            pendingApproval = repository.countPendingApproval(),
            byStatus = WorkOrderStatus.entries.associate { it.name to (byStatus[it] ?: 0L) },
            byType = WorkOrderType.entries.associate { it.name to (byType[it] ?: 0L) },
            workloads = workloads,
        )
    }

    private fun require(id: UUID): WorkOrder =
        repository.findById(id) ?: throw NotFoundException("Work order $id tidak ditemukan")

    private fun requireCustomerExists(customerId: UUID?) {
        if (customerId != null && customerApi.findCustomer(customerId) == null) {
            throw NotFoundException("Pelanggan $customerId tidak ditemukan")
        }
    }

    private fun requireActiveTechnician(technicianId: UUID) {
        val user = iamApi.findUser(technicianId)
            ?: throw NotFoundException("Teknisi $technicianId tidak ditemukan")
        if (!user.active) throw ConflictException("Teknisi ${user.name} tidak aktif")
    }

    /** View untuk satu WO: resolusi nama per-baris (murah untuk detail/aksi tunggal). */
    private fun WorkOrder.toView(): WorkOrderView = toView(
        customerName = customerId?.let { customerApi.findCustomer(it)?.name },
        technicianName = assignedTo?.let { iamApi.findUser(it)?.name },
        approverName = approvedBy?.let { iamApi.findUser(it)?.name },
    )

    private fun WorkOrder.toView(customerName: String?, technicianName: String?, approverName: String?) = WorkOrderView(
        id = id,
        code = code,
        type = type.name,
        status = status.name,
        priority = priority.name,
        title = title,
        description = description,
        customerId = customerId,
        customerName = customerName,
        incidentId = incidentId,
        areaId = areaId,
        assignedTo = assignedTo,
        assignedToName = technicianName,
        scheduledAt = scheduledAt,
        assignedAt = assignedAt,
        startedAt = startedAt,
        completedAt = completedAt,
        resolutionNote = resolutionNote,
        cancelReason = cancelReason,
        rxBeforeDbm = rxBeforeDbm,
        rxAfterDbm = rxAfterDbm,
        approvalStatus = approvalStatus?.name,
        approvedBy = approvedBy,
        approvedByName = approverName,
        approvedAt = approvedAt,
        approvalNote = approvalNote,
        createdAt = createdAt,
    )

    private fun WorkOrderEvent.toView() = WorkOrderEventView(type = type.name, message = message, at = at)
}
