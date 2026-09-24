package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import java.time.Instant
import java.util.UUID

data class WarehouseAssetLossRecord(val id: UUID, val code: String, val input: WarehouseAssetLossInput,
    val actorId: UUID, val ownership: CurrentAssetOwnership, val position: AssetHandoverPosition,
    val source: TransferStock, val evidence: AssetHandoverSignature, val workOrderRevision: Long,
    val authorityEpoch: Long, val cutoverEpoch: Long, val recordedAt: Instant) {
    fun view(revision: Long = 0, state: WarehouseDispositionState = WarehouseDispositionState.DRAFT) =
        WarehouseAssetLossView(id, code, revision, state, input.assignmentId, input.sourceHandoverId,
            ownership.assetId, position.dimension.locationId, input.destinationLocationId, "1", WarehouseBaseUnit.EA,
            input.reason, input.evidenceId, recordedAt)
}
