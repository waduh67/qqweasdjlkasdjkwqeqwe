package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryAssetTitleApi {
    fun requestCorrection(request: AssetTitleCorrectionRequest, metadata: WarehouseMutationMetadata): AssetTitleCorrectionRef
    fun forCustomer(customerId: UUID): List<CurrentAssetOwnership>
    fun exceptionContext(customerId: UUID, assignmentId: UUID): AssetExceptionContext
}

data class AssetTitleCorrectionRequest(val assignmentId: UUID, val sourceHandoverId: UUID,
    val expectedAssignmentRevision: Long, val expectedTitleRevision: Long, val targetOwner: AssetLegalOwner,
    val reason: String, val evidenceId: UUID)
data class AssetTitleCorrectionRef(val documentId: UUID, val assignmentId: UUID, val sourceTitleRevision: Long,
    val targetOwner: AssetLegalOwner)
data class CurrentAssetOwnership(val assignmentId: UUID, val assetId: UUID, val customerId: UUID, val workOrderId: UUID,
    val ownershipMode: AssetOwnershipMode, val legalOwner: AssetLegalOwner, val assignmentRevision: Long,
    val titleRevision: Long, val handoverId: UUID?, val latestTransferId: UUID?, val recoveryRequired: Boolean,
    val serviceCeased: Boolean = false, val recoveryDue: Boolean = false, val positionStatus: String = "CUSTOMER_INSTALLED")
data class AssetExceptionContext(val ownership: CurrentAssetOwnership, val customerLabel: String,
    val workOrder: AssetExceptionWorkOrder, val canRequestTitleCorrection: Boolean, val canRequestLoss: Boolean)
