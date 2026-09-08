package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.inbound.ReceiptEvidenceView
import org.springframework.stereotype.Repository
import java.util.UUID

data class ReceiptIntakeBinding(val contentRevision: Long, val contentHash: String)
data class StoredReceiptEvidence(val view: ReceiptEvidenceView, val objectKey: String, val intakeBinding: ReceiptIntakeBinding?)

@Repository
class ReceiptEvidencePersistence(private val jdbc: WarehouseCommandJdbc) {
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
