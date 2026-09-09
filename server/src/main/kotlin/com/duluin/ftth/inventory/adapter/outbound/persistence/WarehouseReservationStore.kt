package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.service.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class WarehouseReservationStore(private val jdbc: WarehouseCommandJdbc) {
    fun now(): Instant = jdbc.execute { sql -> sql.query("SELECT clock_timestamp()") { it.getTimestamp(1).toInstant() }.single() }
    fun demand(id: UUID): ReservationDemand = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_document WHERE tenant_id=? AND id=? AND kind='DEMAND'", sql.tenant, id) {
            ReservationDemand(id, it.getLong("revision"), it.optionalUuid("work_order_id") ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED),
                it.getLong("plan_revision"), it.getTimestamp("submitted_at")?.toInstant() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED),
                it.getString("state"), it.uuid("actor_id"), it.getLong("cutover_epoch"))
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }
    fun documents(workOrder: UUID): List<UUID> = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_document WHERE tenant_id=? AND work_order_id=? AND kind='DEMAND' ORDER BY id LIMIT 101", sql.tenant, workOrder) { it.uuid("id") }
    }
    fun lockDocuments(ids: Collection<UUID>) = jdbc.execute { sql ->
        ids.distinct().sortedBy(UUID::toString).forEach { id ->
            if (sql.value("SELECT id FROM inventory_document WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id) == null) sql.fail(WarehouseErrorCode.NOT_FOUND)
        }
    }
    fun lines(document: ReservationDemand, requireLatest: Boolean = true): List<ReservationDemandLine> = jdbc.execute { sql ->
        val rows = sql.query("""SELECT demand.*,line.id plan_line_id FROM inventory_document_line demand
            JOIN inventory_material_plan plan ON plan.tenant_id=demand.tenant_id AND plan.work_order_id=? AND plan.plan_revision=?
            JOIN inventory_material_plan_line line ON line.tenant_id=plan.tenant_id AND line.plan_id=plan.id AND line.line_number=demand.line_number
            WHERE demand.tenant_id=? AND demand.document_id=? AND plan.state='SUBMITTED' AND plan.material_mode='MATERIAL_REQUIRED'
            AND line.sku_id=demand.sku_id AND line.base_unit=demand.base_unit AND line.quantity_base=demand.quantity_base AND line.continuous_cut=demand.continuous_cut
            AND (? OR NOT EXISTS (SELECT FROM inventory_material_plan newer WHERE newer.tenant_id=plan.tenant_id AND newer.work_order_id=plan.work_order_id AND newer.plan_revision>plan.plan_revision))
            ORDER BY demand.line_number LIMIT 101""", document.workOrder, document.planRevision, sql.tenant, document.id, !requireLatest) {
            ReservationDemandLine(it.uuid("id"), it.uuid("plan_line_id"), it.uuid("sku_id"), StockUnit.valueOf(it.getString("base_unit")),
                it.getLong("quantity_base"), it.getBoolean("continuous_cut"), it.getString("tracking"))
        }
        val count = sql.value("SELECT count(*) FROM inventory_document_line WHERE tenant_id=? AND document_id=?", sql.tenant, document.id)!!.toLong()
        if (rows.isEmpty() || rows.size > 100 || rows.size.toLong() != count) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        rows
    }
    fun rows(document: UUID): List<ReservationChange> = jdbc.execute { sql ->
        sql.query("""SELECT reservation.* FROM inventory_reservation reservation JOIN inventory_document_line line
            ON line.tenant_id=reservation.tenant_id AND line.id=reservation.document_line_id
            WHERE reservation.tenant_id=? AND line.document_id=? ORDER BY reservation.id""", sql.tenant, document) {
            val unit = StockUnit.valueOf(it.getString("base_unit"))
            ReservationChange(it.uuid("id"), it.uuid("document_line_id"), PostingDimension(it.uuid("sku_id"), it.uuid("stock_identity_id"),
                it.optionalUuid("lot_id"), it.uuid("location_id"), it.uuid("custodian_id"), OwnerKind.valueOf(it.getString("custodian_kind")),
                WarehouseCondition.valueOf(it.getString("condition")), AssetLegalOwner.valueOf(it.getString("legal_owner"))), it.getLong("revision"),
                StockQuantity.of(it.getLong("reserved_unpicked_base"), unit), StockQuantity.of(it.getLong("reserved_picked_base"), unit),
                it.getTimestamp("expires_at").toInstant(), ReservationState.valueOf(it.getString("state")))
        }
    }
    fun candidates(skus: Set<UUID>): List<ReservationCandidate> = jdbc.execute { sql ->
        sql.query("""SELECT balance.*,segment.revision stock_revision,coalesce(lot.received_at,
            (SELECT min(server_received_at) FROM inventory_movement movement WHERE movement.tenant_id=source.tenant_id
                AND movement.document_id=source.id AND movement.kind='RECEIVE' AND movement.state='APPLIED')) received_at,
            source.id origin_document,origin.id origin_line,source.revision origin_revision,
            balance.quantity_base::numeric-coalesce((SELECT sum(reserved_unpicked_base::numeric+reserved_picked_base::numeric)
                FROM inventory_reservation reservation WHERE reservation.tenant_id=balance.tenant_id AND reservation.state='OPEN'
                AND reservation.stock_identity_id=balance.stock_identity_id AND reservation.location_id=balance.location_id
                AND reservation.custodian_id=balance.custody_owner_id AND reservation.custodian_kind=balance.custody_owner_kind
                AND reservation.condition=balance.condition AND reservation.legal_owner=balance.legal_owner),0) available
            FROM inventory_balance_projection balance JOIN inventory_segment segment ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
            JOIN inventory_sku sku ON sku.tenant_id=balance.tenant_id AND sku.id=balance.sku_id
            JOIN inventory_location location ON location.tenant_id=balance.tenant_id AND location.id=balance.location_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=segment.tenant_id AND lot.id=segment.lot_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
            JOIN inventory_document_line origin ON origin.tenant_id=segment.tenant_id AND origin.id=coalesce(lot.origin_document_line_id,asset.origin_document_line_id)
            JOIN inventory_document source ON source.tenant_id=origin.tenant_id AND source.id=origin.document_id
            WHERE balance.tenant_id=? AND balance.sku_id=ANY(?) AND balance.warehouse_admission='VERIFIED'
            AND segment.warehouse_admission='VERIFIED' AND segment.state='ACTIVE' AND sku.state='ACTIVE'
            AND balance.status='AVAILABLE' AND balance.condition='SERVICEABLE' AND balance.legal_owner='ISP' AND balance.quantity_base>0
            AND location.state='ACTIVE' AND location.issue_eligible AND sku.base_unit=balance.base_unit AND segment.base_unit=balance.base_unit
            AND ((location.kind IN ('WAREHOUSE','BIN') AND balance.custody_owner_kind='WAREHOUSE') OR
                (location.kind='TECHNICIAN' AND balance.custody_owner_kind='TECHNICIAN') OR (location.kind='VEHICLE' AND balance.custody_owner_kind='VEHICLE'))
            ORDER BY received_at,segment.id,balance.id LIMIT 2001""", sql.tenant, sql.connection.createArrayOf("uuid", skus.toTypedArray())) {
            ReservationCandidate(PostingDimension(it.uuid("sku_id"), it.uuid("stock_identity_id"), it.optionalUuid("lot_id"), it.uuid("location_id"),
                it.uuid("custody_owner_id"), OwnerKind.valueOf(it.getString("custody_owner_kind")), WarehouseCondition.SERVICEABLE, AssetLegalOwner.ISP),
                StockUnit.valueOf(it.getString("base_unit")), it.getBigDecimal("available").longValueExact(), it.getTimestamp("received_at").toInstant(),
                it.uuid("origin_document"), it.uuid("origin_line"), it.getLong("origin_revision"), it.getLong("stock_revision"))
        }.also { if (it.size > 2000) sql.fail(WarehouseErrorCode.MALFORMED_REQUEST) }
    }
    fun lockStock(candidates: List<ReservationCandidate>, rows: List<ReservationChange>) = jdbc.execute { sql ->
        val dimensions = (candidates.map { it.dimension } + rows.map { it.dimension }).distinct()
        dimensions.map { it.locationId }.distinct().sortedBy(UUID::toString).forEach { sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND id=? FOR SHARE", sql.tenant, it) }
        dimensions.mapNotNull { it.lotId }.distinct().sortedBy(UUID::toString).forEach { sql.value("SELECT id FROM inventory_lot WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, it) }
        dimensions.sortedBy { it.orderKey() }.map { it.stockIdentityId }.distinct().forEach {
            sql.value("SELECT id FROM inventory_segment WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, it)
        }
    }
    fun bind(changes: List<ReservationChange>, lines: List<ReservationDemandLine>, candidates: List<ReservationCandidate>, operation: UUID) = jdbc.execute { sql ->
        changes.filter { it.expectedRevision == null }.forEach { change ->
            val source = candidates.single { it.dimension == change.dimension }
            sql.update("""INSERT INTO inventory_reservation_allocation(id,tenant_id,reservation_id,plan_line_id,operation_id,origin_line_id,origin_revision,stock_revision)
                VALUES (?,?,?,?,?,?,?,?)""", UUID.randomUUID(), sql.tenant, change.id, lines.single { it.id == change.documentLineId }.planLineId,
                operation, source.originLine, source.originRevision, source.stockRevision)
        }
    }
    fun snapshot(operation: UUID, lines: List<ReservationLineSupply>) = jdbc.execute { sql ->
        lines.forEach { line -> sql.update("""INSERT INTO inventory_demand_supply_snapshot(id,tenant_id,operation_id,document_line_id,plan_line_id,
            requested_base,reserved_unpicked_base,reserved_picked_base,backorder_base) VALUES (?,?,?,?,?,?,?,?,?)""", UUID.randomUUID(), sql.tenant, operation,
            line.demandLineId, line.planLineId, line.requestedBase.toLong(), line.reservedUnpickedBase.toLong(), line.reservedPickedBase.toLong(), line.backorderBase.toLong()) }
    }
    fun allocations(document: ReservationDemand): List<ReservationAllocation> = jdbc.execute { sql ->
        val bindings = sql.query("""SELECT allocation.*,reservation.*,allocation.id allocation_id,reservation.revision reservation_revision,
            document.customer_id,operation.actor_id,sku.name item_category FROM inventory_reservation_allocation allocation
            JOIN inventory_reservation reservation ON reservation.tenant_id=allocation.tenant_id AND reservation.id=allocation.reservation_id
            JOIN inventory_document_line line ON line.tenant_id=reservation.tenant_id AND line.id=reservation.document_line_id
            JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
            JOIN inventory_operation operation ON operation.tenant_id=allocation.tenant_id AND operation.id=allocation.operation_id
            JOIN inventory_sku sku ON sku.tenant_id=reservation.tenant_id AND sku.id=reservation.sku_id
            WHERE allocation.tenant_id=? AND line.document_id=? ORDER BY allocation.created_at,allocation.id""", sql.tenant, document.id) {
            ReservationAllocation(it.uuid("allocation_id"), it.uuid("reservation_id"), it.getLong("reservation_revision"), document.id, document.revision,
                it.uuid("document_line_id"), it.uuid("plan_line_id"), document.planRevision, document.workOrder, it.uuid("stock_identity_id"), it.optionalUuid("lot_id"),
                it.uuid("sku_id"), it.uuid("location_id"), it.uuid("origin_line_id"), it.getLong("origin_revision"), it.getLong("stock_revision"),
                it.getLong("reserved_unpicked_base").toString(), it.getLong("reserved_picked_base").toString(), WarehouseBaseUnit.valueOf(it.getString("base_unit")),
                it.getString("state"), it.getTimestamp("expires_at").toInstant(), it.optionalUuid("customer_id"), it.uuid("actor_id"), it.getString("item_category"))
        }
        if (bindings.size != rows(document.id).size) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        bindings
    }
    fun due(): UUID? = jdbc.execute { sql ->
        sql.query("""SELECT line.document_id FROM inventory_reservation reservation JOIN inventory_document_line line
            ON line.tenant_id=reservation.tenant_id AND line.id=reservation.document_line_id
            WHERE reservation.tenant_id=? AND reservation.state='OPEN' AND reservation.reserved_unpicked_base>0
            AND reservation.expires_at<=clock_timestamp() ORDER BY reservation.expires_at,reservation.id LIMIT 1""", sql.tenant) { it.uuid("document_id") }.singleOrNull()
    }
}
