package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.application.service.MaterialResidualSnapshot
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

data class ResidualPosition(val dimension: PostingDimension, val quantity: Long, val revision: Long)
data class ResidualStockSource(val receiptId: UUID, val issueLineId: UUID, val usageId: UUID?, val stockIdentityId: UUID, val baseUnit: WarehouseBaseUnit)

@Repository
class MaterialResidualStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun source(context: MaterialPlanningContext, request: ResidualStockSource): ResidualPosition = jdbc.execute { sql ->
        sql.value("SELECT warehouse_assert_residual_source(?,?,?,?,?,?,?)", sql.tenant, context.workOrderId, request.receiptId,
            request.issueLineId, request.usageId, request.stockIdentityId, context.authority.identity.userId)
        sql.query("""SELECT balance.*,segment.revision stock_revision FROM inventory_balance_projection balance
            JOIN inventory_segment segment ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
            WHERE balance.tenant_id=? AND balance.stock_identity_id=? AND balance.status='ISSUED' AND balance.quantity_base>0
            AND balance.custody_owner_kind='TECHNICIAN' AND balance.custody_owner_id=? AND balance.base_unit=?
            AND balance.condition='SERVICEABLE' AND balance.legal_owner='ISP' FOR UPDATE OF segment,balance""",
            sql.tenant, request.stockIdentityId, context.authority.identity.userId, request.baseUnit) {
            ResidualPosition(PostingDimension(it.uuid("sku_id"), request.stockIdentityId, it.optionalUuid("lot_id"), it.uuid("location_id"),
                context.authority.identity.userId, OwnerKind.TECHNICIAN, WarehouseCondition.SERVICEABLE, AssetLegalOwner.ISP),
                it.getLong("quantity_base"), it.getLong("stock_revision"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.WRONG_CUSTODIAN)
    }

    fun target(id: UUID): Pair<String, UUID?> = jdbc.execute { sql ->
        sql.query("SELECT kind,custodian_id FROM inventory_location WHERE tenant_id=? AND id=? AND state='ACTIVE' FOR SHARE", sql.tenant, id) {
            it.getString("kind") to it.optionalUuid("custodian_id")
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun create(context: MaterialPlanningContext, snapshot: MaterialResidualSnapshot) = jdbc.execute { sql ->
        val input = snapshot.request
        val source = snapshot.source
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,work_order_revision,
            work_order_code_snapshot,cutover_epoch,authority_epoch) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            snapshot.id, sql.tenant, "RESIDUAL-${snapshot.id}", if (snapshot.purpose == ResidualPurpose.RETURN) "RETURN" else "TRANSFER",
            context.authority.identity.userId, context.workOrderId, context.customerId, context.workOrderRevision, context.code,
            context.cutover.snapshot.epoch, context.authority.epoch)
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,base_unit,tracking,
            quantity_base,stock_identity_id,lot_id,source_line_id,location_id,custodian_id,custodian_kind,condition,legal_owner)
            SELECT ?,?,?,0,1,?,?,tracking,?,?,?,?,?,?,?,?,? FROM inventory_sku WHERE tenant_id=? AND id=?""",
            snapshot.lineId, sql.tenant, snapshot.id, source.skuId, input.baseUnit, input.quantityBase.toLong(), source.stockIdentityId,
            source.lotId, input.issueLineId, source.locationId, source.custodianId, source.custodianKind, source.condition, source.legalOwner, sql.tenant, source.skuId)
        sql.update("""INSERT INTO inventory_material_residual(id,tenant_id,work_order_id,receipt_id,issue_line_id,usage_id,source_identity_id,
            transit_identity_id,remainder_identity_id,source_revision,source_quantity_base,quantity_base,base_unit,sender_id,target_location_id,
            receiver_id,purpose,source_dimension,transit_dimension,evidence_reference,dispatch_operation_id,dispatch_posting_id,body,recorded_at,authorization_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", snapshot.id, sql.tenant, context.workOrderId, input.receiptId,
            input.issueLineId, input.usageId, source.stockIdentityId, snapshot.transit.stockIdentityId, snapshot.remainder?.stockIdentityId,
            snapshot.sourceRevision, snapshot.sourceQuantityBase, input.quantityBase.toLong(), input.baseUnit, source.custodianId,
            input.targetLocationId, snapshot.receiverId, snapshot.purpose, mapper.writeValueAsString(source), mapper.writeValueAsString(snapshot.transit),
            input.evidenceReference, snapshot.operationId, snapshot.postingId, mapper.writeValueAsString(snapshot), snapshot.recordedAt, input.authorizationId)
    }

    fun get(id: UUID): MaterialResidualSnapshot = jdbc.execute { sql ->
        val body = sql.value("SELECT body FROM inventory_material_residual WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        sql.value("SELECT warehouse_assert_material_residual(?,?)", sql.tenant, id)
        mapper.readValue(body, MaterialResidualSnapshot::class.java)
    }

    fun acknowledged(id: UUID): Boolean = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_material_residual_ack WHERE tenant_id=? AND residual_id=?", sql.tenant, id) != null
    }

    fun acknowledge(snapshot: MaterialResidualSnapshot, receipt: WarehouseOperationReceipt, evidence: String, actor: UUID) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_material_residual_ack(id,tenant_id,residual_id,actor_id,posting_id,evidence_reference,body,recorded_at)
            VALUES (?,?,?,?,?,?,?,?)""", receipt.operationId, sql.tenant, snapshot.id, actor,
            UUID.nameUUIDFromBytes("warehouse:${receipt.operationId}".toByteArray(Charsets.UTF_8)), evidence, receipt.originalBody, receipt.recordedAt)
    }
}
