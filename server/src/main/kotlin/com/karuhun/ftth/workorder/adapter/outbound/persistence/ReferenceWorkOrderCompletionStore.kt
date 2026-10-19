package com.karuhun.ftth.workorder.adapter.outbound.persistence

import com.karuhun.ftth.common.infrastructure.persistence.WarehouseCommandJdbc
import com.karuhun.ftth.common.infrastructure.web.WarehouseCanonicalPayload
import com.karuhun.ftth.workorder.application.port.inbound.ReferenceWorkOrderCompletionView
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class ReferenceWorkOrderCompletionStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun get(id: UUID): ReferenceWorkOrderCompletionView? = jdbc.execute { sql ->
        sql.value("SELECT proof_canonical FROM work_order_reference_completion WHERE tenant_id=? AND work_order_id=?", sql.tenant, id)
            ?.let { mapper.readValue(it, ReferenceWorkOrderCompletionView::class.java) }
    }
    fun save(view: ReferenceWorkOrderCompletionView, proof: WarehouseCanonicalPayload) = jdbc.execute { sql ->
        sql.update("""INSERT INTO work_order_reference_completion(tenant_id,work_order_id,revision,assignment_generation,
            technician_id,document_id,completed_at,proof_canonical,proof_hash) VALUES (?,?,?,?,?,?,?,?,?)""",
            sql.tenant, view.workOrderId, view.revision, view.assignmentGeneration, view.technicianId, view.documentId,
            view.completedAt, proof.json, proof.hash)
        Unit
    }
}
