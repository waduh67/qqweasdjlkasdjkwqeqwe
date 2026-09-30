package com.duluin.ftth.fulfillment.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.InventoryMaterialUsageApi
import com.duluin.ftth.inventory.InventoryTenantCutoverApi
import com.duluin.ftth.inventory.MaterialPlanningContext
import com.duluin.ftth.inventory.MaterialUsageRequest
import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.WarehouseMutationMetadata
import com.duluin.ftth.inventory.WarehouseOperationClass
import com.duluin.ftth.inventory.WarehouseOperationReceipt
import com.duluin.ftth.workorder.WorkOrderMaterialContextApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MaterialUsageService(
    private val workOrders: WorkOrderMaterialContextApi,
    private val inventory: InventoryMaterialUsageApi,
    private val cutovers: InventoryTenantCutoverApi,
    private val authority: CurrentAuthorityApi,
) {
    fun reportUse(workOrderId: UUID, request: MaterialUsageRequest, key: String): WarehouseOperationReceipt {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        val workOrder = workOrders.read(workOrderId)
        if ("workorder.order.field" !in current.permissions || current.fence.identity.userId !in workOrder.activeAssigneeIds)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.WRONG_CUSTODIAN, "Current assigned technician required"))
        if (!workOrder.active || workOrder.cancelled)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Active work order required"))
        val context = MaterialPlanningContext(workOrderId, workOrder.code, workOrder.workType, workOrder.action.name,
            workOrder.workOrderRevision, workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, current.fence, cutover)
        return inventory.reportUse(context, request, WarehouseMutationMetadata(key))
    }
}
