package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.application.port.outbound.PostingOperation
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseReturnStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun recovered(id: UUID): WarehouseReturnSource {
        val dimension = jdbc.execute { sql ->
            sql.query("""SELECT asset.sku_id,removal.asset_id,removal.recovery_location_id,removal.actor_id,removal.legal_owner
                FROM inventory_asset_removal removal JOIN inventory_serialized_asset asset
                    ON asset.tenant_id=removal.tenant_id AND asset.id=removal.asset_id
                WHERE removal.tenant_id=? AND removal.id=?""", sql.tenant, id) {
                PostingDimension(it.uuid("sku_id"), it.uuid("asset_id"), null, it.uuid("recovery_location_id"),
                    it.uuid("actor_id"), OwnerKind.TRANSIT, WarehouseCondition.QUARANTINE, AssetLegalOwner.valueOf(it.getString("legal_owner")))
            }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        }
        return position(dimension)
    }

    fun sourceLine(id: UUID): UUID = jdbc.execute { sql ->
        UUID.fromString(sql.value("SELECT id FROM inventory_document_line WHERE tenant_id=? AND document_id=?", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED))
    }

    fun position(dimension: PostingDimension): WarehouseReturnSource = jdbc.execute { sql ->
        sql.query("""SELECT balance.*,segment.revision segment_revision,sku.tracking,asset.serial_number
            FROM inventory_balance_projection balance
            JOIN inventory_segment segment ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
            JOIN inventory_sku sku ON sku.tenant_id=segment.tenant_id AND sku.id=segment.sku_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.id
            WHERE balance.tenant_id=? AND balance.stock_identity_id=? AND balance.location_id=?
            AND balance.custody_owner_id=? AND balance.custody_owner_kind=? AND balance.condition=? AND balance.legal_owner=?
            AND balance.quantity_base>0 AND balance.status='QUARANTINE' AND balance.warehouse_admission='VERIFIED'
            AND segment.warehouse_admission='VERIFIED' AND segment.state='ACTIVE' AND sku.state='ACTIVE'""",
            sql.tenant, dimension.stockIdentityId, dimension.locationId, dimension.custodianId,
            dimension.custodianKind, dimension.condition, dimension.legalOwner) {
            WarehouseReturnSource(PostingProjection.dimension(it), it.getLong("quantity_base"),
                WarehouseBaseUnit.valueOf(it.getString("base_unit")), WarehouseTracking.valueOf(it.getString("tracking")),
                it.getLong("revision"), it.getLong("segment_revision"), it.getString("serial_number"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun create(record: WarehouseReturnRecord, operation: PostingOperation, cutoverEpoch: Long) = jdbc.execute { sql ->
        val view = record.view
        val source = record.source
        val dimension = source.dimension
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,state,actor_id,source_document_id,source_revision,
            cutover_epoch,authority_epoch,source_reference,reason)
            SELECT ?,?,?,'RETURN','DRAFT',?,id,revision,?,?,?,'Inspect verified return'
            FROM inventory_document WHERE tenant_id=? AND id=?""", view.id, sql.tenant, "RET-${view.id}", view.receivedBy,
            cutoverEpoch, operation.authorityEpoch, record.intake.evidenceReference, sql.tenant, record.intake.sourceDocumentId)
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,
            stock_identity_id,lot_id,source_line_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner)
            VALUES (?,?,?,0,1,?,?,?,?,?,?,?,?,?,?,?,?)""", view.id, sql.tenant, view.id, dimension.skuId, dimension.stockIdentityId,
            dimension.lotId, record.sourceLineId, source.unit, source.tracking, source.quantity, dimension.locationId,
            dimension.custodianId, dimension.custodianKind, dimension.condition, dimension.legalOwner)
        sql.update("""INSERT INTO inventory_return_case(id,tenant_id,origin,source_document_id,stock_identity_id,quarantine_location_id,body)
            VALUES (?,?,?,?,?,?,?)""", view.id, sql.tenant, record.intake.origin, record.intake.sourceDocumentId,
            dimension.stockIdentityId, record.intake.quarantineLocationId, mapper.writeValueAsString(record))
        sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,
            payload_hash,document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?)""", operation.id, sql.tenant, operation.namespace, operation.key,
            operation.actorId, view.id, operation.resourceScope, operation.payloadHash, view.id, operation.businessAction,
            operation.originalStatus, operation.originalBody, cutoverEpoch, operation.authorityEpoch, operation.recordedAt)
        Unit
    }

    fun get(id: UUID, lock: Boolean = false): WarehouseReturnRecord = jdbc.execute { sql ->
        val row = sql.query("""SELECT source.body,operation.original_body FROM inventory_return_case source
            JOIN inventory_document document ON document.tenant_id=source.tenant_id AND document.id=source.id
            JOIN inventory_operation operation ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id
                AND operation.document_revision=document.revision
            WHERE source.tenant_id=? AND source.id=?${if (lock) " FOR UPDATE OF document" else ""}""", sql.tenant, id) {
            mapper.readValue(it.getString("body"), WarehouseReturnRecord::class.java).copy(
                view = mapper.readValue(it.getString("original_body"), WarehouseReturnView::class.java))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        row
    }

    fun history(id: UUID): List<WarehouseReturnView> = jdbc.execute { sql ->
        sql.query("""SELECT original_body FROM inventory_operation WHERE tenant_id=? AND document_id=?
            AND namespace LIKE 'warehouse.return.%' ORDER BY document_revision LIMIT 100""", sql.tenant, id) {
            mapper.readValue(it.getString("original_body"), WarehouseReturnView::class.java)
        }
    }
}
