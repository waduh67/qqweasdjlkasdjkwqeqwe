package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryMyMaterialsApi {
    fun pendingReturns(page: WarehousePageRequest): WarehousePage<MyMaterialResidual>
    fun jobs(page: WarehousePageRequest): WarehousePage<MyMaterialJob>
    fun authorize(context: MaterialPlanningContext)
    fun custody(context: MaterialPlanningContext, page: WarehousePageRequest, identity: UUID? = null): WarehousePage<MaterialCustodyChoice>
    fun issues(context: MaterialPlanningContext, page: WarehousePageRequest, issue: UUID? = null): WarehousePage<MyMaterialIssue>
    fun residuals(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MyMaterialResidual>
    fun returnLocations(context: MaterialPlanningContext, page: WarehousePageRequest, location: UUID? = null): WarehousePage<WarehouseApprovalLocation>
}
data class MyMaterialJob(val id: UUID, val code: String, val updatedAt: Instant)
data class MyMaterialIssue(val id: UUID, val code: String, val workOrderId: UUID, val workOrderRevision: Long,
    val revision: Long, val state: String, val sender: WarehousePolicyChoice, val receiver: WarehousePolicyChoice,
    val lines: List<MyMaterialIssueLine>)
data class MyMaterialIssueLine(val id: UUID, val stockIdentityId: UUID, val sku: MaterialSkuSnapshot,
    val baseUnit: WarehouseBaseUnit, val dispatchedBase: String, val acceptedBase: String, val remainingBase: String,
    val serial: String?, val lotCode: String?)
data class MyMaterialResidual(val id: UUID, val code: String, val workOrderId: UUID, val revision: Long,
    val state: String, val purpose: ResidualPurpose, val sender: WarehousePolicyChoice?, val receiver: WarehousePolicyChoice?,
    val location: WarehouseApprovalLocation, val sku: MaterialSkuSnapshot, val quantityBase: String, val baseUnit: WarehouseBaseUnit,
    val serial: String?, val lotCode: String?, val recordedAt: Instant)
