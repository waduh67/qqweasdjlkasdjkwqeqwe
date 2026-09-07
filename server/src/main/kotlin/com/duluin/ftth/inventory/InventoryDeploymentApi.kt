package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryDeploymentApi {
    fun authorize(workOrderId: UUID, request: DeploymentIntentRequest, metadata: WarehouseMutationMetadata): DeploymentAuthorizationRef
    fun consume(request: ConsumeDeploymentRequest, metadata: WarehouseMutationMetadata): AssetAssignmentRef
    fun acceptHandover(request: AcceptAssetHandoverRequest, metadata: WarehouseMutationMetadata): AssetAssignmentRef
    fun assignmentHistory(assetId: UUID, page: WarehousePageRequest): WarehousePage<AssetAssignmentRef>
}

enum class DeploymentPurpose { INSTALL, REPLACE, REMOVE, RETURN_CUSTOMER_RMA }
enum class AssetHandoverState { PENDING, ACCEPTED }

data class DeploymentIntentRequest(
    val expectedRevision: Long,
    val assetId: UUID,
    val issueLineId: UUID?,
    val purpose: DeploymentPurpose,
    val ownershipMode: AssetOwnershipMode = AssetOwnershipMode.LOAN,
    val previousAssignmentId: UUID? = null,
    val repairCaseId: UUID? = null,
)

data class ConsumeDeploymentRequest(val authorizationId: UUID, val expectedRevision: Long)
data class AcceptAssetHandoverRequest(val assignmentId: UUID, val expectedRevision: Long, val evidenceId: UUID)

data class DeploymentAuthorizationRef(val authorizationId: UUID, val operationId: UUID, val revision: Long)

data class DeploymentBinding(
    val authorizationId: UUID,
    val operationId: UUID,
    val tenantId: UUID,
    val actorId: UUID,
    val assetId: UUID,
    val stockIdentityId: UUID,
    val issueLineId: UUID?,
    val workOrderId: UUID,
    val customerId: UUID,
    val purpose: DeploymentPurpose,
    val ownershipMode: AssetOwnershipMode,
    val revisions: MaterialRevisions,
    val issueRevision: Long?,
    val assetRevision: Long,
    val authorityEpoch: Long,
    val cutoverEpoch: Long,
    val repairReturn: CustomerRmaReturnBinding?,
    val previousAssignmentId: UUID?,
    val previousAssignmentRevision: Long?,
)

data class CustomerRmaReturnBinding(
    val originalAssignmentId: UUID,
    val originalCustomerId: UUID,
    val repairCaseId: UUID,
    val repairRevision: Long,
    val returnHandoverId: UUID,
)

data class AssetAssignmentRef(
    val assignmentId: UUID,
    val assetId: UUID,
    val customerId: UUID,
    val workOrderId: UUID,
    val issueLineId: UUID?,
    val revision: Long,
    val purpose: DeploymentPurpose,
    val provenance: AssetProvenance,
    val ownershipMode: AssetOwnershipMode,
    val legalOwner: AssetLegalOwner,
    val handoverState: AssetHandoverState,
    val acceptedHandover: AcceptedAssetHandover?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val previousAssignmentId: UUID?,
    val recoveryObligation: Boolean,
)

data class AcceptedAssetHandover(
    val handoverId: UUID,
    val assignmentId: UUID,
    val assetId: UUID,
    val workOrderId: UUID,
    val customerId: UUID,
    val evidenceId: UUID,
    val acceptedAt: Instant,
)
