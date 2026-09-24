package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

/** Read models for the existing WO and technician flows; mutations keep their canonical owner APIs. */
interface InventoryMaterialWorkbenchApi {
    fun context(context: MaterialPlanningContext): MaterialFieldContext
    fun custody(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialCustodyChoice>
    fun usage(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialUsageView>
    fun usageDetails(context: MaterialPlanningContext, id: UUID): MaterialUsageView
    fun obligations(context: MaterialPlanningContext, page: WarehousePageRequest): WarehousePage<MaterialObligationView>
}
data class MaterialFieldContext(val workOrderId: UUID, val workOrderRevision: Long, val plan: MaterialPlanSnapshot?,
    val planState: String?, val useRevision: Long, val latestUsageId: UUID?,
    val reworkId: UUID?, val evidenceRevision: String?)
data class MaterialCustodyChoice(val id: UUID, val receiptId: UUID, val issueId: UUID, val issueCode: String,
    val issueLineId: UUID, val planId: UUID, val planLineId: UUID, val sku: MaterialSkuSnapshot,
    val sourceUsageId: UUID?, val quantityBase: String, val baseUnit: WarehouseBaseUnit, val stockRevision: Long,
    val location: WarehouseApprovalLocation, val serial: String?, val lotCode: String?, val initialUseSource: Boolean)
data class MaterialUsageView(val id: UUID, val workOrderId: UUID, val planId: UUID, val planRevision: Long,
    val useRevision: Long, val materialMode: MaterialMode, val actor: WarehousePolicyChoice?, val evidenceReference: String,
    val reason: String?, val recordedAt: Instant, val lines: List<MaterialUsageViewLine>)
data class MaterialUsageViewLine(val id: UUID, val receiptId: UUID, val issueLineId: UUID, val sku: MaterialSkuSnapshot,
    val quantityBase: String, val baseUnit: WarehouseBaseUnit, val residualBase: String)
data class MaterialObligationView(val id: UUID, val issueCode: String, val sku: MaterialSkuSnapshot,
    val serial: String?, val lotCode: String?, val obligation: MaterialObligationLine)
