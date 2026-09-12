package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.MaterialMode
import com.duluin.ftth.inventory.MaterialUsageSelection
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.application.port.outbound.PostingLeg
import com.duluin.ftth.inventory.application.port.outbound.PostingMaterialFact
import com.duluin.ftth.inventory.application.port.outbound.PostingSplit
import java.time.Instant
import java.util.UUID

data class MaterialUsageLineSnapshot(
    val id: UUID, val selection: MaterialUsageSelection, val receiptRevision: Long,
    val issueId: UUID, val planLineId: UUID, val requestedBase: String, val acknowledgedBase: String,
    val residualBase: String, val source: PostingDimension, val sourceRevision: Long,
    val consumed: PostingDimension, val remainder: PostingDimension?, val factId: UUID,
)

data class MaterialUsageSnapshot(
    val usageId: UUID, val workOrderId: UUID, val workOrderRevision: Long, val planId: UUID,
    val planRevision: Long, val useRevision: Long, val materialMode: MaterialMode,
    val actorId: UUID, val customerId: UUID?, val evidenceReference: String, val reason: String?,
    val networkReferenceLabel: String?, val recordedAt: Instant, val postingId: UUID?,
    val lines: List<MaterialUsageLineSnapshot>,
)

data class PreparedMaterialUsage(
    val line: MaterialUsageLineSnapshot, val legs: List<PostingLeg>, val split: PostingSplit?, val fact: PostingMaterialFact,
)
