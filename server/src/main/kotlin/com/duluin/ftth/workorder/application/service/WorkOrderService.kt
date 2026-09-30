package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.workorder.WorkOrderAssigned
import com.duluin.ftth.workorder.application.port.inbound.ManageWorkOrderUseCase
import com.duluin.ftth.workorder.application.port.inbound.RecordOpticalCommand
import com.duluin.ftth.workorder.application.port.inbound.SaveWorkOrderCommand
import com.duluin.ftth.workorder.application.port.inbound.UpdateWorkOrderCommand
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderView
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderEvidenceRepository
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderSignatureRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.ProofOfWorkPacket
import com.duluin.ftth.workorder.domain.model.ProofArtifactCompatibility
import com.duluin.ftth.workorder.domain.model.ProofArtifactKind
import org.springframework.context.ApplicationEventPublisher
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
    private val events: ApplicationEventPublisher,
    private val evidence: WorkOrderEvidenceRepository,
    private val signatures: WorkOrderSignatureRepository,
    private val cutovers: com.duluin.ftth.inventory.InventoryTenantCutoverApi,
    private val authority: com.duluin.ftth.iam.CurrentAuthorityApi,
    private val approvals: WorkOrderApprovalService,
    private val materialLifecycle: com.duluin.ftth.workorder.WorkOrderMaterialLifecyclePort,
    private val views: WorkOrderViewMapper,
) : ManageWorkOrderUseCase {

    @Transactional
    override fun create(command: SaveWorkOrderCommand): WorkOrderView {
        val current = commandFence("workorder.order.create")
        requireArea(command.areaId, current)
        requireCustomerExists(command.customerId)
        command.assignees.forEach { requireActiveTechnician(it) }
        val actor = current.fence.identity
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
            assignees = command.assignees,
            createdBy = actor.userId,
            subscriptionId = command.subscriptionId,
            orderId = command.orderId,
        )
        val saved = repository.save(workOrder)
        if (saved.assignees.isNotEmpty()) publishAssigned(saved)
        return saved.toView()
    }

    @Transactional
    override fun update(id: UUID, command: UpdateWorkOrderCommand): WorkOrderView {
        val current = commandFence("workorder.order.update")
        val workOrder = require(id, current)
        requireArea(command.areaId, current)
        requireCustomerExists(command.customerId)
        workOrder.updateDetails(
            newTitle = command.title,
            newDescription = command.description,
            newPriority = command.priority,
            newCustomerId = command.customerId,
            newIncidentId = command.incidentId,
            newAreaId = command.areaId,
            newScheduledAt = command.scheduledAt,
            at = Instant.now(),
            actorId = current.fence.identity.userId,
        )
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun assign(id: UUID, technicianIds: Set<UUID>): WorkOrderView {
        val current = commandFence("workorder.order.assign")
        if (technicianIds.isEmpty()) throw ConflictException("Minimal satu teknisi harus ditugaskan")
        val workOrder = require(id, current)
        technicianIds.forEach { requireActiveTechnician(it) }
        materialLifecycle.beforeChange(id, com.duluin.ftth.workorder.MaterialLifecycleChange.REASSIGN)
        workOrder.assign(technicianIds, Instant.now(), current.fence.identity.userId)
        val saved = repository.save(workOrder)
        publishAssigned(saved)
        return saved.toView()
    }

    @Transactional
    override fun start(id: UUID): WorkOrderView {
        val current = commandFence("workorder.order.update", "workorder.order.field")
        val workOrder = require(id, current)
        requireFieldAccess(workOrder, "workorder.order.update", current)
        workOrder.start(Instant.now(), current.fence.identity.userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun authorizeComplete(id: UUID) {
        val current = commandFence("workorder.order.close", "workorder.order.field")
        val workOrder = require(id, current)
        requireFieldAccess(workOrder, "workorder.order.close", current)
    }

    @Transactional
    override fun complete(id: UUID, resolutionNote: String?, packet: ProofOfWorkPacket): WorkOrderView {
        val current = commandFence("workorder.order.close", "workorder.order.field")
        val workOrder = require(id, current)
        requireFieldAccess(workOrder, "workorder.order.close", current)
        val authoritativeArtifacts = HashMap<UUID, ProofArtifactKind>()
        evidence.listByWorkOrder(id).forEach { item ->
            ProofArtifactCompatibility.fromEvidence(item.kind)?.let { kind -> authoritativeArtifacts[item.id] = kind }
        }
        signatures.findByWorkOrder(id)?.let { signature ->
            authoritativeArtifacts[signature.id] = ProofArtifactCompatibility.customerSignature
        }
        val authoritativeRevision = proofRevision(authoritativeArtifacts.keys)
        if (packet.revision != authoritativeRevision) {
            throw ConflictException("Proof of Work sudah berubah; muat ulang bukti sebelum mengirim")
        }
        ProofArtifactCompatibility.requireMatching(packet.artifacts, authoritativeArtifacts)
        materialLifecycle.beforeChange(id, com.duluin.ftth.workorder.MaterialLifecycleChange.RESUBMIT)
        workOrder.complete(resolutionNote, packet, Instant.now(), current.fence.identity.userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun cancel(id: UUID, reason: String?): WorkOrderView {
        val current = commandFence("workorder.order.close")
        val workOrder = require(id, current)
        materialLifecycle.beforeChange(id, com.duluin.ftth.workorder.MaterialLifecycleChange.CANCEL)
        workOrder.cancel(reason, Instant.now(), current.fence.identity.userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun recordOptical(id: UUID, command: RecordOpticalCommand): WorkOrderView {
        val current = commandFence("workorder.order.update", "workorder.order.field")
        val workOrder = require(id, current)
        requireFieldAccess(workOrder, "workorder.order.update", current)
        workOrder.recordOptical(command.rxBeforeDbm, command.rxAfterDbm, Instant.now(), current.fence.identity.userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun approve(id: UUID, note: String?): WorkOrderView = approvals.approve(id, note).toView()

    @Transactional
    override fun reject(id: UUID, reason: String): WorkOrderView {
        val current = commandFence("workorder.order.approve")
        val workOrder = require(id, current)
        materialLifecycle.beforeChange(id, com.duluin.ftth.workorder.MaterialLifecycleChange.REWORK)
        workOrder.reject(reason, Instant.now(), current.fence.identity.userId)
        return repository.save(workOrder).toView()
    }

    @Transactional
    override fun delete(id: UUID) {
        val current = commandFence("workorder.order.update")
        val workOrder = require(id, current)
        // Sekali ditugaskan, work order punya jejak (assignment/timeline) yang tak boleh
        // hilang diam-diam; yang belum tersentuh boleh dihapus, sisanya dibatalkan saja.
        if (workOrder.status != WorkOrderStatus.DRAFT) {
            throw ConflictException("Hanya work order berstatus DRAFT yang bisa dihapus; batalkan sisanya")
        }
        repository.deleteById(id)
    }

    private fun commandFence(vararg permissions: String): CurrentAuthority {
        cutovers.lockForCommand(cutovers.read().epoch, com.duluin.ftth.inventory.WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authority.lockCurrent()
        if (!current.platformAdmin && permissions.none { it in current.permissions }) throw AccessDeniedException("Current work order permission required")
        return current
    }

    private fun require(id: UUID): WorkOrder =
        repository.findById(id) ?: throw NotFoundException("Work order $id tidak ditemukan")

    private fun require(id: UUID, current: CurrentAuthority): WorkOrder = require(id).also { requireArea(it.areaId, current) }

    private fun requireArea(areaId: UUID?, current: CurrentAuthority) {
        current.fence.assertHeld()
        val scope = current.areaScope
        if (scope is AuthorityScope.Restricted && (if (areaId == null) scope.ids.isNotEmpty() else areaId !in scope.ids)) {
            throw AccessDeniedException("Work order di luar area Anda")
        }
    }

    private fun proofRevision(revisionIds: Set<UUID>): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(revisionIds.sortedBy { it.toString() }.joinToString("|").toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun requireCustomerExists(customerId: UUID?) {
        if (customerId != null && customerApi.findCustomer(customerId) == null) {
            throw NotFoundException("Pelanggan $customerId tidak ditemukan")
        }
    }

    private fun requireActiveTechnician(technicianId: UUID) {
        val user = iamApi.findUser(technicianId)
            ?: throw NotFoundException("Teknisi $technicianId tidak ditemukan")
        if (!user.active) throw ConflictException("Teknisi ${user.name} tidak aktif")
        if (!user.technician) throw ConflictException("Pengguna ${user.name} bukan teknisi")
    }

    /**
     * Pengerjaan lapangan dibatasi kepemilikan: pemegang izin dispatcher boleh aksi
     * WO mana pun, sedangkan teknisi lapangan (hanya izin `field`) hanya boleh WO
     * yang ditugaskan ke dirinya. Seluruh keputusan memakai authority yang dipagar.
     */
    private fun requireFieldAccess(workOrder: WorkOrder, dispatcherPermission: String, current: CurrentAuthority) {
        current.fence.assertHeld()
        if (current.platformAdmin || dispatcherPermission in current.permissions) return
        val actor = current.fence.identity
        val technician = iamApi.findUser(actor.userId)
        if ("workorder.order.field" !in current.permissions || technician?.active != true || !technician.technician || !workOrder.isAssignedTo(actor.userId)) {
            throw AccessDeniedException("Work order ${workOrder.code} tidak ditugaskan ke Anda")
        }
    }

    private fun publishAssigned(workOrder: WorkOrder) {
        events.publishEvent(
            WorkOrderAssigned(
                tenantId = workOrder.tenantId,
                workOrderId = workOrder.id,
                code = workOrder.code,
                title = workOrder.title,
                technicianIds = workOrder.assignees.toList(),
                scheduledAt = workOrder.scheduledAt,
                customerId = workOrder.customerId,
            ),
        )
    }

    private fun WorkOrder.toView(): WorkOrderView = views.single(this)
}
