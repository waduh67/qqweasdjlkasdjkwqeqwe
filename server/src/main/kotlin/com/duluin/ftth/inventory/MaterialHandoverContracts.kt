package com.duluin.ftth.inventory

import java.util.UUID

data class PickMaterialRequest(val documentId: UUID, val expectedRevision: Long, val lines: List<MaterialPickLine>)

data class MaterialPickLine(
    val demandLineId: UUID,
    val stockIdentityId: UUID,
    val quantityBase: String,
    val baseUnit: WarehouseBaseUnit,
)

data class AcknowledgeMaterialRequest(
    val documentId: UUID,
    val expectedRevision: Long,
    val lines: List<MaterialAcknowledgementLine>,
)

data class MaterialAcknowledgementLine(
    val issueLineId: UUID,
    val acceptedBase: String,
    val rejectedBase: String,
    val missingBase: String,
    val reason: String?,
)

data class ReturnMaterialRequest(
    val documentId: UUID,
    val expectedRevision: Long,
    val returnLocationId: UUID,
    val lines: List<MaterialUseLine>,
    val reason: String,
)

data class ReallocateMaterialRequest(
    val documentId: UUID,
    val expectedRevision: Long,
    val targetWorkOrderId: UUID,
    val targetPlanRevision: Long,
    val lines: List<MaterialUseLine>,
    val reason: String,
)
