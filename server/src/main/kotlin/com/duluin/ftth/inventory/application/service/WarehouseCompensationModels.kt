package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import java.time.Instant
import java.util.UUID

data class WarehouseCompensationContext(val workOrderId: UUID, val workOrderRevision: Long, val assetRevision: Long?, val materialRevision: Long?)
data class WarehouseCompensationRecord(val id: UUID, val code: String, val original: WarehouseDispositionRecord,
    val originalPostingId: UUID, val input: WarehouseCompensationInput, val actorId: UUID,
    val returned: WarehouseReturnRecord, val source: WarehouseReturnSource, val context: WarehouseCompensationContext,
    val authorityEpoch: Long, val cutoverEpoch: Long, val recordedAt: Instant) {
    fun view(revision: Long = 0, state: WarehouseDispositionState = WarehouseDispositionState.DRAFT) = WarehouseCompensationView(
        id, code, revision, state, original.id, originalPostingId, returned.view.id, source.dimension.stockIdentityId,
        source.quantity.toString(), source.unit, source.dimension.locationId, input.destinationLocationId, input.reason, input.evidenceReference, recordedAt)
}
