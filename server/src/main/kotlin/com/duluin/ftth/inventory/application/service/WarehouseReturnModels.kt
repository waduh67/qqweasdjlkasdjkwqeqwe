package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import java.util.UUID

data class WarehouseReturnSource(val dimension: PostingDimension, val quantity: Long,
    val unit: WarehouseBaseUnit, val tracking: WarehouseTracking, val balanceRevision: Long,
    val segmentRevision: Long, val serial: String?)
data class WarehouseReturnRecord(val intake: WarehouseReturnIntake, val source: WarehouseReturnSource,
    val sourceLineId: UUID, val view: WarehouseReturnView)
