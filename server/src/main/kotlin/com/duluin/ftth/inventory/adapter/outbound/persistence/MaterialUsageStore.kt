package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.MaterialMode
import com.duluin.ftth.inventory.MaterialPlanningContext
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.outbound.PostingOperation
import com.duluin.ftth.inventory.application.service.MaterialUsageSnapshot
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MaterialUsageStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun consumedLocation(): UUID = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_location WHERE tenant_id=? AND code='CONSUMED' AND state='ACTIVE' FOR SHARE", sql.tenant) {
            it.uuid("id")
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun lockSources(receipts: List<UUID>) = jdbc.execute { sql ->
        val documents = receipts.flatMap { receipt -> sql.query("""SELECT document_id FROM inventory_document_line
            WHERE tenant_id=? AND id IN (
                SELECT line.issue_line_id FROM inventory_material_receipt_line line WHERE line.tenant_id=? AND line.receipt_id=?
                UNION SELECT lot.origin_document_line_id FROM inventory_material_receipt_line line
                    JOIN inventory_segment segment ON segment.tenant_id=line.tenant_id AND segment.id=line.accepted_identity_id
                    JOIN inventory_lot lot ON lot.tenant_id=segment.tenant_id AND lot.id=segment.lot_id
                    WHERE line.tenant_id=? AND line.receipt_id=?)
            UNION SELECT demand_document_id FROM inventory_issue_snapshot WHERE tenant_id=? AND id IN (
                SELECT issue_id FROM inventory_material_receipt WHERE tenant_id=? AND id=?)""",
            sql.tenant, sql.tenant, receipt, sql.tenant, receipt, sql.tenant, sql.tenant, receipt) { it.uuid("document_id") } }
        documents.distinct().sortedBy(UUID::toString).forEach { id ->
            sql.value("SELECT id FROM inventory_document WHERE tenant_id=? AND id=? FOR NO KEY UPDATE", sql.tenant, id)
        }
        receipts.distinct().sortedBy(UUID::toString).forEach { id ->
            if (sql.value("SELECT id FROM inventory_material_receipt WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id) == null)
                sql.fail(WarehouseErrorCode.NOT_FOUND)
        }
    }

    fun create(snapshot: MaterialUsageSnapshot, context: MaterialPlanningContext) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,work_order_revision,
            plan_revision,use_revision,work_order_code_snapshot,cutover_epoch,authority_epoch)
            VALUES (?,?,?,'USAGE',?,?,?,?,?,?,?,?,?)""", snapshot.usageId, sql.tenant, "USE-${snapshot.usageId}", snapshot.actorId,
            snapshot.workOrderId, snapshot.customerId, snapshot.workOrderRevision, snapshot.planRevision, snapshot.useRevision,
            context.code, context.cutover.snapshot.epoch, context.authority.epoch)
        snapshot.lines.forEachIndexed { index, line ->
            val source = line.source
            sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,base_unit,tracking,
                quantity_base,stock_identity_id,lot_id,source_line_id,location_id,custodian_id,custodian_kind,condition,legal_owner)
                SELECT ?,?,?,0,?,?,?,tracking,?,?,?,?,?,?,?,?,? FROM inventory_sku WHERE tenant_id=? AND id=?""",
                line.id, sql.tenant, snapshot.usageId, index + 1, source.skuId, line.selection.baseUnit, line.selection.quantityBase.toLong(),
                source.stockIdentityId, source.lotId, line.selection.issueLineId, source.locationId, source.custodianId, source.custodianKind,
                source.condition, source.legalOwner, sql.tenant, source.skuId)
        }
        sql.update("""INSERT INTO inventory_material_usage(id,tenant_id,actor_id,customer_id,evidence_reference,reason,network_reference_label,recorded_at)
            VALUES (?,?,?,?,?,?,?,?)""", snapshot.usageId, sql.tenant, snapshot.actorId, snapshot.customerId, snapshot.evidenceReference,
            snapshot.reason, snapshot.networkReferenceLabel, snapshot.recordedAt)
        snapshot.lines.forEach { line ->
            sql.update("""INSERT INTO inventory_material_usage_line(id,tenant_id,usage_id,receipt_id,issue_line_id,plan_line_id,source_identity_id,
                source_revision,consumed_identity_id,remainder_identity_id,fact_id,requested_base,acknowledged_base,used_base,residual_base,base_unit)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", line.id, sql.tenant, snapshot.usageId, line.selection.receiptId,
                line.selection.issueLineId, line.planLineId, line.source.stockIdentityId, line.sourceRevision, line.consumed.stockIdentityId,
                line.remainder?.stockIdentityId, line.factId, line.requestedBase.toLong(), line.acknowledgedBase.toLong(),
                line.selection.quantityBase.toLong(), line.residualBase.toLong(), line.selection.baseUnit)
        }
    }

    fun recordNone(snapshot: MaterialUsageSnapshot, operation: PostingOperation, context: MaterialPlanningContext) = jdbc.execute { sql ->
        check(snapshot.materialMode == MaterialMode.NONE && snapshot.lines.isEmpty())
        sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,payload_hash,
            document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,1,'REPORT_USE',200,?,?,?,?)""", operation.id, sql.tenant, operation.namespace, operation.key,
            operation.actorId, operation.resourceId, operation.resourceScope, operation.payloadHash, snapshot.usageId,
            operation.originalBody, context.cutover.snapshot.epoch, operation.authorityEpoch, operation.recordedAt)
        sql.update("UPDATE inventory_document SET state='POSTED',revision=1 WHERE tenant_id=? AND id=?", sql.tenant, snapshot.usageId)
        sql.update("""INSERT INTO inventory_usage_snapshot(id,tenant_id,work_order_id,use_revision,plan_id,work_order_revision,
            operation_id,posting_ids,frozen_snapshot,material_mode) VALUES (?,?,?,1,?,?,?,'{}'::uuid[],?,'NONE')""",
            snapshot.usageId, sql.tenant, snapshot.workOrderId, snapshot.planId, snapshot.workOrderRevision, operation.id, operation.originalBody)
    }

    fun get(id: UUID): MaterialUsageSnapshot = mapper.readValue(body(id), MaterialUsageSnapshot::class.java)

    fun body(id: UUID): String = jdbc.execute { sql ->
        val body = sql.value("""SELECT snapshot.frozen_snapshot FROM inventory_usage_snapshot snapshot JOIN inventory_material_usage usage
            ON usage.tenant_id=snapshot.tenant_id AND usage.id=snapshot.id WHERE snapshot.tenant_id=? AND snapshot.id=?""", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        sql.value("SELECT warehouse_assert_material_usage(?,?)", sql.tenant, id)
        body
    }
}
