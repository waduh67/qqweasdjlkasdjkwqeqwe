package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.inbound.ReceiptEvidenceView
import org.springframework.stereotype.Repository
import java.util.UUID

data class StoredReceiptEvidence(val view: ReceiptEvidenceView, val objectKey: String)

@Repository
class ReceiptEvidencePersistence(private val jdbc: WarehouseCommandJdbc) {
    fun get(document: UUID, id: UUID): StoredReceiptEvidence = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_receipt_evidence WHERE tenant_id=? AND document_id=? AND id=?", sql.tenant, document, id) {
            StoredReceiptEvidence(ReceiptEvidenceView(id, document, it.getString("content_type"), it.getLong("size_bytes"), it.getString("sha256")), it.getString("object_key"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun save(evidence: StoredReceiptEvidence, actor: UUID) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_receipt_evidence(id,tenant_id,document_id,object_key,sha256,content_type,size_bytes,actor_id)
            VALUES (?,?,?,?,?,?,?,?)""", evidence.view.id, sql.tenant, evidence.view.documentId, evidence.objectKey,
            evidence.view.sha256, evidence.view.contentType, evidence.view.sizeBytes, actor)
    }
}
