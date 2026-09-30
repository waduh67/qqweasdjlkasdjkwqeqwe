package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

interface InventoryMaterialReworkApi {
    fun basis(context: MaterialReworkContext): MaterialReworkBasis
    fun append(context: MaterialReworkContext, request: MaterialReworkRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt
}

data class MaterialReworkBasis(val expectedRevision: Long, val workOrderRevision: Long, val previousPlanId: UUID,
    val previousUsageId: UUID, val expectedUsageRevision: Long, val previousEvidenceRevision: String, val evidenceRevision: String)

data class MaterialReworkRequest(val expectedRevision: Long, val workOrderRevision: Long, val previousPlanId: UUID,
    val previousUsageId: UUID, val expectedUsageRevision: Long, val previousEvidenceRevision: String,
    val evidenceRevision: String, val reason: String, val deltas: List<MaterialPlanLine>)
enum class MaterialEvidenceSource { PHOTO, SIGNATURE }
data class MaterialReworkEvidence(val revisionId: UUID, val kind: String, val source: MaterialEvidenceSource)
data class MaterialReworkContext(val material: MaterialPlanningContext, val previousEvidenceRevision: String,
    val evidenceRevision: String, val evidence: List<MaterialReworkEvidence>)
data class MaterialReworkSnapshot(val reworkId: UUID, val previousPlan: MaterialPlanSnapshot, val plan: MaterialPlanSnapshot,
    val previousUsageId: UUID, val previousUsageRevision: Long, val previousEvidenceRevision: String,
    val evidenceRevision: String, val evidence: List<MaterialReworkEvidence>, val inheritedLines: List<MaterialPlanSnapshotLine>,
    val reason: String, val actorId: UUID, val recordedAt: Instant)
