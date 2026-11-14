package com.karuhun.ftth.inventory.application.port.inbound

import com.karuhun.ftth.inventory.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface WarehouseClientDocument { val id: UUID; val revision: Long; val warehouseId: UUID; val createdAt: Instant; val updatedAt: Instant }
data class WarehouseClientEvent(val id: UUID,val revision: Long,val action: String,val actorName: String,val notes: String,val movementIds: List<UUID>,val recordedAt: Instant)
data class WarehouseClientDetail<T>(val document: T,val timeline: List<WarehouseClientEvent>)
data class WarehouseClientRevision(val expectedRevision: Long,val notes: String = "")
data class WarehousePurchaseInput(val warehouseId: UUID,val supplierId: UUID,val purchaseDate: LocalDate,val reference: String,
    val lines: List<WarehousePurchaseLineInput>,val requestId: UUID? = null,val notes: String = "")
data class WarehousePurchaseLineInput(val skuId: UUID,val baseUnit: WarehouseBaseUnit,val quantityBase: String,
    val requestLineId: UUID? = null,val unitPriceMinor: String? = null)
data class WarehousePurchaseLine(val id: UUID,val skuId: UUID,val skuName: String,val baseUnit: WarehouseBaseUnit,
    val quantityBase: String,val receivedBase: String = "0",val requestLineId: UUID? = null,val unitPriceMinor: String? = null)
data class WarehousePurchaseView(override val id: UUID,override val revision: Long,override val warehouseId: UUID,val warehouseName: String,
    val supplierId: UUID,val supplierName: String,val purchaseDate: LocalDate,val reference: String,val requestId: UUID?,
    val state: String,val notes: String,val lines: List<WarehousePurchaseLine>,override val createdAt: Instant,override val updatedAt: Instant) : WarehouseClientDocument
data class WarehousePurchaseReceipt(val expectedRevision: Long,val lineId: UUID,val quantityBase: String,
    val serials: List<ReceiptSerialInput> = emptyList(),val lotCode: String? = null,val cost: ReceiptCostInput? = null,val notes: String = "")

data class WarehouseLoanInput(val warehouseId: UUID,val borrowerName: String,val contact: String = "",val loanDate: LocalDate,
    val dueDate: LocalDate? = null,val notes: String = "",val lines: List<WarehouseLoanLineInput>)
data class WarehouseLoanLineInput(val skuId: UUID,val baseUnit: WarehouseBaseUnit,val quantityBase: String)
data class WarehouseLoanStock(val stockIdentityId: UUID,val quantityBase: String,val returnedBase: String = "0",val serial: String? = null)
data class WarehouseLoanLine(val id: UUID,val skuId: UUID,val skuName: String,val baseUnit: WarehouseBaseUnit,val tracking: WarehouseTracking,
    val quantityBase: String,val approvedBase: String = "0",val stocks: List<WarehouseLoanStock> = emptyList())
data class WarehouseLoanView(override val id: UUID,override val revision: Long,override val warehouseId: UUID,val warehouseName: String,
    val borrowerName: String,val contact: String,val loanDate: LocalDate,val dueDate: LocalDate?,val notes: String,val state: String,
    val requiresManagerApproval: Boolean,val lines: List<WarehouseLoanLine>,val externalLocationId: UUID? = null,
    override val createdAt: Instant,override val updatedAt: Instant,val issueEvidence: String = "",val issuedBy: String = "",val policyRevision: Long = 0) : WarehouseClientDocument
data class WarehouseLoanReview(val expectedRevision: Long,val lines: List<ReferenceRequestReviewLine>,val notes: String = "")
data class WarehouseLoanIssue(val expectedRevision: Long,val lines: List<WarehouseLoanIssueLine>,val evidence: String)
data class WarehouseLoanIssueLine(val lineId: UUID,val stocks: List<ReferenceTransferLine>)
data class WarehouseLoanReturn(val expectedRevision: Long,val lines: List<WarehouseLoanReturnLine>,val evidence: String)
data class WarehouseLoanReturnLine(val lineId: UUID,val stockIdentityId: UUID,val quantityBase: String,val condition: WarehouseCondition,val observedSerial: String? = null)

data class WarehouseWarrantyView(override val id: UUID,override val revision: Long,override val warehouseId: UUID,val warehouseName: String,
    val loanId: UUID,val borrowerName: String,val skuId: UUID,val skuName: String,val baseUnit: WarehouseBaseUnit,val tracking: WarehouseTracking,
    val stockIdentityId: UUID,val quantityBase: String,val serial: String?,val locationId: UUID,val state: String,val result: String? = null,
    val supplierId: UUID? = null,val supplierName: String? = null,val vendorReference: String = "",val evidence: String,
    val sentAt: Instant? = null,val returnedAt: Instant? = null,val replacementAssetId: UUID? = null,
    override val createdAt: Instant,override val updatedAt: Instant) : WarehouseClientDocument
data class WarehouseWarrantyDispatch(val expectedRevision: Long,val supplierId: UUID,val vendorReference: String,val evidence: String)
data class WarehouseWarrantyReceipt(val expectedRevision: Long,val result: String,val evidence: String,val observedSerial: String? = null,
    val replacementSerial: String? = null,val replacementMac: String? = null)
data class WarehouseWarrantyInspection(val expectedRevision: Long,val serviceable: Boolean,val resetConfirmed: Boolean = false,val evidence: String)
