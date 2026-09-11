package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class IssueStock(val quantity: Long, val revision: Long, val serial: String?, val lot: String?, val location: String)

@Repository
class WarehouseIssueStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun stock(identity: UUID, location: UUID): IssueStock = jdbc.execute { sql ->
        sql.query("""SELECT segment.quantity_base,segment.revision,asset.canonical_serial,lot.code,location.name
            FROM inventory_segment segment LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=segment.tenant_id AND lot.id=segment.lot_id
            JOIN inventory_location location ON location.tenant_id=segment.tenant_id AND location.id=?
            WHERE segment.tenant_id=? AND segment.id=? AND segment.state='ACTIVE' AND segment.warehouse_admission='VERIFIED'""", location, sql.tenant, identity) {
            IssueStock(it.getLong(1), it.getLong(2), it.getString(3), it.getString(4), it.getString(5))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
    fun create(snapshot: IssueSnapshot, context: MaterialPlanningContext) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,work_order_revision,
            plan_revision,work_order_code_snapshot,customer_label_snapshot,cutover_epoch,authority_epoch)
            VALUES (?,?,?,'ISSUE',?,?,?,?,?,?,?,?,?)""", snapshot.issueId, sql.tenant, snapshot.code, snapshot.sender.id,
            snapshot.workOrderId, snapshot.customerId, snapshot.workOrderRevision, snapshot.planRevision,
            snapshot.workOrderCode, snapshot.customerLabelSnapshot, context.cutover.snapshot.epoch, context.authority.epoch)
        snapshot.lines.forEachIndexed { index, line ->
            val dimension = line.dimension
            sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,base_unit,tracking,quantity_base,
                stock_identity_id,lot_id,source_line_id,location_id,custodian_id,custodian_kind,condition,legal_owner)
                VALUES (?,?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?)""", line.id, sql.tenant, snapshot.issueId, index + 1, line.sku.id,
                line.baseUnit, line.sku.tracking, line.quantityBase.toLong(), line.sourceIdentityId, dimension.lotId,
                line.demandLineId, dimension.locationId, dimension.custodianId, dimension.custodianKind, dimension.condition, dimension.legalOwner)
        }
    }
    fun seal(snapshot: IssueSnapshot, operation: UUID, body: String) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_issue_snapshot(id,tenant_id,demand_document_id,plan_id,operation_id,demand_revision,sender_id,receiver_id,snapshot)
            VALUES (?,?,?,?,?,?,?,?,?)""", snapshot.issueId, sql.tenant, snapshot.demandDocumentId, snapshot.planId,
            operation, snapshot.demandRevision, snapshot.sender.id, snapshot.receiver.id, body)
        snapshot.lines.forEach { line -> sql.update("""INSERT INTO inventory_issue_line(id,tenant_id,issue_id,reservation_id,stock_identity_id,reservation_revision,quantity_base)
            VALUES (?,?,?,?,?,?,?)""", line.id, sql.tenant, snapshot.issueId, line.reservationId, line.dimension.stockIdentityId,
            line.reservationRevision, line.quantityBase.toLong()) }
    }
    fun snapshot(id: UUID): IssueSnapshot = jdbc.execute { sql ->
        val body = sql.value("SELECT snapshot FROM inventory_issue_snapshot WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        mapper.readValue(body, IssueSnapshot::class.java)
    }
    fun state(id: UUID): Pair<String, Long> = jdbc.execute { sql ->
        sql.query("SELECT state,revision FROM inventory_document WHERE tenant_id=? AND id=? AND kind='ISSUE'", sql.tenant, id) {
            it.getString(1) to it.getLong(2)
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun wasUnpicked(id: UUID): Boolean = jdbc.execute { it.value("SELECT id FROM inventory_issue_unpick WHERE tenant_id=? AND id=?", it.tenant, id) != null }
    fun dispatchDestinations(id: UUID): List<PostingDimension> = jdbc.execute { sql ->
        sql.query("""SELECT leg.* FROM inventory_operation operation JOIN inventory_movement movement
            ON movement.tenant_id=operation.tenant_id AND movement.operation_id=operation.id
            JOIN inventory_movement_leg leg ON leg.tenant_id=movement.tenant_id AND leg.movement_id=movement.id
            WHERE operation.tenant_id=? AND operation.document_id=? AND operation.business_action='DISPATCH'
                AND movement.state='APPLIED' AND leg.direction='IN' AND leg.status='IN_TRANSIT' ORDER BY leg.id""", sql.tenant, id) {
            PostingDimension(it.uuid("sku_id"), it.uuid("stock_identity_id"), it.optionalUuid("lot_id"), it.uuid("location_id"),
                it.uuid("custody_owner_id"), OwnerKind.valueOf(it.getString("custody_owner_kind")),
                WarehouseCondition.valueOf(it.getString("condition")), AssetLegalOwner.valueOf(it.getString("legal_owner")))
        }.also { destinations ->
            if (destinations.isEmpty() && sql.value("SELECT id FROM inventory_operation WHERE tenant_id=? AND document_id=? AND business_action='DISPATCH'", sql.tenant, id) != null)
                sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
    }
    fun unpicked(id: UUID, operation: UUID) = jdbc.execute { it.update("INSERT INTO inventory_issue_unpick(id,tenant_id,operation_id) VALUES (?,?,?)", id, it.tenant, operation) }
    fun print(id: UUID): String = jdbc.execute { sql ->
        sql.value("SELECT original_body FROM inventory_operation WHERE tenant_id=? AND document_id=? AND business_action IN ('DISPATCH','UNPICK') ORDER BY document_revision DESC LIMIT 1", sql.tenant, id)
            ?: sql.value("SELECT snapshot FROM inventory_issue_snapshot WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun transit(): UUID = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_location WHERE tenant_id=? AND code='WO_TRANSIT' AND kind='TRANSIT' AND state='ACTIVE' FOR SHARE", sql.tenant) {
            it.uuid("id")
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
}
