package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingOperation
import com.duluin.ftth.inventory.application.service.TransferRecord
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseTransferStore(private val jdbc: WarehouseCommandJdbc, private val operations: WarehouseOperationStore) {
    private val mapper = jacksonObjectMapper()

    fun get(id: UUID, lock: Boolean = false): TransferRecord = jdbc.execute { sql ->
        val revision = sql.value("""SELECT revision FROM inventory_document WHERE tenant_id=? AND id=?
            AND kind='TRANSFER' AND transfer_receiver_id IS NOT NULL${if (lock) " FOR UPDATE" else ""}""", sql.tenant, id)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        val body = sql.value("""SELECT identity.canonical_payload FROM inventory_operation operation
            JOIN inventory_command_identity identity ON identity.tenant_id=operation.tenant_id AND identity.id=operation.id
            WHERE operation.tenant_id=? AND operation.document_id=? AND operation.document_revision=?
            AND operation.namespace LIKE 'warehouse.transfer.%'""", sql.tenant, id, revision.toLong())
            ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        mapper.readValue(body, TransferRecord::class.java)
    }

    fun create(record: TransferRecord, authorityEpoch: Long, cutoverEpoch: Long) = jdbc.execute { sql ->
        val binding = record.binding
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,authority_epoch,cutover_epoch,reason,
            transfer_source_location_id,transfer_destination_location_id,transfer_transit_location_id,transfer_receiver_id)
            VALUES (?,?,?,'TRANSFER',?,?,?,?,?,?,?,?)""", record.id, sql.tenant, record.code, record.sender,
            authorityEpoch, cutoverEpoch, binding.reason, binding.sourceLocationId, binding.destinationLocationId,
            binding.transitLocationId, binding.receiverId)
        lines(sql, record)
    }

    fun replaceDraft(record: TransferRecord) = jdbc.execute { sql ->
        val binding = record.binding
        if (sql.update("""UPDATE inventory_document SET reason=?,transfer_source_location_id=?,
            transfer_destination_location_id=?,transfer_transit_location_id=?,transfer_receiver_id=?,revision=?,updated_at=?
            WHERE tenant_id=? AND id=? AND state='DRAFT' AND revision=?""", binding.reason, binding.sourceLocationId,
                binding.destinationLocationId, binding.transitLocationId, binding.receiverId, record.revision, record.recordedAt,
                sql.tenant, record.id, record.revision - 1) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
        sql.update("DELETE FROM inventory_document_line WHERE tenant_id=? AND document_id=?", sql.tenant, record.id)
        lines(sql, record)
    }

    private fun lines(sql: PostingSql, record: TransferRecord) {
        val binding = record.binding
        record.lines.forEachIndexed { index, line ->
            val source = line.source
            val dimension = source.dimension
            sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,
                base_unit,tracking,quantity_base,stock_identity_id,lot_id,location_id,destination_location_id,custodian_id,
                custodian_kind,condition,legal_owner,cost_total_minor,cost_basis_quantity_base,currency)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", line.id, sql.tenant, record.id, record.revision, index + 1,
                dimension.skuId, source.unit, source.tracking, line.quantity, dimension.stockIdentityId, dimension.lotId,
                dimension.locationId, binding.destinationLocationId, dimension.custodianId, dimension.custodianKind,
                dimension.condition, dimension.legalOwner, source.cost?.totalMinor?.toLong(),
                source.cost?.costBasisQuantityBase?.toLong(), source.cost?.currency)
        }
    }

    fun draftOperation(operation: PostingOperation, epoch: Long, revision: Long = 0) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,
            payload_hash,document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", operation.id, sql.tenant, operation.namespace, operation.key,
            operation.actorId, operation.resourceId, operation.resourceScope, operation.payloadHash, operation.resourceId,
            revision, operation.businessAction, operation.originalStatus, operation.originalBody, epoch, operation.authorityEpoch, operation.recordedAt)
    }

    fun seal(record: TransferRecord, operation: PostingOperation, session: String?) {
        operations.storeIdentity(operation.id, mapper.writeValueAsString(record), session)
    }

    fun advanceWithoutPosting(record: TransferRecord, operation: PostingOperation, epoch: Long, session: String?) {
        jdbc.execute { sql ->
            sql.update("""INSERT INTO inventory_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,resource_scope,
                payload_hash,document_id,document_revision,business_action,original_status,original_body,cutover_epoch,authority_epoch,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", operation.id, sql.tenant, operation.namespace, operation.key,
                operation.actorId, record.id, operation.resourceScope, operation.payloadHash, record.id, record.revision,
                operation.businessAction, operation.originalStatus, operation.originalBody, epoch, operation.authorityEpoch, operation.recordedAt)
            if (sql.update("UPDATE inventory_document SET state=?,revision=revision+1,updated_at=? WHERE tenant_id=? AND id=? AND revision=?",
                record.state, operation.recordedAt, sql.tenant, record.id, record.revision - 1) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
            sql.update("""INSERT INTO inventory_outbox(id,tenant_id,operation_id,document_id,document_revision,event_kind,payload,recorded_at)
                VALUES (?,?,?,?,?,'ACKNOWLEDGED',?,?)""", UUID.randomUUID(), sql.tenant, operation.id, record.id,
                record.revision, operation.originalBody, operation.recordedAt)
        }
        seal(record, operation, session)
    }

}
