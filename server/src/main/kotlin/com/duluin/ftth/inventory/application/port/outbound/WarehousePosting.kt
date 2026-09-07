package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.AssetLegalOwner
import com.duluin.ftth.inventory.TenantCutoverFence
import com.duluin.ftth.inventory.WarehouseCondition
import com.duluin.ftth.inventory.WarehouseEventKind
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.LegDirection
import com.duluin.ftth.inventory.domain.model.MovementKind
import com.duluin.ftth.inventory.domain.model.OwnerKind
import com.duluin.ftth.inventory.domain.model.StockQuantity
import java.time.Instant
import java.util.UUID

interface WarehousePosting {
    fun post(command: WarehousePost, cutover: TenantCutoverFence): WarehousePostResult
    fun rebuild(expectedCutoverEpoch: Long): List<PostingBalance>
}

data class PostingDimension(
    val skuId: UUID,
    val stockIdentityId: UUID,
    val lotId: UUID?,
    val locationId: UUID,
    val custodianId: UUID,
    val custodianKind: OwnerKind,
    val condition: WarehouseCondition,
    val legalOwner: AssetLegalOwner,
) {
    fun orderKey(): String = listOf(skuId, stockIdentityId, lotId, locationId, custodianId, custodianKind, condition, legalOwner)
        .joinToString("|") { it?.toString().orEmpty() }
}

enum class PostingEndpoint { PHYSICAL, RECEIPT_SOURCE, CONSUMED }

data class PostingLeg(
    val direction: LegDirection,
    val dimension: PostingDimension,
    val quantity: StockQuantity,
    val documentLineId: UUID,
    val status: InventoryStatus,
    val endpoint: PostingEndpoint = PostingEndpoint.PHYSICAL,
)

data class PostingOperation(
    val id: UUID,
    val namespace: String,
    val key: String,
    val actorId: UUID,
    val resourceId: UUID,
    val resourceScope: String,
    val payloadHash: String,
    val businessAction: String,
    val originalStatus: Int,
    val originalBody: String,
    val authorityEpoch: Long,
)

data class ReservationChange(
    val id: UUID,
    val documentLineId: UUID,
    val dimension: PostingDimension,
    val expectedRevision: Long?,
    val unpicked: StockQuantity,
    val picked: StockQuantity,
    val expiresAt: Instant,
    val state: ReservationState = ReservationState.OPEN,
)

enum class ReservationState { OPEN, DISPATCHED, RELEASED, EXPIRED }

data class SegmentChild(val id: UUID, val quantity: StockQuantity, val kind: SegmentKind)
enum class SegmentKind { CUT, REMNANT, BULK }
data class PostingSplit(val parentId: UUID, val expectedRevision: Long, val children: List<SegmentChild>)

data class PostingMaterialFact(
    val id: UUID,
    val stockIdentityId: UUID,
    val customerId: UUID,
    val workOrderId: UUID,
    val itemCategory: String,
    val quantity: StockQuantity,
    val useRevision: Long,
    val installed: Boolean,
    val returned: Boolean,
    val fulfillmentTargetId: UUID? = null,
    val compensatesFactId: UUID? = null,
)

data class PostingUsage(
    val id: UUID,
    val workOrderId: UUID,
    val workOrderRevision: Long,
    val planId: UUID,
    val useRevision: Long,
    val frozenSnapshot: String,
    val compensatesSnapshotId: UUID? = null,
)

data class PostingEvent(val id: UUID, val kind: WarehouseEventKind, val payload: String)

data class WarehousePost(
    val documentId: UUID,
    val expectedRevision: Long,
    val nextState: String,
    val operation: PostingOperation,
    val kind: MovementKind,
    val reason: String,
    val legs: List<PostingLeg>,
    val reservations: List<ReservationChange> = emptyList(),
    val splits: List<PostingSplit> = emptyList(),
    val facts: List<PostingMaterialFact> = emptyList(),
    val usage: PostingUsage? = null,
    val events: List<PostingEvent> = emptyList(),
    val compensatesPostingId: UUID? = null,
)

data class WarehousePostResult(val postingId: UUID, val operationId: UUID, val documentRevision: Long, val recordedAt: Instant)
data class PostingBalance(val dimension: PostingDimension, val quantity: StockQuantity, val status: InventoryStatus)
enum class PostingPhase { DOCUMENT, HEADER, LEGS, BALANCES, RESERVATIONS, CUSTODY, FACTS, EVENTS }
data class PostingPhaseReached(val postingId: UUID, val phase: PostingPhase)
