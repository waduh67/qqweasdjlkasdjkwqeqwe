package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehouseTracking
import java.time.Instant
import java.util.UUID

data class ReferenceActivationInput(val expectedEpoch: Long, val reviewHash: String, val reason: String)
data class ReferenceDrainInput(val expectedEpoch: Long)
data class ReferenceReceiptInput(val warehouseId: UUID, val lines: List<ReceiptLineInput>,
    val notes: String = "", val supplierId: UUID? = null, val reference: String? = null)
data class ReferenceTransferInput(val sourceWarehouseId: UUID, val warehouseId: UUID,
    val lines: List<ReferenceTransferLine>, val notes: String = "")
data class ReferenceTransferLine(val stockIdentityId: UUID, val quantityBase: String)
data class ReferenceTechnicianHandoverInput(val sourceWarehouseId: UUID, val technicianId: UUID, val skuId: UUID,
    val lines: List<ReferenceTransferLine>, val notes: String = "")
data class ReferenceMovementView(val id: UUID, val operationId: UUID, val revision: Long, val kind: String,
    val state: String, val warehouseId: UUID, val sourceWarehouseId: UUID?, val notes: String, val recordedAt: Instant,
    val technicianId: UUID? = null)
data class ReferenceStockPosition(val stockIdentityId: UUID, val skuId: UUID, val skuCode: String, val skuName: String,
    val tracking: WarehouseTracking, val baseUnit: WarehouseBaseUnit, val quantityBase: String,
    val locationId: UUID, val locationName: String, val holderId: UUID, val holderName: String, val holderEmail: String?,
    val holderKind: String, val status: String, val serial: String?, val mac: String?, val revision: Long)
data class ReferenceWarehouseQuantity(val warehouseId: UUID, val warehouseName: String, val quantityBase: String)
data class ReferenceSkuStock(val sku: SkuSnapshot, val warehouses: List<ReferenceWarehouseQuantity>,
    val positions: List<ReferenceStockPosition>)
data class ReferenceStockHistory(val operationId: UUID, val documentId: UUID, val kind: String,
    val recordedAt: Instant, val actorName: String, val notes: String, val locationId: UUID,
    val locationName: String, val holderName: String, val direction: String, val quantityBase: String,
    val baseUnit: WarehouseBaseUnit, val serial: String?, val mac: String?)
