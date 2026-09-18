package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import org.springframework.stereotype.Repository
import java.util.UUID

data class CountSession(val view: WarehouseCountView, val requester: UUID, val cutoverEpoch: Long)
data class CountPosition(val id: UUID, val dimension: PostingDimension, val revision: Long, val quantity: Long,
    val unit: WarehouseBaseUnit, val tracking: String, val capacity: Long, val status: InventoryStatus)

@Repository
class WarehouseCountStore(private val jdbc: WarehouseCommandJdbc) {
    fun get(id: UUID): CountSession = jdbc.execute { sql ->
        val header = sql.query("""SELECT document.*,scope.location_id,scope.partial_location,
            (SELECT max(document_revision) FROM inventory_count_round WHERE tenant_id=document.tenant_id AND document_id=document.id) round_revision
            FROM inventory_document document JOIN inventory_count_scope scope ON scope.tenant_id=document.tenant_id AND scope.id=document.id
            WHERE document.tenant_id=? AND document.id=? FOR UPDATE OF document""", sql.tenant, id) {
            CountSession(WarehouseCountView(id, it.getLong("revision"), WarehouseCountState.valueOf(it.getString("state")),
                it.uuid("location_id"), it.getBoolean("partial_location"), it.getString("round_revision")?.toLong(), emptyList()),
                it.uuid("actor_id"), it.getLong("cutover_epoch"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        val entries = sql.query("""SELECT entry.balance_id,entry.counter_id,line.stock_identity_id,line.sku_id,line.base_unit
            FROM inventory_count_entry entry JOIN inventory_document_line line ON line.tenant_id=entry.tenant_id AND line.id=entry.id
            WHERE entry.tenant_id=? AND entry.document_id=? ORDER BY line.line_number""", sql.tenant, id) {
            WarehouseCountEntry(it.uuid("balance_id"), it.uuid("counter_id"), it.uuid("stock_identity_id"), it.uuid("sku_id"),
                WarehouseBaseUnit.valueOf(it.getString("base_unit")))
        }
        header.copy(view = header.view.copy(entries = entries))
    }

    fun position(id: UUID): CountPosition = jdbc.execute { sql ->
        sql.query("""SELECT balance.*,sku.tracking,segment.quantity_base capacity
            FROM inventory_balance_projection balance JOIN inventory_segment segment ON segment.tenant_id=balance.tenant_id
                AND segment.id=balance.stock_identity_id
            JOIN inventory_sku sku ON sku.tenant_id=balance.tenant_id AND sku.id=balance.sku_id
            WHERE balance.tenant_id=? AND balance.id=? AND balance.warehouse_admission='VERIFIED'
                AND segment.warehouse_admission='VERIFIED' AND segment.state='ACTIVE' FOR UPDATE OF balance""", sql.tenant, id) {
            CountPosition(id, PostingProjection.dimension(it), it.getLong("revision"), it.getLong("quantity_base"),
                WarehouseBaseUnit.valueOf(it.getString("base_unit")), it.getString("tracking"), it.getLong("capacity"),
                InventoryStatus.valueOf(it.getString("status")))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    fun create(id: UUID, input: WarehouseCountDraft, actor: UUID, authority: Long, cutover: Long, positions: Map<UUID, CountPosition>) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,reason,cutover_epoch,authority_epoch)
            VALUES (?,?,?,'COUNT',?,?,?,?)""", id, sql.tenant, "COUNT-$id", actor, input.reason, cutover, authority)
        sql.update("INSERT INTO inventory_count_scope(id,tenant_id,location_id,partial_location) VALUES (?,?,?,?)", id, sql.tenant, input.locationId, input.partialLocation)
        input.entries.forEachIndexed { index, entry ->
            val position = positions.getValue(entry.balanceId)
            val dimension = position.dimension
            val line = UUID.randomUUID()
            sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,
                lot_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner)
                VALUES (?,?,?,?,0,?,?,?,?,?,?,?,?,?,?,?)""", line, sql.tenant, id, index + 1, dimension.skuId, dimension.stockIdentityId,
                dimension.lotId, position.unit, position.tracking, position.capacity,
                dimension.locationId, dimension.custodianId, dimension.custodianKind, dimension.condition, dimension.legalOwner)
            sql.update("INSERT INTO inventory_count_entry(id,tenant_id,document_id,balance_id,counter_id) VALUES (?,?,?,?,?)",
                line, sql.tenant, id, entry.balanceId, entry.counterId)
        }
    }

    fun advance(id: UUID, revision: Long, state: WarehouseCountState) = jdbc.execute { sql ->
        if (sql.update("UPDATE inventory_document SET state=?,revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND revision=?",
                state, sql.tenant, id, revision) != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
    }

    fun startRound(id: UUID, revision: Long) = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_count_round(tenant_id,document_id,document_revision) VALUES (?,?,?)", sql.tenant, id, revision)
    }

    fun observe(session: CountSession, input: WarehouseCountObservation, position: CountPosition, actor: UUID, key: String, hash: String) = jdbc.execute { sql ->
        val dimension = position.dimension
        sql.update("""INSERT INTO inventory_cycle_count(id,tenant_id,location_id,item_id,sku_id,reason,evidence_reference,custodian_id,
            operation_key,operation_hash,discrepancy_state,created_at,document_id,document_revision,stock_identity_id,balance_id,
            observed_dimension_revision,prior_quantity_base,observed_quantity_base,base_unit,counter_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,'OBSERVED',clock_timestamp(),?,?,?,?,?,?,?,?,?)""", UUID.randomUUID(), sql.tenant,
            dimension.locationId, dimension.stockIdentityId, dimension.skuId, input.reason, input.documentReference, dimension.custodianId,
            key, hash, session.view.id, requireNotNull(session.view.roundRevision), dimension.stockIdentityId, position.id,
            position.revision, position.quantity, input.quantityBase.toLong(), position.unit, actor)
    }

    fun facts(id: UUID): List<WarehouseCountFact> = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_cycle_count WHERE tenant_id=? AND document_id=? ORDER BY created_at,id", sql.tenant, id) {
            WarehouseCountFact(it.uuid("id"), it.uuid("balance_id"), it.uuid("counter_id"), it.getLong("document_revision"),
                it.getString("observed_quantity_base"), WarehouseBaseUnit.valueOf(it.getString("base_unit")), it.getString("reason"), it.getString("evidence_reference"))
        }
    }

    fun stale(session: CountSession): Boolean = jdbc.execute { sql ->
        session.view.entries.sortedBy { it.balanceId.toString() }.any { entry ->
            val position = position(entry.balanceId)
            sql.value("""SELECT observed_dimension_revision FROM inventory_cycle_count
                WHERE tenant_id=? AND document_id=? AND document_revision=? AND balance_id=?""", sql.tenant,
                session.view.id, session.view.roundRevision, entry.balanceId)?.toLong() != position.revision
        }
    }

    fun unchanged(session: CountSession): Boolean = jdbc.execute { sql ->
        sql.value("""SELECT count(*) FROM inventory_cycle_count WHERE tenant_id=? AND document_id=? AND document_revision=?
            AND prior_quantity_base<>observed_quantity_base""", sql.tenant, session.view.id, session.view.roundRevision) == "0"
    }

    fun candidates(): List<UUID> = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_count_scope WHERE tenant_id=? ORDER BY created_at DESC,id", sql.tenant) { it.uuid("id") }
    }

    fun complete(session: CountSession, sourceRevision: Long, operation: UUID, approval: UUID?) = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_count_result(id,tenant_id,round_revision,source_revision,operation_id,approval_id) VALUES (?,?,?,?,?,?)",
            session.view.id, sql.tenant, session.view.roundRevision, sourceRevision, operation, approval)
    }

    fun review(session: CountSession): WarehouseCountReview = jdbc.execute { sql ->
        WarehouseCountReview(session.view, sql.query("""SELECT * FROM inventory_cycle_count
            WHERE tenant_id=? AND document_id=? AND document_revision=? ORDER BY balance_id""", sql.tenant, session.view.id, session.view.roundRevision) {
            WarehouseCountComparison(it.uuid("balance_id"), it.uuid("counter_id"), it.getString("prior_quantity_base"),
                it.getString("observed_quantity_base"), WarehouseBaseUnit.valueOf(it.getString("base_unit")), it.getLong("observed_dimension_revision"))
        })
    }
}
