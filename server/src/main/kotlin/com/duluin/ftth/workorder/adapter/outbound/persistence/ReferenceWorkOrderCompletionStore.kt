package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseCommandJdbc
import com.duluin.ftth.inventory.application.service.TransferLine
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import com.duluin.ftth.workorder.application.port.inbound.ReferenceWorkOrderCompletionView
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
    fun consumedLocation(): UUID = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|reference-consumed-location")
        sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND code='CONSUMED' AND kind='TRANSIT' AND state='ACTIVE'", sql.tenant)
            ?.let(UUID::fromString) ?: UUID.randomUUID().also { id ->
                sql.update("INSERT INTO inventory_location(id,tenant_id,code,name,kind) VALUES (?,?,'CONSUMED','Material terpakai','TRANSIT')", id, sql.tenant)
            }
    }
    fun draft(id: UUID, workOrder: UUID, customer: UUID?, destination: UUID, lines: List<TransferLine>,
        actor: UUID, authority: Long, epoch: Long, notes: String) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,work_order_id,customer_id,actor_id,cutover_epoch,authority_epoch,reason)
            VALUES (?,?,?,'USAGE',?,?,?,?,?,?)""", id, sql.tenant, "USE-$id", workOrder, customer, actor, epoch, authority, notes)
        lines.forEachIndexed { index, line ->
            sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,stock_identity_id,lot_id,
                base_unit,tracking,quantity_base,location_id,destination_location_id,custodian_id,custodian_kind,condition,legal_owner)
                VALUES (?,?,?,0,?,?,?,?,?,?,?,?,?,?,'TECHNICIAN','SERVICEABLE','ISP')""", line.id, sql.tenant, id, index + 1,
                line.source.dimension.skuId, line.source.dimension.stockIdentityId, line.source.dimension.lotId, line.source.unit,
                line.source.tracking, line.quantity, line.source.dimension.locationId, destination, actor)
        }
        Unit
    }
}
