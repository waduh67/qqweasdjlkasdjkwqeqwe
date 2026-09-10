package com.duluin.ftth.fulfillment

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.WorkOrderMaterialContextApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class MaterialWorkflowService(private val workOrders: WorkOrderMaterialContextApi, private val materials: InventoryMaterialApi,
    private val cutovers: InventoryTenantCutoverApi, private val authority: CurrentAuthorityApi) {
    fun summary(id: UUID): MaterialSummary = materials.summary(context(id, null))
    fun history(id: UUID, page: WarehousePageRequest) = materials.history(context(id, null), page)
    fun replacePlan(id: UUID, request: MaterialPlanningRequest, key: String) =
        materials.replacePlan(context(id, request.workOrderRevision), request, WarehouseMutationMetadata(key))
    fun submitRequest(id: UUID, request: MaterialPlanCommand, key: String) =
        materials.submitRequest(context(id, request.workOrderRevision), request, WarehouseMutationMetadata(key))
    fun reserve(id: UUID, request: MaterialPlanCommand, key: String) =
        materials.reserve(context(id, request.workOrderRevision), request, WarehouseMutationMetadata(key))
    fun release(id: UUID, request: MaterialPlanCommand, key: String) =
        materials.release(context(id, request.workOrderRevision), request, WarehouseMutationMetadata(key))
    private fun context(id: UUID, revision: Long?): MaterialPlanningContext {
        val cutover = cutovers.lockForCommand(cutovers.read().epoch,
            if (revision == null) WarehouseOperationClass.CONTROL_PLANE else WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        val workOrder = if (revision == null) workOrders.read(id) else workOrders.lock(id, revision, current.fence)
        return MaterialPlanningContext(id, workOrder.code, workOrder.workType, workOrder.action.name, workOrder.workOrderRevision,
            workOrder.customerId, workOrder.areaId, workOrder.activeAssigneeIds, current.fence, cutover)
    }
}
