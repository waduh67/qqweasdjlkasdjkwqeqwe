package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehouseTracking
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import java.time.Instant
import java.util.UUID

data class ReferenceCountLoad(val skuId: UUID, val locationId: UUID)
data class ReferenceCountLocation(val id: UUID, val code: String, val name: String, val kind: String,
    val technicianName: String?)
data class ReferenceCountPosition(val balanceId: UUID, val dimension: PostingDimension, val quantityBase: String,
    val status: InventoryStatus, val balanceRevision: Long, val pieceRevision: Long, val serial: String?, val mac: String?)
data class ReferenceCountSnapshot(val id: UUID, val skuId: UUID, val skuName: String, val locationId: UUID,
    val locationName: String, val baseUnit: WarehouseBaseUnit, val tracking: WarehouseTracking, val bookBase: String,
    val positions: List<ReferenceCountPosition>, val snapshotHash: String, val loadedAt: Instant)
data class ReferenceCountInput(val snapshotId: UUID, val physicalBase: String,
    val serials: List<ReceiptSerialInput> = emptyList(), val reason: String)
data class ReferenceCountView(val id: UUID, val snapshot: ReferenceCountSnapshot, val physicalBase: String,
    val differenceBase: String, val serials: List<ReceiptSerialInput>, val reason: String, val actorId: UUID,
    val actorName: String, val recordedAt: Instant, val movementIds: List<UUID>)
data class ReferenceCountMovementInput(val countId: UUID, val direction: String, val locationId: UUID,
    val skuId: UUID, val lines: List<ReferenceCountSelection>, val notes: String)
data class ReferenceCountSelection(val balanceId: UUID, val stockIdentityId: UUID, val quantityBase: String)
