package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.MaterialReceiptSelection
import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.application.port.outbound.PostingLeg
import com.duluin.ftth.inventory.application.port.outbound.PostingSplit
import java.time.Instant
import java.util.UUID

data class MaterialReceiptLineSnapshot(
    val selection: MaterialReceiptSelection,
    val source: PostingDimension,
    val sourceRevision: Long,
    val accepted: PostingDimension?,
    val remainder: PostingDimension?,
    val priorAcceptedBase: String,
    val remainingBase: String,
)

data class MaterialReceiptTotal(val issueLineId: UUID, val baseUnit: WarehouseBaseUnit,
    val dispatchedBase: String, val acceptedBase: String, val inTransitBase: String)

data class MaterialReceiptSnapshot(
    val receiptId: UUID,
    val issueId: UUID,
    val revision: Long,
    val state: String,
    val receiver: IssuePerson,
    val evidenceReference: String,
    val recordedAt: Instant,
    val postingId: UUID,
    val issue: IssueSnapshot,
    val lines: List<MaterialReceiptLineSnapshot>,
    val totals: List<MaterialReceiptTotal>,
)

data class PreparedMaterialReceipt(val line: MaterialReceiptLineSnapshot, val legs: List<PostingLeg>, val split: PostingSplit?)
