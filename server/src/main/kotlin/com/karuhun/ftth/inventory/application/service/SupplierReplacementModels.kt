package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.inventory.*
import com.karuhun.ftth.inventory.application.port.inbound.ReceiptRecord
import java.time.Instant
import java.util.UUID

data class SupplierReplacementContext(val assignmentId: UUID, val customerId: UUID, val workOrderId: UUID,
    val assetRevision: Long)
data class SupplierReplacementRecord(val view: SupplierReplacementView, val returned: WarehouseReturnRecord,
    val context: SupplierReplacementContext, val position: WarehouseReturnSource, val receipt: ReceiptRecord, val input: SupplierReplacementInput,
    val actorId: UUID, val workOrderRevision: Long, val authorityEpoch: Long, val cutoverEpoch: Long, val recordedAt: Instant)
