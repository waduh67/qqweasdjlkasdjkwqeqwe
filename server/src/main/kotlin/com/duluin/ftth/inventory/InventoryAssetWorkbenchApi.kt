package com.duluin.ftth.inventory

import com.duluin.ftth.common.security.AuthorityFence
import java.time.Instant
import java.util.UUID

interface InventoryAssetWorkbenchApi {
    fun sources(context: MaterialPlanningContext, page: WarehousePageRequest, assetId: UUID? = null): WarehousePage<CustomerAssetSource>
    fun history(context: AssetCustomerReadContext, page: WarehousePageRequest, assignmentId: UUID? = null): WarehousePage<CustomerAssetAssignmentView>
}
/** The customer owner must authorize customer visibility under this fence before invoking inventory. */
data class AssetCustomerReadContext(val customerId: UUID, val authority: AuthorityFence, val cutover: TenantCutoverFence)
data class CustomerAssetSource(val id: UUID, val source: MaterialCustodyChoice, val assetRevision: Long,
    val provenance: AssetProvenance, val ownershipModes: List<AssetOwnershipMode>)
data class CustomerAssetOrigin(val id: UUID, val code: String, val kind: String)
data class CustomerAssetAssignmentView(val id: UUID, val customerId: UUID, val assetId: UUID, val serial: String,
    val sku: MaterialSkuSnapshot, val workOrderId: UUID, val issueId: UUID?, val issueCode: String?,
    val revision: Long, val titleRevision: Long, val purpose: DeploymentPurpose, val provenance: AssetProvenance,
    val ownershipMode: AssetOwnershipMode, val legalOwner: AssetLegalOwner, val handoverState: AssetHandoverState,
    val startedAt: Instant, val endedAt: Instant?, val previousAssignmentId: UUID?, val origin: CustomerAssetOrigin?,
    val positionStatus: String, val recoveryRequired: Boolean)
