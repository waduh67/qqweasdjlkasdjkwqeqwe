package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import java.time.Instant
import java.util.UUID

data class WarehouseDispositionContext(val workOrderId: UUID, val workOrderRevision: Long, val assetRevision: Long?)
data class WarehouseDispositionRecord(val id: UUID, val code: String, val input: WarehouseDispositionInput,
    val actorId: UUID, val returned: WarehouseReturnRecord, val source: WarehouseReturnSource,
    val cost: ReceiptCostSnapshot?, val context: WarehouseDispositionContext, val authorityEpoch: Long,
    val cutoverEpoch: Long, val recordedAt: Instant) {
    fun view(revision: Long = 0, state: WarehouseDispositionState = WarehouseDispositionState.DRAFT) = WarehouseDispositionView(
        id, code, revision, state, input.action, input.sourceDocumentId, input.expectedRevision, input.stockIdentityId,
        source.dimension.skuId, input.quantityBase, input.baseUnit, source.dimension.locationId, input.destinationLocationId,
        source.dimension.legalOwner, input.reason, input.evidenceReference, recordedAt)
}
