package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.PostingOperation
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseReceiptPersistence(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun get(id: UUID, lock: Boolean = false): ReceiptRecord = jdbc.execute { sql ->
        sql.query("""SELECT document.id,document.revision,document.state,document.created_at,intake.snapshot FROM inventory_document document
            JOIN inventory_receipt_intake intake ON intake.tenant_id=document.tenant_id AND intake.id=document.id
            WHERE document.tenant_id=? AND document.id=?${if (lock) " FOR UPDATE OF document" else ""}""", sql.tenant, id) {
            ReceiptRecord(it.uuid("id"), it.getLong("revision"), WarehouseReceiptState.valueOf(it.getString("state")),
                it.getTimestamp("created_at").toInstant(), mapper.readValue(it.getString("snapshot"), ReceiptIntake::class.java))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun saveDraft(id: UUID, intake: ReceiptIntake, revision: Long, actor: UUID, epoch: Long, cutover: Long, creating: Boolean) = jdbc.execute { sql ->
        if (creating) sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,supplier_id,source_reference,cutover_epoch,authority_epoch)
            VALUES (?,?,?,'RECEIPT',?,?,?,?,?)""", id, sql.tenant, "RCV-$id", actor, intake.supplier.id, intake.externalReference, cutover, epoch)
        else {
            sql.update("DELETE FROM inventory_document_line WHERE tenant_id=? AND document_id=?", sql.tenant, id)
            sql.update("""UPDATE inventory_document SET revision=revision+1,supplier_id=?,source_reference=?,updated_at=clock_timestamp()
                WHERE tenant_id=? AND id=? AND state='DRAFT'""", intake.supplier.id, intake.externalReference, sql.tenant, id)
        }
        sql.update("""INSERT INTO inventory_receipt_intake(id,tenant_id,source_location_id,inspection_location_id,snapshot) VALUES (?,?,?,?,?)
            ON CONFLICT (id) DO UPDATE SET source_location_id=excluded.source_location_id,inspection_location_id=excluded.inspection_location_id,snapshot=excluded.snapshot""",
            id, sql.tenant, intake.source.id, intake.inspection.id, mapper.writeValueAsString(intake))
        intake.lines.forEachIndexed { index, line ->
            val conversion = line.conversion?.takeIf { line.sku.tracking != WarehouseTracking.SERIAL }
            sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,base_unit,tracking,quantity_base,
                location_id,destination_location_id,custodian_id,custodian_kind,condition,legal_owner,inspection_required_snapshot,
                cost_total_minor,cost_basis_quantity_base,currency,conversion_numerator,conversion_denominator,package_quantity)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'WAREHOUSE','QUARANTINE','ISP',?,?,?,?,?,?,?)""",
                line.id, sql.tenant, id, revision, index + 1, line.sku.id, line.sku.baseUnit, line.sku.tracking, line.quantityBase.toLong(),
                intake.inspection.id, intake.source.id, intake.inspection.id, line.sku.inspectionRequired,
                line.cost?.totalMinor?.toLong(), line.cost?.costBasisQuantityBase?.toLong(), line.cost?.currency,
                conversion?.numerator?.toLong(), conversion?.denominator?.toLong(), conversion?.packageQuantity?.toLong())
        }
    }

    fun operation(operation: PostingOperation, revision: Long, cutover: Long) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,payload_hash,
            document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", operation.id, sql.tenant, operation.namespace, operation.key, operation.actorId,
            operation.resourceId, operation.resourceScope, operation.payloadHash, operation.resourceId, revision, operation.businessAction,
            operation.originalStatus, operation.originalBody, cutover, operation.authorityEpoch, operation.recordedAt)
    }

    fun advance(id: UUID, revision: Long) = jdbc.execute { sql ->
        if (sql.update("UPDATE inventory_document SET revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND revision=?",
            sql.tenant, id, revision) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
    }

    fun history(id: UUID): List<ReceiptHistory> = jdbc.execute { sql ->
        sql.query("""SELECT id,document_revision,business_action,created_at FROM inventory_operation
            WHERE tenant_id=? AND document_id=? ORDER BY document_revision,id""", sql.tenant, id) {
            ReceiptHistory(it.uuid("id"), it.getLong("document_revision"), it.getString("business_action"), it.getTimestamp("created_at").toInstant())
        }
    }

    fun inspections(id: UUID): List<ReceiptInspectionView> = jdbc.execute { sql ->
        sql.query("""SELECT inspection.* FROM inventory_inspection inspection JOIN inventory_document_line line
            ON line.tenant_id=inspection.tenant_id AND line.id=inspection.document_line_id
            WHERE line.tenant_id=? AND line.document_id=? ORDER BY inspection.created_at,inspection.id""", sql.tenant, id) {
            ReceiptInspectionView(it.uuid("id"), it.uuid("document_line_id"), it.getLong("accepted_base").toString(), it.getLong("rejected_base").toString(),
                WarehouseBaseUnit.valueOf(it.getString("base_unit")), UUID.fromString(it.getString("evidence_reference")), it.getString("reason"),
                it.getString("disposition"), it.uuid("operation_id"))
        }
    }

    fun putawayBase(line: UUID): String = jdbc.execute { sql ->
        requireNotNull(sql.value("""SELECT coalesce(sum(leg.quantity_base::numeric),0) FROM inventory_movement_leg leg
            JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            JOIN inventory_operation operation ON operation.tenant_id=movement.tenant_id AND operation.id=movement.operation_id
            WHERE leg.tenant_id=? AND leg.document_line_id=? AND leg.direction='IN' AND leg.status='AVAILABLE'
                AND operation.namespace='warehouse.receipt.putaway'""", sql.tenant, line))
    }

    fun candidates(filter: ReceiptFilter): List<UUID> = jdbc.execute { sql ->
        val conditions = mutableListOf("document.tenant_id=?")
        val values = mutableListOf<Any?>(sql.tenant)
        filter.status?.let { conditions += "document.state=?"; values += it }
        filter.skuId?.let { conditions += "EXISTS(SELECT FROM inventory_document_line WHERE tenant_id=document.tenant_id AND document_id=document.id AND sku_id=?)"; values += it }
        filter.locationId?.let { conditions += "(intake.inspection_location_id=? OR intake.source_location_id=?)"; values += it; values += it }
        filter.from?.let { conditions += "document.created_at>=?"; values += it }
        filter.until?.let { conditions += "document.created_at<?"; values += it }
        val sort = if (filter.sort == "createdAt") "document.created_at" else "document.source_reference"
        sql.query("""SELECT document.id FROM inventory_document document JOIN inventory_receipt_intake intake
            ON intake.tenant_id=document.tenant_id AND intake.id=document.id WHERE ${conditions.joinToString(" AND ")}
            ORDER BY $sort ${filter.direction},document.id""", *values.toTypedArray()) { it.uuid("id") }
    }

    fun pieces(line: UUID): List<ReceiptPiece> = jdbc.execute { sql ->
        sql.query("""WITH RECURSIVE pieces AS (
            SELECT segment.*,NULL::text AS inherited_disposition FROM inventory_document_line line JOIN inventory_segment segment
                ON segment.tenant_id=line.tenant_id AND segment.id=line.stock_identity_id WHERE line.tenant_id=? AND line.id=?
            UNION ALL SELECT child.*,coalesce(disposition.disposition,parent.inherited_disposition) FROM inventory_segment child
                JOIN pieces parent ON parent.tenant_id=child.tenant_id AND parent.id=child.parent_segment_id
                LEFT JOIN inventory_receipt_disposition disposition ON disposition.tenant_id=parent.tenant_id AND disposition.segment_id=parent.id)
            SELECT piece.id,piece.lot_id,piece.quantity_base,piece.revision,coalesce(disposition.disposition,piece.inherited_disposition) disposition,
                balance.location_id,balance.condition,balance.legal_owner,balance.status,balance.custody_owner_id,balance.custody_owner_kind
            FROM pieces piece JOIN inventory_balance_projection balance ON balance.tenant_id=piece.tenant_id AND balance.stock_identity_id=piece.id
            LEFT JOIN inventory_receipt_disposition disposition ON disposition.tenant_id=piece.tenant_id AND disposition.segment_id=piece.id
            WHERE piece.state='ACTIVE' AND piece.warehouse_admission='VERIFIED' AND balance.quantity_base>0 ORDER BY piece.id""", sql.tenant, line) {
            ReceiptPiece(it.uuid("id"), it.optionalUuid("lot_id"), it.getLong("quantity_base").toString(), it.getLong("revision"), it.getString("disposition"),
                it.uuid("location_id"), WarehouseCondition.valueOf(it.getString("condition")), AssetLegalOwner.valueOf(it.getString("legal_owner")),
                it.getString("status"), it.uuid("custody_owner_id"), it.getString("custody_owner_kind"))
        }
    }
}
