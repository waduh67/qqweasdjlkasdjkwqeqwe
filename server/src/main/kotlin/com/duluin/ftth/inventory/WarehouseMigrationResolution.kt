package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

enum class MigrationResolutionKind { BASELINE_STOCK, PROVENANCE_ONLY, DUPLICATE, CANCEL_PENDING }
enum class MigrationSourceUnit { EA, MM, M }

data class MigrationEvidenceReference(val id: UUID, val sourceHash: String, val sha256: String, val uploadedBy: UUID)
data class MigrationBaselineStock(val skuId: UUID, val skuRevision: Long, val locationId: UUID, val locationRevision: Long,
    val tracking: WarehouseTracking, val sourceUnit: MigrationSourceUnit, val quantityBase: String,
    val baseUnit: WarehouseBaseUnit, val legalOwner: AssetLegalOwner)

/** A proposal for independent batch review. It never constitutes stock admission. */
data class WarehouseMigrationResolution(val id: UUID, val batchId: UUID, val caseId: UUID, val sourceHash: String,
    val revision: Long, val kind: MigrationResolutionKind, val reason: String, val evidence: List<MigrationEvidenceReference>,
    val stock: MigrationBaselineStock?, val duplicateCaseId: UUID?, val duplicateResolutionId: UUID?,
    val resolvedBy: UUID, val createdAt: Instant)
