package com.duluin.ftth.inventory

import com.duluin.ftth.common.security.AuthorityFence
import java.time.Instant
import java.util.UUID

data class MaterialPlanningContext(val workOrderId: UUID, val code: String, val workType: String, val action: String,
    val workOrderRevision: Long, val customerId: UUID?, val areaId: UUID?, val activeAssigneeIds: Set<UUID>,
    val authority: AuthorityFence, val cutover: TenantCutoverFence, val customerLabelSnapshot: String? = null)

data class MaterialPlanningRequest(val expectedRevision: Long, val workOrderRevision: Long, val materialMode: MaterialMode,
    val reason: String? = null, val lines: List<MaterialPlanLine>? = null)
data class MaterialPlanCommand(val expectedRevision: Long, val workOrderRevision: Long, val reason: String? = null)
data class MaterialSubstitution(val originalPlanLineId: UUID, val originalSkuId: UUID, val reason: String)
data class MaterialSkuSnapshot(val id: UUID, val revision: Long, val code: String, val name: String,
    val tracking: WarehouseTracking, val baseUnit: WarehouseBaseUnit)
data class MaterialPlanSnapshotLine(val id: UUID, val lineNumber: Int, val sku: MaterialSkuSnapshot,
    val quantityBase: String, val continuousCut: Boolean, val substitution: MaterialSubstitution?, val originalSku: MaterialSkuSnapshot?)
data class MaterialPlanSnapshot(val id: UUID, val workOrderId: UUID, val workOrderCode: String, val workType: String,
    val action: String, val customerId: UUID?, val workOrderRevision: Long, val planRevision: Long,
    val materialMode: MaterialMode, val reason: String?, val templateId: UUID?, val actorId: UUID,
    val lines: List<MaterialPlanSnapshotLine>, val recordedAt: Instant)
data class MaterialPlanHistory(val plan: MaterialPlanSnapshot, val state: String, val demandDocumentId: UUID?)
data class MaterialTemplateSnapshot(val id: UUID, val workType: String, val action: String, val revision: Long,
    val lines: List<MaterialPlanSnapshotLine>)
data class MaterialTemplateRequest(val expectedRevision: Long, val lines: List<MaterialPlanLine>)
interface InventoryMaterialTemplateApi {
    fun current(workType: String, action: String): MaterialTemplateSnapshot?
    fun publish(workType: String, action: String, request: MaterialTemplateRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
}
interface InventoryApprovalInvalidationApi {
    fun invalidateUnposted(documentIds: Set<UUID>, authority: AuthorityFence, cutover: TenantCutoverFence): Int
}
