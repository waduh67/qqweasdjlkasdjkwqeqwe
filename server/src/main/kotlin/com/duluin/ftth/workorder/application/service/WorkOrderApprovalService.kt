package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.InventoryTenantCutoverApi
import com.duluin.ftth.inventory.WarehouseOperationClass
import com.duluin.ftth.workorder.FulfillmentApproved
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.domain.model.WorkOrder
import com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class WorkOrderApprovalService(private val repository: WorkOrderRepository, private val authority: CurrentAuthorityApi,
    private val cutovers: InventoryTenantCutoverApi, private val events: ApplicationEventPublisher) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun approve(id: UUID, note: String?): WorkOrder {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authority.lockCurrent()
        if (!current.platformAdmin && "workorder.order.approve" !in current.permissions) throw AccessDeniedException("Current approval permission required")
        val workOrder = repository.findById(id) ?: throw NotFoundException("Work order not found")
        val scope = current.areaScope
        if (scope is AuthorityScope.Restricted && (if (workOrder.areaId == null) scope.ids.isNotEmpty() else workOrder.areaId !in scope.ids))
            throw AccessDeniedException("Work order outside current area")
        val saved = if (workOrder.approvalStatus == WorkOrderApprovalStatus.APPROVED) {
            if (workOrder.approvedBy != current.fence.identity.userId) throw AccessDeniedException("Approval belongs to another actor")
            workOrder
        } else {
            workOrder.approve(note, Instant.now(), current.fence.identity.userId)
            repository.save(workOrder)
        }
        events.publishEvent(FulfillmentApproved(saved.tenantId, saved.id, saved.type.name, saved.subscriptionId,
            requireNotNull(saved.proofOfWorkHash), saved.orderId, saved.approvedBy, verifiedMaterialRequired = true))
        return saved
    }
}
