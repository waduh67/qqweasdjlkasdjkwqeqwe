package com.karuhun.ftth.fulfillment.application.service

import com.karuhun.ftth.iam.CurrentAuthorityApi
import com.karuhun.ftth.inventory.InventoryMaterialReceiptApi
import com.karuhun.ftth.inventory.InventoryTenantCutoverApi
import com.karuhun.ftth.inventory.MaterialPlanningContext
import com.karuhun.ftth.inventory.MaterialReceiptRequest
import com.karuhun.ftth.inventory.WarehouseContractException
import com.karuhun.ftth.inventory.WarehouseError
import com.karuhun.ftth.inventory.WarehouseErrorCode
import com.karuhun.ftth.inventory.WarehouseMutationMetadata
import com.karuhun.ftth.inventory.WarehouseOperationClass
import com.karuhun.ftth.inventory.WarehouseOperationReceipt
import com.karuhun.ftth.workorder.WorkOrderMaterialContextApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MaterialReceiptService(
    private val workOrders: WorkOrderMaterialContextApi,
    private val inventory: InventoryMaterialReceiptApi,
    private val cutovers: InventoryTenantCutoverApi,
    private val authority: CurrentAuthorityApi,
) {
    fun acknowledge(workOrderId: UUID, request: MaterialReceiptRequest, key: String): WarehouseOperationReceipt {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        val workOrder = workOrders.read(workOrderId)
        if ("workorder.order.field" !in current.permissions || current.fence.identity.userId !in workOrder.activeAssigneeIds)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.WRONG_CUSTODIAN, "Current assigned receiver required"))
        if (!workOrder.active || workOrder.cancelled)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Active work order required"))
        val context = MaterialPlanningContext(workOrderId, workOrder.code, workOrder.workType, workOrder.action.name,
            workOrder.workOrderRevision, workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, current.fence, cutover)
        return inventory.acknowledge(context, request, WarehouseMutationMetadata(key))
    }
}
