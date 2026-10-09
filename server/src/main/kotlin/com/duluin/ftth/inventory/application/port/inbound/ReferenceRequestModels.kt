package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehousePage
import java.time.Instant
import java.util.UUID

enum class ReferenceRequestKind { RESTOCK, PROCUREMENT }
enum class ReferenceRequestState { SUBMITTED, MANAGER_REVIEW, APPROVED, REJECTED, PARTIALLY_RECEIVED, RECEIVED, PARTIALLY_FULFILLED, FULFILLED }
data class ReferenceRequestInput(val kind: ReferenceRequestKind, val reason: String, val lines: List<ReferenceRequestLineInput>,
    val warehouseId: UUID? = null, val technicianId: UUID? = null)
data class ReferenceRequestLineInput(val baseUnit: WarehouseBaseUnit, val requestedBase: String,
    val skuId: UUID? = null, val proposedName: String? = null)
data class ReferenceRequestReview(val expectedRevision: Long, val lines: List<ReferenceRequestReviewLine>, val notes: String = "")
data class ReferenceRequestReviewLine(val lineId: UUID, val approvedBase: String, val skuId: UUID? = null)
data class ReferenceRequestDecision(val expectedRevision: Long, val approved: Boolean, val reason: String = "")
data class ReferenceRequestReceipt(val expectedRevision: Long, val lineId: UUID, val warehouseId: UUID,
    val quantityBase: String, val serials: List<ReceiptSerialInput> = emptyList(), val supplierId: UUID? = null,
    val reference: String? = null, val notes: String = "", val lotCode: String? = null,
    val conversion: ReceiptPackageInput? = null, val cost: ReceiptCostInput? = null)
data class ReferenceRequestHandover(val expectedRevision: Long, val lineId: UUID, val warehouseId: UUID,
    val lines: List<ReferenceTransferLine>, val notes: String = "")
data class ReferenceRequestLineView(val id: UUID, val baseUnit: WarehouseBaseUnit, val requestedBase: String,
    val skuId: UUID?, val name: String, val proposedName: String?, val approvedBase: String = "0",
    val receivedBase: String = "0", val fulfilledBase: String = "0")
data class ReferenceRequestView(val id: UUID, val revision: Long, val kind: ReferenceRequestKind, val state: ReferenceRequestState,
    val requesterId: UUID, val requesterName: String, val warehouseId: UUID?, val warehouseName: String?,
    val technicianId: UUID?, val technicianName: String?, val requiresManagerApproval: Boolean, val policyRevision: Long, val reason: String,
    val lines: List<ReferenceRequestLineView>, val createdAt: Instant, val updatedAt: Instant)
data class ReferenceRequestEvent(val operationId: UUID, val revision: Long, val action: String, val actorName: String,
    val notes: String, val movementId: UUID?, val recordedAt: Instant)
data class ReferenceRequestDetail(val request: ReferenceRequestView, val timeline: List<ReferenceRequestEvent>)
data class ReferenceOperationalSettings(val revision: Long = 0, val requireManagerApproval: Boolean = true, val overdueDays: Int = 3)
data class ReferenceOperationalSettingsInput(val expectedRevision: Long, val requireManagerApproval: Boolean, val overdueDays: Int)
data class ReferenceRequestStockPreview(val skuId: UUID, val skuName: String, val baseUnit: WarehouseBaseUnit,
    val totalWarehouseBase: String, val technicianId: UUID?, val technicianName: String?, val technicianQuantityBase: String?,
    val warehouses: WarehousePage<ReferenceWarehouseQuantity>)
