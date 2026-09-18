package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.TransferRecord
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class TransferResolution(val id: UUID, val transferId: UUID, val transferRevision: Long,
    val request: WarehouseTransferDiscrepancy, val actorId: UUID)

@Repository
class WarehouseTransferDiscrepancyStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun find(id: UUID): TransferResolution? = jdbc.execute { sql ->
        sql.query("""SELECT * FROM inventory_document WHERE tenant_id=? AND id=? AND kind='ADJUSTMENT'
            AND transfer_remainder_action IS NOT NULL""", sql.tenant, id) {
            TransferResolution(id, it.uuid("source_document_id"), it.getLong("source_revision"),
                mapper.readValue(it.getString("transfer_remainder_request"), WarehouseTransferDiscrepancy::class.java), it.uuid("actor_id"))
        }.singleOrNull()
    }

    fun create(record: TransferRecord, request: WarehouseTransferDiscrepancy, actor: UUID, authority: Long, epoch: Long) = jdbc.execute { sql ->
        val id = requireNotNull(record.resolutionDocumentId)
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,authority_epoch,cutover_epoch,
            source_document_id,source_revision,reason,transfer_remainder_action,transfer_remainder_request)
            VALUES (?,?,?,'ADJUSTMENT',?,?,?,?,?,?,?,?)""", id, sql.tenant, "TRX-$id", actor, authority, epoch,
            record.id, record.revision, request.reason, request.action, mapper.writeValueAsString(request))
        record.lines.filter { it.received < it.quantity }.forEachIndexed { index, line ->
            sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,
                base_unit,tracking,quantity_base,stock_identity_id,lot_id,source_line_id,location_id,destination_location_id,
                custodian_id,custodian_kind,condition,legal_owner,cost_total_minor,cost_basis_quantity_base,currency)
                VALUES (?,?,?,0,?,?,?,?,?,?,?,?,?,?,?,'TRANSIT',?,?,?,?,?)""", UUID.randomUUID(), sql.tenant, id, index + 1,
                line.source.dimension.skuId, line.source.unit, line.source.tracking, line.quantity - line.received,
                line.remainingIdentity, line.source.dimension.lotId, line.id, record.binding.transitLocationId,
                request.destinationLocationId, record.id, line.source.dimension.condition, line.source.dimension.legalOwner,
                line.source.cost?.totalMinor?.toLong(), line.source.cost?.costBasisQuantityBase?.toLong(), line.source.cost?.currency)
        }
    }

    fun lines(id: UUID): Map<UUID, UUID> = jdbc.execute { sql ->
        sql.query("SELECT id,source_line_id FROM inventory_document_line WHERE tenant_id=? AND document_id=?", sql.tenant, id) {
            it.uuid("source_line_id") to it.uuid("id")
        }.toMap()
    }

    fun guard(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt): ReceiptPostingApproval = jdbc.execute { sql ->
        ReceiptPostingApproval(attempt, record.expiresAt,
            WarehouseCanonicalPayload.parse(mapper.writeValueAsString(requireNotNull(record.snapshot.evaluation.policy))).hash,
            record.snapshot.sourceHash, record.snapshot.cutoverEpoch, requireNotNull(sql.value("SELECT pg_current_xact_id()::text")), ApprovalPostingKind.ADJUSTMENT)
    }

    fun assertApproval(guard: ReceiptPostingApproval) = jdbc.execute { sql -> assertReceiptApproval(sql, guard, false) }
}
