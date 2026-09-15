package com.duluin.ftth.inventory

import com.duluin.ftth.common.security.AuthorityFence
import java.time.Instant
import java.util.UUID

interface InventoryAssetReplacementApi {
    fun replace(customerId: UUID, request: ReplacePhysicalAssetRequest, metadata: WarehouseMutationMetadata): AssetRemovalResult
    fun remove(customerId: UUID, request: RemovePhysicalAssetRequest, metadata: WarehouseMutationMetadata): AssetRemovalResult
    fun lockTopology(context: AssetTopologyContext): CurrentAssetOwnership
    fun authorizeOutcome(customerId: UUID, operationId: UUID): AssetRemovalResult
}

data class ReplacePhysicalAssetRequest(val authorizationId: UUID, val expectedRevision: Long,
    val expectedAssignmentRevision: Long, val expectedTitleRevision: Long, val evidenceId: UUID, val installationPayload: String)
data class RemovePhysicalAssetRequest(val assignmentId: UUID, val workOrderId: UUID, val expectedRevision: Long,
    val expectedTitleRevision: Long, val evidenceId: UUID)
data class AssetRemovalResult(val operationId: UUID, val assignmentId: UUID, val assetId: UUID, val customerId: UUID,
    val workOrderId: UUID, val removedAt: Instant, val legalOwner: AssetLegalOwner, val replacement: DeploymentConsumption?)

interface AssetRemovalWorkOrderPort {
    fun lock(request: AssetRemovalWorkOrderRequest, authority: AuthorityFence): Long
}
data class AssetRemovalWorkOrderRequest(val workOrderId: UUID, val customerId: UUID, val purpose: DeploymentPurpose)
data class AssetTopologyContext(val customerId: UUID, val assignmentId: UUID, val workOrderId: UUID, val expectedWorkOrderRevision: Long)
