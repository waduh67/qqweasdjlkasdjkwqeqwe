package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import java.time.Instant
import java.util.UUID

data class AssetHandoverPosition(val dimension: PostingDimension, val assetRevision: Long)
data class AssetHandoverRecord(val id: UUID, val code: String, val authorizationId: UUID,
    val assignment: AssetAssignmentRef, val actorId: UUID, val operationId: UUID,
    val operationKey: String, val payloadHash: String, val sourceAssignmentRevision: Long,
    val sourceTitleRevision: Long, val position: AssetHandoverPosition,
    val workOrder: AssetHandoverWorkOrder, val customer: AssetHandoverCustomer,
    val signature: AssetHandoverSignature, val acceptedAt: Instant,
    val authorityEpoch: Long, val cutoverEpoch: Long)
