package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import java.time.Instant
import java.util.UUID

data class TransferStock(val dimension: PostingDimension, val quantity: Long, val unit: WarehouseBaseUnit,
    val tracking: WarehouseTracking, val status: InventoryStatus, val revision: Long, val cost: ReceiptCostSnapshot?)
data class TransferLine(val id: UUID, val source: TransferStock, val quantity: Long,
    val received: Long = 0, val remainingIdentity: UUID? = source.dimension.stockIdentityId)
data class TransferRecord(val id: UUID, val code: String, val revision: Long, val state: WarehouseTransferState,
    val binding: WarehouseTransferDraft, val sender: UUID, val recordedAt: Instant, val lines: List<TransferLine>) {
    fun view(): WarehouseTransferView = WarehouseTransferView(id, code, revision, state, binding.sourceLocationId,
        binding.destinationLocationId, binding.transitLocationId, sender, binding.receiverId, binding.reason, recordedAt,
        lines.map { line -> WarehouseTransferLineView(line.id, line.source.dimension.skuId,
            line.source.dimension.stockIdentityId, line.source.unit, line.quantity.toString(), line.received.toString(),
            (if (state == WarehouseTransferState.DRAFT) 0 else line.quantity - line.received).toString(),
            line.remainingIdentity, line.source.dimension.condition, line.source.dimension.legalOwner) })
}

internal fun transferQuantity(value: String): Long {
    if (!value.matches(Regex("[1-9][0-9]{0,18}"))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    return value.toLongOrNull() ?: masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
}
