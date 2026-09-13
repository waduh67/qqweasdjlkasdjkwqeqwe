package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.MaterialResidualRequest
import com.duluin.ftth.inventory.ResidualPurpose
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import java.time.Instant
import java.util.UUID

data class MaterialResidualSnapshot(
    val id: UUID, val workOrderId: UUID, val lineId: UUID, val request: MaterialResidualRequest,
    val purpose: ResidualPurpose, val source: PostingDimension, val transit: PostingDimension,
    val remainder: PostingDimension?, val sourceRevision: Long, val sourceQuantityBase: Long,
    val receiverId: UUID?, val operationId: UUID, val postingId: UUID, val recordedAt: Instant,
)
