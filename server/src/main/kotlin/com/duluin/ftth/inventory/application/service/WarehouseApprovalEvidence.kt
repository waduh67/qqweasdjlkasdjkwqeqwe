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
    private val workOrders: AssetHandoverWorkOrderPort, private val migrationFiles: MigrationEvidenceStore,
    private val migrationStorage: MigrationEvidenceService) {
    private val mapper = jacksonObjectMapper()
    fun list(record: WarehouseApprovalRecord, page: WarehousePageRequest): WarehousePage<WarehouseApprovalAttachment> {
        val source = mapper.readTree(record.snapshot.source)
        opening(source)?.let { opening ->
            val references = opening.manifest.cases.flatMap { sourceCase -> sourceCase.resolution?.evidence.orEmpty().map { sourceCase.caseId to it } }
                .distinctBy { it.second.id }
            val offset = page.page.toLong() * page.size
            val selected = if (offset >= references.size) emptyList() else references.drop(offset.toInt()).take(page.size)
            return WarehousePage(selected.map { (case, reference) ->
                val file = migrationFile(opening.batchId, case, reference).view
                WarehouseApprovalAttachment(file.id, "MIGRATION_EVIDENCE", file.createdAt, file.contentType, file.sizeBytes,
                    label = file.label, sha256 = file.sha256, caseId = case)
            }, page.page, page.size, references.size.toLong())
        }
        intake(source)?.let { return receipts.forApproval(record.snapshot.evaluation.sourceDocumentId, it, record.requestedAt, page) }
        val signature = signature(source)
        val items = if (signature != null && page.page == 0) listOf(WarehouseApprovalAttachment(signature.id, "SIGNATURE", signature.recordedAt,
            signerLabel = signature.signer)) else emptyList()
        return WarehousePage(items, page.page, page.size, if (signature == null) 0 else 1)
    }
    fun download(record: WarehouseApprovalRecord, evidenceId: UUID, authority: AuthorityFence): StoredObject {
        val source = mapper.readTree(record.snapshot.source)
        opening(source)?.let { opening ->
            val selected = opening.manifest.cases.firstNotNullOfOrNull { sourceCase ->
                sourceCase.resolution?.evidence?.singleOrNull { it.id == evidenceId }?.let { sourceCase.caseId to it }
            } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
            return migrationStorage.verified(migrationFile(opening.batchId, selected.first, selected.second))
        }
        intake(source)?.let { binding ->
            val document = record.snapshot.evaluation.sourceDocumentId
            receipts.requireApprovalBinding(document, evidenceId, binding, record.requestedAt)
            return receiptFiles.verified(receipts.get(document, evidenceId))
        }
        val signature = signature(source)?.takeIf { it.id == evidenceId } ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
        val content = workOrders.signatureForApproval(signature.workOrderId, signature.id, signature.digest, authority)
        return StoredObject(content.contentType, content.bytes)
    }
    private fun opening(source: JsonNode): WarehouseMigrationOpening? = if (source.path("document").path("kind").asString() == "OPENING_BALANCE")
        mapper.treeToValue(source.path("opening"), WarehouseMigrationOpening::class.java) else null
    private fun migrationFile(batch: UUID, case: UUID, reference: MigrationEvidenceReference): StoredMigrationEvidence {
        val file = migrationFiles.get(batch, case, reference.id)
        if (file.view.sourceHash != reference.sourceHash || file.view.sha256 != reference.sha256 || file.view.uploadedBy != reference.uploadedBy)
            masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return file
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
