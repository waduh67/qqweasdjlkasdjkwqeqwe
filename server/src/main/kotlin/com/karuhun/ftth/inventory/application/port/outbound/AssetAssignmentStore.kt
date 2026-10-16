package com.karuhun.ftth.inventory.application.port.outbound

import com.karuhun.ftth.inventory.AssetLegalOwner
import com.karuhun.ftth.inventory.AssetOwnershipMode
import com.karuhun.ftth.inventory.AssetProvenance
import com.karuhun.ftth.inventory.DeploymentPurpose
import com.karuhun.ftth.inventory.WarehouseAdmission
import java.time.Instant
import java.util.UUID

interface AssetAssignmentStore {
    fun append(assignment: NewAssetAssignment)
    fun close(closure: AssetAssignmentClosure)
    fun history(assetId: UUID): List<StoredAssetAssignment>
}

data class NewAssetAssignment(
    val id: UUID,
    val assetId: UUID,
    val customerId: UUID,
    val workOrderId: UUID,
    val issueLineId: UUID?,
    val purpose: DeploymentPurpose,
    val ownershipMode: AssetOwnershipMode,
    val legalOwner: AssetLegalOwner,
    val provenance: AssetProvenance,
    val actorId: UUID,
    val startedAt: Instant,
    val previousAssignmentId: UUID? = null,
)

data class AssetAssignmentClosure(val id: UUID, val expectedRevision: Long, val endedAt: Instant)

data class StoredAssetAssignment(
    val id: UUID,
    val assetId: UUID?,
    val customerId: UUID?,
    val workOrderId: UUID?,
    val issueLineId: UUID?,
    val revision: Long,
    val admission: WarehouseAdmission,
    val purpose: DeploymentPurpose?,
    val ownershipMode: AssetOwnershipMode?,
    val legalOwner: AssetLegalOwner?,
    val provenance: AssetProvenance,
    val startedAt: Instant?,
    val endedAt: Instant?,
)
