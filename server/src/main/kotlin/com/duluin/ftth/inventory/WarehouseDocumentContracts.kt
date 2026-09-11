package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

enum class WarehouseMasterState { ACTIVE, ARCHIVED }
enum class WarehouseReceiptState { DRAFT, RECEIVED_IN_INSPECTION, PUTAWAY, CLOSED }
enum class WarehouseTransferState { DRAFT, DISPATCHED, PART_RECEIVED, RECEIVED, DISCREPANCY }
enum class MaterialDemandState { DRAFT, SUBMITTED, PART_RESERVED, RESERVED, PART_ISSUED, ISSUED, SETTLING, CLOSED, CANCELLED }
enum class WarehouseIssueState { DRAFT, PICKED, UNPICKED, DISPATCHED, PART_RECEIVED, RECEIVED }
enum class WarehouseReturnState { DRAFT, DISPATCHED, RECEIVED_IN_INSPECTION, ACCEPTED, REPAIR, SUPPLIER_RETURN, SCRAP }
enum class WarehouseCountState { DRAFT, COUNTING, SUBMITTED, APPROVED, POSTED, RECOUNT_REQUIRED }
enum class WarehouseDocumentKind { RECEIPT, OPENING_BALANCE, DEMAND, ISSUE, TRANSFER, RETURN, REPAIR, COUNT, LOSS, SCRAP, ADJUSTMENT }
enum class WarehouseEventKind {
    RECEIVED, INSPECTED, PUTAWAY, RESERVED, RELEASED, RESERVATION_EXPIRED, PICKED, UNPICKED,
    DISPATCHED, ACKNOWLEDGED, SEGMENT_SPLIT, USE_POSTED, USE_COMPENSATED, SETTLEMENT_VERIFIED,
    RETURN_RECEIVED, ASSIGNMENT_OPENED, ASSIGNMENT_CLOSED, HANDOVER_ACCEPTED, TITLE_REACQUIRED,
    COUNT_POSTED, DISPOSED, CUTOVER_CHANGED,
}

data class WarehouseMutationMetadata(val idempotencyKey: String)
data class MaterialDocumentRequest(val documentId: UUID, val expectedRevision: Long, val reservation: ReservationRequest? = null)
data class ApprovalSourceRequest(val documentId: UUID, val expectedRevision: Long)

data class WarehouseOperationReceipt(
    val operationId: UUID,
    val documentId: UUID,
    val documentRevision: Long,
    val originalStatus: Int,
    val originalBody: String,
    val recordedAt: Instant,
)

data class WarehouseDocumentEvent(
    val eventId: UUID,
    val tenantId: UUID,
    val operationId: UUID,
    val documentId: UUID,
    val documentRevision: Long,
    val kind: WarehouseEventKind,
    val workOrderId: UUID?,
    val assetId: UUID?,
    val recordedAt: Instant,
)
