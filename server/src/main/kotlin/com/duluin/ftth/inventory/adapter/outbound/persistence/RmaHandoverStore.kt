package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class RmaHandoverStore(private val jdbc: WarehouseCommandJdbc, private val returns: WarehouseReturnStore) {
    private val mapper = jacksonObjectMapper()

    fun origin(record: WarehouseReturnRecord): RmaHandoverOrigin = jdbc.execute { sql ->
        val view = record.view
        val source = returns.position(record.source.dimension.copy(locationId = view.locationId, custodianId = view.locationId,
            custodianKind = OwnerKind.WAREHOUSE, condition = WarehouseCondition.SERVICEABLE))
        sql.query("""SELECT repair.id,repair.revision,removal.assignment_id,removal.customer_id,asset.revision asset_revision
            FROM inventory_repair_case repair JOIN inventory_asset_removal removal
                ON removal.tenant_id=repair.tenant_id AND removal.id=?
            JOIN inventory_serialized_asset asset ON asset.tenant_id=repair.tenant_id AND asset.id=repair.asset_id
            WHERE repair.tenant_id=? AND repair.return_document_id=? AND repair.state='CLOSED'
                AND repair.inspected_revision=? AND repair.legal_owner='CUSTOMER' AND asset.legal_owner='CUSTOMER'
                AND asset.id=? AND asset.status='QUARANTINE' AND asset.condition='SERVICEABLE'
            FOR UPDATE OF repair,asset""", view.sourceDocumentId, sql.tenant, view.id, view.revision, view.stockIdentityId) {
            RmaHandoverOrigin(it.uuid("id"), it.getLong("revision"), it.uuid("assignment_id"), it.uuid("customer_id"),
                it.getLong("asset_revision"), source)
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun create(record: RmaHandoverRecord, epoch: Long, authorityEpoch: Long, workOrderCode: String) = jdbc.execute { sql ->
        val view = record.view
        val source = record.origin.source.dimension
        if (sql.value("SELECT id FROM inventory_rma_handover WHERE tenant_id=? AND return_id=?", sql.tenant, view.returnId) != null)
            sql.fail(WarehouseErrorCode.STALE_REVISION)
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,state,actor_id,customer_id,work_order_id,
            source_document_id,source_revision,cutover_epoch,authority_epoch,source_reference,reason,work_order_code_snapshot)
            VALUES (?,?,?,'RMA_HANDOVER','DRAFT',?,?,?,?,?,?,?,?,?,?)""", view.id, sql.tenant, "RMA-${view.id}", view.createdBy,
            view.customerId, view.workOrderId, view.returnId, record.request.expectedRevision, epoch, authorityEpoch,
            record.request.evidenceReference, "Return inspected customer-owned repair", workOrderCode)
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,
            stock_identity_id,source_line_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner)
            VALUES (?,?,?,0,1,?,?,?,'EA','SERIAL',1,?,?,'WAREHOUSE','SERVICEABLE','CUSTOMER')""",
            view.id, sql.tenant, view.id, source.skuId, source.stockIdentityId, view.returnId, source.locationId, source.locationId)
        sql.update("""INSERT INTO inventory_rma_handover(id,tenant_id,return_id,repair_case_id,original_assignment_id,customer_id,
            work_order_id,technician_id,asset_id,body) VALUES (?,?,?,?,?,?,?,?,?,?)""", view.id, sql.tenant, view.returnId,
            view.repairCaseId, view.originalAssignmentId, view.customerId, view.workOrderId, view.technicianId, view.stockIdentityId,
            mapper.writeValueAsString(record))
        Unit
    }

    fun get(id: UUID, lock: Boolean = false): RmaHandoverRecord = jdbc.execute { sql ->
        sql.query("""SELECT handover.body,operation.original_body FROM inventory_rma_handover handover
            JOIN inventory_document document ON document.tenant_id=handover.tenant_id AND document.id=handover.id
            JOIN inventory_operation operation ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id
                AND operation.document_revision=document.revision
            WHERE handover.tenant_id=? AND handover.id=?${if (lock) " FOR UPDATE OF document" else ""}""", sql.tenant, id) {
            mapper.readValue(it.getString("body"), RmaHandoverRecord::class.java).copy(
                view = mapper.readValue(it.getString("original_body"), CustomerRmaHandover::class.java))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun receive(id: UUID, actor: UUID, request: CustomerRmaReceipt) = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_rma_receipt(tenant_id,handover_id,actor_id,request) VALUES (?,?,?,?::jsonb)",
            sql.tenant, id, actor, mapper.writeValueAsString(request))
        Unit
    }
}
