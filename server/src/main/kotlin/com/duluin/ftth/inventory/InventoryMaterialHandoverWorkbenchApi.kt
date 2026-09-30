package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryMaterialHandoverWorkbenchApi {
    fun sources(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialHandoverSource>
    fun targets(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialHandoverTarget>
    fun pending(context: MaterialPlanningContext, page: WarehousePageRequest, id: UUID? = null): WarehousePage<MaterialHandoverGrant>
}
data class MaterialHandoverSource(val id: UUID, val sender: WarehousePolicyChoice, val source: MaterialCustodyChoice)
data class MaterialHandoverTarget(val id: UUID, val code: String, val name: String, val receiver: WarehousePolicyChoice)
data class MaterialHandoverGrant(val id: UUID, val workOrderId: UUID, val request: MaterialResidualRequest,
    val sender: WarehousePolicyChoice, val receiver: WarehousePolicyChoice, val dispatcher: WarehousePolicyChoice,
    val location: WarehouseApprovalLocation, val sku: MaterialSkuSnapshot, val serial: String?, val lotCode: String?,
    val currentQuantityBase: String, val stockRevision: Long, val recordedAt: Instant)
