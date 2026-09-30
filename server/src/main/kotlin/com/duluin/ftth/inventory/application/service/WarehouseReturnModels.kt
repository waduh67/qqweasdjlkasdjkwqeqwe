package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.common.domain.identity.SerialIdentity
import java.util.UUID

data class WarehouseReturnSource(val dimension: PostingDimension, val quantity: Long,
    val unit: WarehouseBaseUnit, val tracking: WarehouseTracking, val balanceRevision: Long,
    val segmentRevision: Long, val serial: String?)
data class WarehouseReturnRecord(val intake: WarehouseReturnIntake, val source: WarehouseReturnSource,
    val sourceLineId: UUID, val view: WarehouseReturnView)

/** Compare physical identity without rewriting raw receipt history or command bytes. */
internal fun returnSerialMatches(stored: String?, observed: String?): Boolean =
    !stored.isNullOrBlank() && !observed.isNullOrBlank() && SerialIdentity.parse(stored) == SerialIdentity.parse(observed)
