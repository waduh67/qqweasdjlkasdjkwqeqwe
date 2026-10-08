package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseCommandJdbc
import com.duluin.ftth.inventory.adapter.outbound.persistence.uuid
import com.duluin.ftth.workorder.application.port.inbound.ReferenceWorkOrderPhotoView
import com.duluin.ftth.workorder.domain.model.WorkOrderEvidence
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReferenceWorkOrderEvidenceStore(private val jdbc: WarehouseCommandJdbc) {
    fun save(photo: WorkOrderEvidence, slot: String, generation: Long, revision: Long) = jdbc.execute { sql ->
        sql.update("""INSERT INTO work_order_reference_photo(evidence_id,tenant_id,work_order_id,assignment_generation,
            work_order_revision,slot,uploaded_by,object_key,sha256,size_bytes,content_type,received_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", photo.id, sql.tenant, photo.workOrderId, generation, revision, slot,
            photo.uploadedBy, photo.storageKey, photo.sha256, photo.sizeBytes, photo.contentType, photo.receiptAt)
        Unit
    }
    fun list(id: UUID, generation: Long): List<ReferenceWorkOrderPhotoView> = jdbc.execute { sql ->
        sql.query("""SELECT photo.*,actor.name actor_name,
            photo.assignment_generation=? AND photo.work_order_revision=(SELECT max(latest.work_order_revision)
                FROM work_order_reference_photo latest WHERE latest.tenant_id=photo.tenant_id AND latest.work_order_id=photo.work_order_id
                AND latest.assignment_generation=photo.assignment_generation AND latest.slot=photo.slot) AS current
            FROM work_order_reference_photo photo JOIN app_user actor ON actor.tenant_id=photo.tenant_id AND actor.id=photo.uploaded_by
            WHERE photo.tenant_id=? AND photo.work_order_id=? ORDER BY photo.work_order_revision""", generation, sql.tenant, id) {
            ReferenceWorkOrderPhotoView(it.uuid("evidence_id"), it.getString("slot"), it.getLong("assignment_generation"),
                it.uuid("uploaded_by"), it.getString("actor_name"), it.getString("content_type"), it.getLong("size_bytes"),
                it.getString("sha256"), it.getTimestamp("received_at").toInstant(), it.getBoolean("current"))
        }
    }
}
