package com.duluin.ftth.inventory

import com.fasterxml.jackson.annotation.JsonInclude
import com.duluin.ftth.common.storage.StoredObject
import java.time.Instant
import java.util.UUID

interface InventoryApprovalQueryApi {
    fun list(filter: WarehouseApprovalFilter): WarehousePage<WarehouseApprovalSummary>
    fun source(id: UUID): WarehouseApprovalSourceView
    fun details(id: UUID): WarehouseApprovalDetails
    fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseApprovalHistoryEntry>
    fun attachments(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseApprovalAttachment>
    fun attachment(id: UUID, evidenceId: UUID): StoredObject
}
data class WarehouseApprovalFilter(val page: Int = 0, val size: Int = 25, val status: WarehouseApprovalStatus? = null,
    val sourceDocumentId: UUID? = null, val query: String? = null, val operation: PolicyOperation? = null,
    val locationId: UUID? = null, val skuId: UUID? = null, val serial: String? = null, val from: Instant? = null, val until: Instant? = null)
data class WarehouseApprovalPerson(val id: UUID, val name: String?)
data class WarehouseApprovalLocation(val id: UUID, val code: String, val name: String?)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WarehouseApprovalLine(val id: UUID, val skuId: UUID, val code: String, val name: String, val tracking: WarehouseTracking,
    val baseUnit: WarehouseBaseUnit, val quantityBase: String?, val serial: String?, val lotCode: String?,
    val locationId: UUID?, val destinationLocationId: UUID?, val condition: WarehouseCondition, val legalOwner: AssetLegalOwner)
data class WarehouseApprovalComparison(val balanceId: UUID, val skuId: UUID, val counter: WarehouseApprovalPerson,
    val baseUnit: WarehouseBaseUnit, val bookQuantityBase: String, val quantityBase: String, val documentReference: String)
data class WarehouseApprovalEvidenceReference(val kind: String, val reference: String)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WarehouseApprovalAttachment(val id: UUID, val kind: String, val recordedAt: Instant,
    val contentType: String? = null, val sizeBytes: Long? = null, val signerLabel: String? = null)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WarehouseApprovalDocument(val id: UUID, val revision: Long, val kind: String, val code: String, val state: String,
    val reason: String?, val createdAt: Instant, val requester: WarehouseApprovalPerson, val locations: List<WarehouseApprovalLocation>,
    val lines: List<WarehouseApprovalLine>, val comparisons: List<WarehouseApprovalComparison>,
    val receiptId: UUID? = null, val countId: UUID? = null, val transferId: UUID? = null, val returnId: UUID? = null,
    val evidenceReferences: List<WarehouseApprovalEvidenceReference> = emptyList())
data class WarehouseApprovalSourceView(val document: WarehouseApprovalDocument, val canRequest: Boolean, val requestBlock: String?)
data class WarehouseApprovalSummary(val approval: WarehouseApprovalView, val documentCode: String, val operation: PolicyOperation,
    val requester: WarehouseApprovalPerson, val requestedAt: Instant)
data class WarehouseApprovalActions(val canDecide: Boolean, val decisionBlock: String?, val canRework: Boolean,
    val reworkSourceRevision: Long, val currentTier: Int?)
data class WarehouseApprovalTierView(val number: Int, val approvers: List<WarehouseApprovalPerson>)
data class WarehouseApprovalPolicyView(val id: UUID, val revision: Long, val tiers: List<WarehouseApprovalTierView>)
data class WarehouseApprovalAmount(val numerator: String, val denominator: String, val currency: String)
data class WarehouseApprovalEffectView(val operationId: UUID, val businessAction: String, val recordedAt: Instant, val movementIds: List<UUID>)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WarehouseApprovalDetails(val approval: WarehouseApprovalView, val document: WarehouseApprovalDocument,
    val currentSourceRevision: Long, val currentSourceState: String, val requestedAt: Instant,
    val policy: WarehouseApprovalPolicyView, val actions: WarehouseApprovalActions,
    val effect: WarehouseApprovalEffectView?, val cost: WarehouseApprovalAmount?)
data class WarehouseApprovalHistoryEntry(val id: UUID, val tier: Int, val approver: WarehouseApprovalPerson, val decision: String,
    val reason: String?, val decidedAt: Instant, val revision: Long, val delegatedFrom: WarehouseApprovalPerson?, val evidenceReference: UUID?)
