package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import java.time.Instant
import java.util.UUID

data class ReturnTitleContext(val assignmentId: UUID, val customerId: UUID, val workOrderId: UUID,
    val handoverId: UUID, val handoverActorId: UUID, val removalActorId: UUID, val assignmentRevision: Long)
data class ReturnTitleRecord(val id: UUID, val code: String, val returned: WarehouseReturnRecord,
    val context: ReturnTitleContext, val source: WarehouseReturnSource, val assetRevision: Long,
    val request: ReturnReacquisitionInput, val signature: AssetHandoverSignature,
    val actorId: UUID, val workOrderRevision: Long, val authorityEpoch: Long, val cutoverEpoch: Long,
    val recordedAt: Instant) {
    fun reference() = ReturnReacquisitionRef(id, returned.view.id)
}
