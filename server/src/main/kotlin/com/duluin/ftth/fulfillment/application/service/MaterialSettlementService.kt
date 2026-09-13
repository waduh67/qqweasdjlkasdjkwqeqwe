package com.duluin.ftth.fulfillment.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.fulfillment.FulfillmentApprovalStore
import com.duluin.ftth.fulfillment.FulfillmentCheckpointRepository
import com.duluin.ftth.fulfillment.FulfillmentEffectType
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class WorkOrderMaterialSettlement(
    val workOrderId: UUID, val technicalState: String, val qaState: String?, val provisioningState: String,
    val materialState: ResidualSettlementState, val revision: Long, val outstandingBase: String,
    val obligations: MaterialObligations,
)

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MaterialSettlementService(
    private val workOrders: WorkOrderMaterialContextApi,
    private val inventory: InventoryMaterialLifecycleApi,
    private val cutovers: InventoryTenantCutoverApi,
    private val authority: CurrentAuthorityApi,
    private val approvals: FulfillmentApprovalStore,
    private val checkpoints: FulfillmentCheckpointRepository,
    private val reworkContext: WorkOrderMaterialReworkApi,
) : WorkOrderMaterialLifecyclePort {
    override fun beforeChange(workOrderId: UUID, change: MaterialLifecycleChange) {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        authority.lockCurrent().fence.assertHeld()
        if (!inventory.participates(workOrderId)) return
        val context = context(workOrderId).second
        inventory.beforeChange(context, when (change) {
            MaterialLifecycleChange.CANCEL -> MaterialLifecycleAction.CANCEL
            MaterialLifecycleChange.REASSIGN -> MaterialLifecycleAction.REASSIGN
            MaterialLifecycleChange.REWORK -> MaterialLifecycleAction.REWORK
            MaterialLifecycleChange.RESUBMIT -> MaterialLifecycleAction.RESUBMIT
        })
    }

    fun summary(workOrderId: UUID): WorkOrderMaterialSettlement {
        val (workOrder, context) = context(workOrderId)
        val summary = inventory.summary(context)
        val approval = approvals.forWorkOrder(workOrderId)?.request
        val provisioning = if (approval == null || FulfillmentEffectType.PROVISIONING !in approval.requiredEffects) "NOT_APPLICABLE"
            else if (FulfillmentEffectType.PROVISIONING in checkpoints.completedEffects(approval.tenantId, approval.namespace, approval.operationKey)) "OWNER_APPLIED"
            else checkpoints.find(approval.tenantId, approval.namespace, approval.operationKey)?.state?.name ?: "PENDING"
        return WorkOrderMaterialSettlement(workOrderId, workOrder.technicalState, workOrder.qaState,
            provisioning, summary.materialState,
            summary.revision, summary.outstandingBase, summary)
    }

    fun close(workOrderId: UUID, request: MaterialCloseRequest, key: String) =
        inventory.close(context(workOrderId).second, request, WarehouseMutationMetadata(key))

    fun dispatch(workOrderId: UUID, request: MaterialResidualRequest, key: String) =
        inventory.dispatch(context(workOrderId).second, request, WarehouseMutationMetadata(key))

    fun acknowledge(workOrderId: UUID, request: MaterialResidualAcknowledgement, key: String) =
        inventory.acknowledge(context(workOrderId).second, request, WarehouseMutationMetadata(key))

    fun authorizeHandover(workOrderId: UUID, request: MaterialResidualRequest, key: String) =
        inventory.authorizeHandover(context(workOrderId).second, request, WarehouseMutationMetadata(key))

    fun correctUse(workOrderId: UUID, request: MaterialUsageDeltaRequest, key: String): WarehouseOperationReceipt {
        val (workOrder, context) = context(workOrderId)
        if (!workOrder.material.active || workOrder.material.cancelled || context.authority.identity.userId !in context.activeAssigneeIds)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.FORBIDDEN, "Current active assigned technician required"))
        if (request.reworkId != null && reworkContext.lock(workOrderId, context.authority).evidenceRevision != request.evidenceRevision)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.STALE_REVISION, "Evidence revision changed"))
        return inventory.correctUse(context, request, WarehouseMutationMetadata(key))
    }

    private fun context(id: UUID): Pair<WorkOrderLifecycleContext, MaterialPlanningContext> {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE)
        val current = authority.lockCurrent()
        val lifecycle = workOrders.lockForCustody(id, current.fence)
        val workOrder = lifecycle.material
        return lifecycle to MaterialPlanningContext(id, workOrder.code, workOrder.workType, workOrder.action.name,
            workOrder.workOrderRevision, workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, current.fence, cutover)
    }
}
