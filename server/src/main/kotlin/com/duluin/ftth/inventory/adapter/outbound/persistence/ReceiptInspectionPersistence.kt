package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.service.ReceiptDecision
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReceiptInspectionPersistence(private val jdbc: WarehouseCommandJdbc) {
    fun save(decisions: List<ReceiptDecision>, operation: UUID, actor: UUID) = jdbc.execute { sql ->
        decisions.forEach { decision ->
            val id = UUID.randomUUID()
            val input = decision.input
            sql.update("""INSERT INTO inventory_inspection(id,tenant_id,document_line_id,inspector_id,accepted_base,rejected_base,base_unit,
                disposition,evidence_reference,reason,operation_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)""", id, sql.tenant, input.lineId, actor,
                input.acceptedBase.toLong(), input.rejectedBase.toLong(), input.baseUnit,
                if (input.rejectedBase.toLong() == 0L) "ACCEPTED" else input.rejectedDisposition.name, input.evidenceId.toString(), input.reason, operation)
            decision.outputs.forEach { (segment, disposition) ->
                sql.update("""INSERT INTO inventory_receipt_disposition(id,tenant_id,inspection_id,source_segment_id,segment_id,
                    quantity_base,base_unit,disposition,evidence_id) VALUES (?,?,?,?,?,?,?,?,?)""", UUID.randomUUID(), sql.tenant, id,
                    input.stockIdentityId, segment.id, segment.quantity.quantityBase, input.baseUnit, disposition, input.evidenceId)
            }
        }
    }
}
