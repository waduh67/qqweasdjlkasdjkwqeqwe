package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.inventory.ReceiptCostSnapshot
import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehouseTracking
import java.time.Instant
import java.util.UUID

data class ReferenceMovementSummary(val id: UUID, val code: String, val operationId: UUID, val revision: Long,
    val kind: String, val state: String, val notes: String, val recordedAt: Instant, val actorName: String,
    val warehouseId: UUID, val warehouseName: String, val sourceWarehouseId: UUID?, val sourceWarehouseName: String?,
    val supplierName: String?, val reference: String?, val costVisible: Boolean)
data class ReferenceMovementLineView(val id: UUID, val lineNumber: Int, val skuId: UUID, val skuCode: String,
    val skuName: String, val tracking: WarehouseTracking, val baseUnit: WarehouseBaseUnit, val quantityBase: String,
    val serial: String?, val mac: String?, val lotCode: String?, val conversion: ReceiptPackageInput?, val cost: ReceiptCostSnapshot?)
