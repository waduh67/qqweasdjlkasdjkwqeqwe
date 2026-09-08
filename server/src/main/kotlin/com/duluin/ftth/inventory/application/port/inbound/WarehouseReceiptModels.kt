package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.inventory.*
import java.time.Instant
import java.util.UUID

enum class ReceiptAction { CREATE, UPDATE, RECEIVE, INSPECT, PUTAWAY }
sealed interface ReceiptInput { val expectedRevision: Long? }
data class ReceiptDraftInput(val supplierId: UUID, val externalReference: String,
    val sourceLocationId: UUID, val inspectionLocationId: UUID, val lines: List<ReceiptLineInput>,
    override val expectedRevision: Long? = null) : ReceiptInput
data class ReceiptLineInput(val skuId: UUID, val quantityBase: String, val serials: List<ReceiptSerialInput> = emptyList(),
    val lotCode: String? = null, val conversion: ReceiptPackageInput? = null, val cost: ReceiptCostInput? = null)
data class ReceiptSerialInput(val serial: String, val mac: String? = null)
data class ReceiptPackageInput(val numerator: String, val denominator: String, val packageQuantity: String)
data class ReceiptCostInput(val totalMinor: String, val currency: String)
data class ReceiptReceiveInput(override val expectedRevision: Long) : ReceiptInput
data class ReceiptInspectInput(override val expectedRevision: Long, val lines: List<ReceiptInspectionInput>) : ReceiptInput
enum class ReceiptRejection { QUARANTINE, SUPPLIER_RETURN }
data class ReceiptInspectionInput(val lineId: UUID, val stockIdentityId: UUID, val baseUnit: WarehouseBaseUnit,
    val acceptedBase: String, val rejectedBase: String, val evidenceId: UUID, val reason: String,
    val rejectedDisposition: ReceiptRejection = ReceiptRejection.QUARANTINE)
data class ReceiptPutawayInput(override val expectedRevision: Long, val destinationLocationId: UUID,
    val lines: List<ReceiptPutawayLine>) : ReceiptInput
data class ReceiptPutawayLine(val lineId: UUID, val stockIdentityId: UUID, val quantityBase: String, val baseUnit: WarehouseBaseUnit)
data class OpeningBalanceInput(val migrationReference: String, val sourceSnapshot: String, val cutoff: Instant,
    val evidenceDocumentId: UUID, val evidenceId: UUID)

data class ReceiptIntake(val supplier: SupplierSnapshot, val externalReference: String,
    val source: LocationSnapshot, val inspection: LocationSnapshot, val lines: List<ReceiptIntakeLine>)
data class ReceiptIntakeLine(val id: UUID, val inputLineNumber: Int, val sku: SkuSnapshot,
    val quantityBase: String, val serial: String?, val mac: String?, val lotCode: String?,
    val conversion: ReceiptPackageInput?, val cost: ReceiptCostSnapshot?)
data class ReceiptRecord(val id: UUID, val revision: Long, val state: WarehouseReceiptState,
    val createdAt: Instant, val intake: ReceiptIntake)
data class ReceiptPiece(val stockIdentityId: UUID, val lotId: UUID?, val quantityBase: String,
    val revision: Long, val disposition: String?, val locationId: UUID, val condition: WarehouseCondition,
    val legalOwner: AssetLegalOwner, val status: String, val custodianId: UUID, val custodianKind: String)
data class ReceiptLineView(val id: UUID, val inputLineNumber: Int, val skuId: UUID, val skuCode: String, val skuName: String,
    val tracking: WarehouseTracking, val baseUnit: WarehouseBaseUnit, val quantityBase: String,
    val serial: String?, val mac: String?, val lotCode: String?, val inspectionRequired: Boolean,
    val conversion: ReceiptPackageInput?, val cost: ReceiptCostSnapshot?, val pieces: List<ReceiptPiece>)
data class ReceiptView(val id: UUID, val revision: Long, val state: WarehouseReceiptState, val createdAt: Instant,
    val supplierId: UUID, val supplierName: String, val externalReference: String,
    val sourceLocationId: UUID, val inspectionLocationId: UUID, val lines: List<ReceiptLineView>)
data class ReceiptHistory(val operationId: UUID, val revision: Long, val action: String, val recordedAt: Instant)
data class ReceiptFilter(val page: Int = 0, val size: Int = 25, val status: WarehouseReceiptState? = null,
    val skuId: UUID? = null, val serial: String? = null, val locationId: UUID? = null,
    val from: Instant? = null, val until: Instant? = null, val sort: String = "createdAt", val direction: String = "desc")
data class ReceiptEvidenceView(val id: UUID, val documentId: UUID, val contentType: String, val sizeBytes: Long, val sha256: String)
