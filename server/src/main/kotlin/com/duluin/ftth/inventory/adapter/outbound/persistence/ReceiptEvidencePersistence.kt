package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.inbound.ReceiptEvidenceView
import com.duluin.ftth.inventory.application.port.inbound.ReceiptEvidenceListItem
import com.duluin.ftth.inventory.WarehousePage
import org.springframework.stereotype.Repository
import java.util.UUID
import java.time.Instant
import com.duluin.ftth.inventory.WarehouseApprovalAttachment
import com.duluin.ftth.inventory.WarehousePageRequest

data class ReceiptIntakeBinding(val contentRevision: Long, val contentHash: String)
data class StoredReceiptEvidence(val view: ReceiptEvidenceView, val objectKey: String, val intakeBinding: ReceiptIntakeBinding?)

@Repository
class ReceiptEvidencePersistence(private val jdbc: WarehouseCommandJdbc) {
    /** The approval captured this exact intake before requestedAt; later uploads are not evidence for that request. */
    fun forApproval(document: UUID, binding: ReceiptIntakeBinding, requestedAt: Instant, page: WarehousePageRequest): WarehousePage<WarehouseApprovalAttachment> = jdbc.execute { sql ->
        val where = "tenant_id=? AND document_id=? AND intake_content_revision=? AND intake_hash=? AND created_at<=?"
        val total = requireNotNull(sql.value("SELECT count(*) FROM inventory_receipt_evidence WHERE $where", sql.tenant, document,
            binding.contentRevision, binding.contentHash, requestedAt)).toLong()
        val items = sql.query("SELECT id,content_type,size_bytes,created_at FROM inventory_receipt_evidence WHERE $where ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
            sql.tenant, document, binding.contentRevision, binding.contentHash, requestedAt, page.size, page.page.toLong() * page.size) {
            WarehouseApprovalAttachment(it.uuid("id"), "RECEIPT", it.getTimestamp("created_at").toInstant(), it.getString("content_type"), it.getLong("size_bytes"))
        }
        WarehousePage(items, page.page, page.size, total)
    }
    fun requireApprovalBinding(document: UUID, id: UUID, binding: ReceiptIntakeBinding, requestedAt: Instant) = jdbc.execute { sql ->
        if (sql.value("""SELECT id FROM inventory_receipt_evidence WHERE tenant_id=? AND document_id=? AND id=?
            AND intake_content_revision=? AND intake_hash=? AND created_at<=? FOR SHARE""", sql.tenant, document, id,
                binding.contentRevision, binding.contentHash, requestedAt) == null) sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun list(document: UUID, page: Int, size: Int): WarehousePage<ReceiptEvidenceListItem> = jdbc.execute { sql ->
        val total = requireNotNull(sql.value("SELECT count(*) FROM inventory_receipt_evidence WHERE tenant_id=? AND document_id=?", sql.tenant, document)).toLong()
        val items = sql.query("""SELECT evidence.id,evidence.document_id,evidence.content_type,evidence.size_bytes,evidence.sha256,evidence.created_at,
                coalesce(evidence.intake_content_revision=intake.content_revision AND evidence.intake_hash=intake.content_hash,false) matches_current_intake
            FROM inventory_receipt_evidence evidence JOIN inventory_receipt_intake intake
                ON intake.tenant_id=evidence.tenant_id AND intake.id=evidence.document_id
            WHERE evidence.tenant_id=? AND evidence.document_id=? ORDER BY evidence.created_at DESC,evidence.id
            LIMIT ? OFFSET ?""", sql.tenant, document, size, page.toLong() * size) {
            ReceiptEvidenceListItem(it.uuid("id"), it.uuid("document_id"), it.getString("content_type"), it.getLong("size_bytes"),
                it.getString("sha256"), it.getTimestamp("created_at").toInstant(), it.getBoolean("matches_current_intake"))
        }
        WarehousePage(items, page, size, total)
    }
    internal fun settledObjectKey(document: UUID, id: UUID): String? = jdbc.execute { sql ->
        sql.update("SET LOCAL lock_timeout='2s'")
        sql.update("SET LOCAL statement_timeout='5s'")
        if (sql.value("SELECT id FROM inventory_document WHERE tenant_id=? AND id=? FOR NO KEY UPDATE", sql.tenant, document) == null)
            sql.fail(WarehouseErrorCode.NOT_FOUND)
        sql.value("SELECT object_key FROM inventory_receipt_evidence WHERE tenant_id=? AND document_id=? AND id=?", sql.tenant, document, id)
    }
    fun currentBinding(document: UUID): ReceiptIntakeBinding = jdbc.execute { sql ->
        sql.query("SELECT content_revision,content_hash FROM inventory_receipt_intake WHERE tenant_id=? AND id=?", sql.tenant, document) {
            ReceiptIntakeBinding(it.getLong("content_revision"), it.getString("content_hash"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun get(document: UUID, id: UUID): StoredReceiptEvidence = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_receipt_evidence WHERE tenant_id=? AND document_id=? AND id=?", sql.tenant, document, id) {
            val binding = it.getString("intake_hash")?.let { hash -> ReceiptIntakeBinding(it.getLong("intake_content_revision"), hash) }
            StoredReceiptEvidence(ReceiptEvidenceView(id, document, it.getString("content_type"), it.getLong("size_bytes"), it.getString("sha256")), it.getString("object_key"), binding)
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun save(evidence: StoredReceiptEvidence, actor: UUID) = jdbc.execute { sql ->
        val binding = requireNotNull(evidence.intakeBinding)
        sql.update("""INSERT INTO inventory_receipt_evidence(id,tenant_id,document_id,object_key,sha256,content_type,size_bytes,actor_id,intake_content_revision,intake_hash)
            VALUES (?,?,?,?,?,?,?,?,?,?)""", evidence.view.id, sql.tenant, evidence.view.documentId, evidence.objectKey,
            evidence.view.sha256, evidence.view.contentType, evidence.view.sizeBytes, actor, binding.contentRevision, binding.contentHash)
    }
}
