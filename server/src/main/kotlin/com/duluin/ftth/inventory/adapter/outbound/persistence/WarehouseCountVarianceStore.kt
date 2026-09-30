package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.domain.model.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseCountVarianceStore(private val jdbc: WarehouseCommandJdbc, private val counts: WarehouseCountStore) {
    fun lock(session: CountSession) = jdbc.execute { sql ->
        val identities = session.view.entries.map { it.stockIdentityId }.distinct().sortedBy(UUID::toString)
        val origins = identities.flatMap { identity ->
            sql.query("""SELECT lot.id lot_id,lot.origin_document_line_id,line.document_id FROM inventory_segment segment
                JOIN inventory_lot lot ON lot.tenant_id=segment.tenant_id AND lot.id=segment.lot_id
                JOIN inventory_document_line line ON line.tenant_id=lot.tenant_id AND line.id=lot.origin_document_line_id
                WHERE segment.tenant_id=? AND segment.id=?""", sql.tenant, identity) {
                Triple(it.uuid("lot_id"), it.uuid("origin_document_line_id"), it.uuid("document_id"))
            }
        }
        origins.map { it.third }.distinct().sortedBy(UUID::toString).forEach {
            sql.value("SELECT id FROM inventory_document WHERE tenant_id=? AND id=? FOR SHARE", sql.tenant, it)
        }
        origins.map { it.first }.distinct().sortedBy(UUID::toString).forEach {
            sql.value("SELECT id FROM inventory_lot WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, it)
        }
        identities.forEach { sql.value("SELECT id FROM inventory_segment WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, it) }
        session.view.entries.sortedBy { it.balanceId.toString() }.forEach { counts.position(it.balanceId) }
    }

    fun guard(record: WarehouseApprovalRecord, attempt: WarehouseApprovalAttempt): ReceiptPostingApproval = jdbc.execute { sql ->
        ReceiptPostingApproval(attempt, record.expiresAt,
            WarehouseCanonicalPayload.parse(jacksonObjectMapper().writeValueAsString(requireNotNull(record.snapshot.evaluation.policy))).hash,
            record.snapshot.sourceHash, record.snapshot.cutoverEpoch, requireNotNull(sql.value("SELECT pg_current_xact_id()::text")), ApprovalPostingKind.COUNT)
    }

    fun legs(session: CountSession): List<PostingLeg> = jdbc.execute { sql ->
        val facts = counts.facts(session.view.id).filter { it.roundRevision == session.view.roundRevision }.associateBy { it.balanceId }
        session.view.entries.flatMap { entry ->
            val position = counts.position(entry.balanceId)
            val measured = facts.getValue(entry.balanceId).quantityBase.toLong()
            val delta = measured - position.quantity
            if (delta == 0L) return@flatMap emptyList()
            val source = position.dimension
            if (position.tracking != "BULK" || position.unit != WarehouseBaseUnit.EA || source.legalOwner != AssetLegalOwner.ISP ||
                source.custodianKind != OwnerKind.WAREHOUSE || source.condition != WarehouseCondition.SERVICEABLE || position.status != InventoryStatus.AVAILABLE)
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Count adjustment supports verified ISP bulk units in warehouse custody; use the identity or custody workflow for other discrepancies")
            if (sql.value("""SELECT EXISTS(SELECT FROM inventory_reservation WHERE tenant_id=? AND stock_identity_id=? AND state='OPEN')
                OR EXISTS(SELECT FROM inventory_asset_assignment WHERE tenant_id=? AND asset_id=? AND ended_at IS NULL)
                OR EXISTS(SELECT FROM inventory_material_obligation_snapshot WHERE tenant_id=? AND stock_identity_id=?)
                OR EXISTS(SELECT FROM inventory_material_receipt_line WHERE tenant_id=? AND accepted_identity_id=?)""",
                    sql.tenant, source.stockIdentityId, sql.tenant, source.stockIdentityId, sql.tenant, source.stockIdentityId, sql.tenant, source.stockIdentityId) == "t")
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Resolve reservations, assignments and work-order custody before adjusting this identity")
            val missing = source.copy(condition = WarehouseCondition.QUARANTINE)
            val held = PostingProjection(sql).readLocked(missing)
            if (held != null && held.status != InventoryStatus.LOST)
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "Existing quarantine stock requires inspection rather than a count adjustment")
            if (delta > 0 && (held == null || held.quantity.quantityBase < delta || sql.value("""SELECT count(*) FROM inventory_movement_leg leg
                JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
                WHERE leg.tenant_id=? AND leg.stock_identity_id=? AND leg.location_id=? AND leg.direction='IN'
                    AND leg.status='LOST' AND movement.kind='COUNT_VARIANCE' AND movement.operation_namespace='warehouse.approval.effect'""",
                    sql.tenant, source.stockIdentityId, source.locationId) == "0"))
                masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED, "An increase requires previously recorded count-missing units of this identity; receive or reconcile unknown stock separately")
            val line = requireNotNull(sql.value("SELECT id FROM inventory_count_entry WHERE tenant_id=? AND document_id=? AND balance_id=?",
                sql.tenant, session.view.id, entry.balanceId)).let(UUID::fromString)
            val quantity = StockQuantity.of(if (delta < 0) -delta else delta, StockUnit.EA)
            if (delta < 0) listOf(PostingLeg(LegDirection.OUT, source, quantity, line, InventoryStatus.AVAILABLE),
                PostingLeg(LegDirection.IN, missing, quantity, line, InventoryStatus.LOST))
            else listOf(PostingLeg(LegDirection.OUT, missing, quantity, line, InventoryStatus.LOST),
                PostingLeg(LegDirection.IN, source, quantity, line, InventoryStatus.AVAILABLE))
        }
    }
}
