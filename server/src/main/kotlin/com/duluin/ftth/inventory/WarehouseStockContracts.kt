package com.duluin.ftth.inventory

import java.time.Instant
import java.util.UUID

enum class WarehouseTracking { SERIAL, LOT, BULK }
enum class WarehouseBaseUnit { EA, MM }
enum class WarehouseDisplayUnit { EA, M }
enum class WarehouseCondition { SERVICEABLE, QUARANTINE, DAMAGED, SCRAP }
enum class AssetLegalOwner { ISP, CUSTOMER, UNKNOWN }
enum class AssetOwnershipMode { LOAN, SALE }
enum class AssetProvenance { RECEIPT, OPENING_BALANCE, UNKNOWN }

data class WarehouseQuantity(
    val quantityBase: String,
    val baseUnit: WarehouseBaseUnit,
    val displayQuantity: String,
    val displayUnit: WarehouseDisplayUnit,
)

data class WarehouseStockDimension(
    val skuId: UUID,
    val stockIdentityId: UUID,
    val lotId: UUID?,
    val locationId: UUID,
    val custodianId: UUID?,
    val condition: WarehouseCondition,
    val legalOwner: AssetLegalOwner,
)

data class WarehouseStockPosition(
    val dimension: WarehouseStockDimension,
    val quantity: WarehouseQuantity,
    val reservedUnpickedBase: String,
    val reservedPickedBase: String,
    val availableBase: String,
    val revision: Long,
)

data class StockSegmentLineage(
    val parentStockIdentityId: UUID,
    val childStockIdentityId: UUID,
    val quantityBase: String,
    val movementId: UUID,
)

data class ReceiptConversionSnapshot(val numerator: String, val denominator: String)

data class ReceiptCostSnapshot(val totalMinor: String, val currency: String, val costBasisQuantityBase: String)

data class WarehouseCostTotal(
    val currency: String,
    val knownTotalMinor: String,
    val unknownLineCount: Int,
)

data class WarehouseDocumentLabels(val customerLabelSnapshot: String?, val workOrderCodeSnapshot: String?)

data class WarehousePageRequest(val page: Int = 0, val size: Int = 25)
data class WarehousePage<T>(val items: List<T>, val page: Int, val size: Int, val totalElements: Long)

data class WarehouseHistoryEntry(
    val eventId: UUID,
    val documentId: UUID,
    val documentRevision: Long,
    val kind: WarehouseEventKind,
    val recordedAt: Instant,
)
