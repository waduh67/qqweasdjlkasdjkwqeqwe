package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.storage.StoredObject
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

/** Only called while the approval query holds current authority, source-owner and source locks. */
@Component
class WarehouseApprovalEvidence(private val receipts: ReceiptEvidencePersistence, private val receiptFiles: ReceiptEvidenceService,
    private val workOrders: AssetHandoverWorkOrderPort) {
    private val mapper = jacksonObjectMapper()
    fun list(record: WarehouseApprovalRecord, page: WarehousePageRequest): WarehousePage<WarehouseApprovalAttachment> {
        val source = mapper.readTree(record.snapshot.source)
        intake(source)?.let { return receipts.forApproval(record.snapshot.evaluation.sourceDocumentId, it, record.requestedAt, page) }
        val signature = signature(source)
        val items = if (signature != null && page.page == 0) listOf(WarehouseApprovalAttachment(signature.id, "SIGNATURE", signature.recordedAt,
            signerLabel = signature.signer)) else emptyList()
        return WarehousePage(items, page.page, page.size, if (signature == null) 0 else 1)
    }
    fun download(record: WarehouseApprovalRecord, evidenceId: UUID, authority: AuthorityFence): StoredObject {
        val source = mapper.readTree(record.snapshot.source)
        intake(source)?.let { binding ->
            val document = record.snapshot.evaluation.sourceDocumentId
            receipts.requireApprovalBinding(document, evidenceId, binding, record.requestedAt)
            return receiptFiles.verified(receipts.get(document, evidenceId))
        }
        val signature = signature(source)?.takeIf { it.id == evidenceId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        val content = workOrders.signatureForApproval(signature.workOrderId, signature.id, signature.digest, authority)
        return StoredObject(content.contentType, content.bytes)
    }
    private fun intake(source: JsonNode): ReceiptIntakeBinding? {
        if (source.path("document").path("kind").asString() != "RECEIPT") return null
        val intake = source.path("intake")
        if (intake.isNull || intake.isMissingNode) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return ReceiptIntakeBinding(intake.path("content_revision").asLong(), intake.path("content_hash").asString())
    }
    private data class Signature(val id: UUID, val workOrderId: UUID, val digest: String, val signer: String, val recordedAt: Instant)
    private fun signature(source: JsonNode): Signature? {
        val (record, evidenceKey, ownerKey) = when (source.path("document").path("kind").asString()) {
            "RETURN_TITLE" -> Triple(source.path("returnTitle"), "signature", "context")
            "TITLE_CORRECTION" -> Triple(source.path("title"), "evidence", "source")
            "ASSET_LOSS" -> Triple(source.path("assetLoss"), "evidence", "ownership")
            else -> return null
        }
        val evidence = record.path(evidenceKey)
        return Signature(UUID.fromString(evidence.path("id").asString()), UUID.fromString(record.path(ownerKey).path("workOrderId").asString()),
            evidence.path("digest").asString(), evidence.path("receiverLabel").asString(), Instant.parse(evidence.path("receivedAt").asString()))
    }
}
