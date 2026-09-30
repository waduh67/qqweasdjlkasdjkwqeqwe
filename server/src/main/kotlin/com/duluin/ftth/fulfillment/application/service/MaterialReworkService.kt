package com.duluin.ftth.fulfillment.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.WorkOrderMaterialReworkApi
import com.duluin.ftth.workorder.WorkOrderEvidenceSource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MaterialReworkService(private val workOrders: WorkOrderMaterialReworkApi, private val inventory: InventoryMaterialReworkApi,
    private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi) {
    fun basis(workOrderId: UUID): MaterialReworkBasis = inventory.basis(context(workOrderId, WarehouseOperationClass.CONTROL_PLANE))

    fun append(workOrderId: UUID, request: MaterialReworkRequest, key: String): WarehouseOperationReceipt =
        inventory.append(context(workOrderId, WarehouseOperationClass.ORDINARY_STOCK), request, WarehouseMutationMetadata(key))

    private fun context(workOrderId: UUID, operation: WarehouseOperationClass): MaterialReworkContext {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, operation)
        val current = authority.lockCurrent()
        val workOrder = workOrders.lock(workOrderId, current.fence)
        val material = workOrder.material
        val context = MaterialPlanningContext(workOrderId, material.code, material.workType, material.action.name,
            material.workOrderRevision, material.customerId, material.areaId, material.activeAssigneeIds, current.fence, cutover)
        val evidence = workOrder.evidence.map { entry -> MaterialReworkEvidence(entry.revisionId, entry.kind, when (entry.source) {
            WorkOrderEvidenceSource.PHOTO -> MaterialEvidenceSource.PHOTO
            WorkOrderEvidenceSource.SIGNATURE -> MaterialEvidenceSource.SIGNATURE
        }) }
        return MaterialReworkContext(context, workOrder.previousEvidenceRevision, workOrder.evidenceRevision, evidence)
    }
}
