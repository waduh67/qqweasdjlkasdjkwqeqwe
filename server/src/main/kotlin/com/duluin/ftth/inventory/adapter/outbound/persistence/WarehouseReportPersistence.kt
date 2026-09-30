package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import com.duluin.ftth.inventory.application.port.inbound.WarehouseReportKind
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WarehouseReportPersistence(private val jdbc: WarehouseCommandJdbc) {
    fun report(kind: WarehouseReportKind, filter: WarehouseQueryFilter, access: WarehouseQueryAccess,
        readableWorkOrders: Set<UUID>, workOrder: UUID? = null): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter, access)
        val statement = when (kind) {
            WarehouseReportKind.STOCK_CARD -> ledger + card(query)
            WarehouseReportKind.MOVEMENTS -> ledger + movements(query)
            WarehouseReportKind.CUSTODY_AGING -> ledger + custody(query, false)
            WarehouseReportKind.TRANSIT_BACKLOG -> ledger + custody(query, true)
            WarehouseReportKind.LOAN_ASSETS -> assignments(query, "LOAN")
            WarehouseReportKind.SOLD_ASSETS -> assignments(query, "SALE")
            WarehouseReportKind.WORK_ORDER_COSTS -> ledger + warehouseReportCosts(query)
            WarehouseReportKind.STOCK, WarehouseReportKind.UNKNOWN_STOCK -> error("Stock uses the shared physical stock projection")
        }
        val workOrders = sql.connection.createArrayOf("uuid", readableWorkOrders.toTypedArray())
        if (kind == WarehouseReportKind.WORK_ORDER_COSTS) query.result(reportWorkOrders + statement, workOrders, workOrder, workOrder)
        else query.result(reportWorkOrders + statement, workOrders)
    }

    fun serialChain(id: UUID, filter: WarehouseQueryFilter, access: WarehouseQueryAccess): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter, access)
        query.result(""", target AS (SELECT asset.id FROM inventory_serialized_asset asset,request
            WHERE asset.tenant_id=request.tenant AND asset.id=? AND asset.warehouse_admission='VERIFIED'
            AND asset.location_id IN (SELECT id FROM visible_locations)
            AND (request.serial IS NULL OR asset.id IN (SELECT id FROM resolved_serial_asset)))""" + ledger +
            movements(query, "AND event.stock_identity_id IN (SELECT id FROM target)", true), id)
    }

    private fun movements(query: WarehouseQuerySql, extra: String = "", target: Boolean = false): String = query.page(
        "SELECT event.* FROM report_ledger event,request WHERE ${WarehouseQueryPredicates.eventHistory} AND $serialFilter $extra",
        movementJson, order(query), target)

    private fun card(query: WarehouseQuerySql): String = """, card_events AS (SELECT ledger.*,
        sum(delta) OVER (PARTITION BY sku_id,location_id,base_unit ORDER BY created_at,id ROWS UNBOUNDED PRECEDING) closing
        FROM report_ledger ledger,request WHERE (request.sku IS NULL OR ledger.sku_id=request.sku)
        AND (request.location IS NULL OR ledger.location_id=request.location)
        AND (request.serial IS NULL OR ledger.stock_identity_id IN (SELECT id FROM resolved_serial_asset))
        AND (request.status IS NULL OR ledger.status=request.status)
        AND (request.condition IS NULL OR ledger.condition=request.condition)
        AND (request.owner IS NULL OR ledger.legal_owner=request.owner))""" + query.page(
        """SELECT event.* FROM card_events event,request WHERE ${WarehouseQueryPredicates.eventHistory}""",
        """$movementJson || jsonb_build_object('openingQuantityBase',(closing-delta)::text,'closingQuantityBase',closing::text)""", order(query))

    private fun custody(query: WarehouseQuerySql, transit: Boolean): String = """, custody_events AS (
        SELECT ledger.*,sum(delta) OVER (PARTITION BY stock_identity_id,location_id,custody_owner_id,custody_owner_kind,status,condition,legal_owner
            ORDER BY created_at,id ROWS UNBOUNDED PRECEDING) running FROM report_ledger ledger),
        custody_entries AS (SELECT stock_identity_id,location_id,custody_owner_id,custody_owner_kind,status,condition,legal_owner,
            max(created_at) entered_at FROM custody_events WHERE delta>0 AND running-delta=0
            GROUP BY stock_identity_id,location_id,custody_owner_id,custody_owner_kind,status,condition,legal_owner)""" + query.page(
        """SELECT position.*,position.sku_name name,entry.entered_at FROM dimension_filtered_positions position
            LEFT JOIN custody_entries entry ON entry.stock_identity_id=position.stock_identity_id AND entry.location_id=position.location_id
            AND entry.custody_owner_id=position.custody_owner_id AND entry.custody_owner_kind=position.custody_owner_kind
            AND entry.status=position.status AND entry.condition=position.condition AND entry.legal_owner=position.legal_owner,request
            WHERE position.warehouse_admission='VERIFIED' AND position.quantity_base>0 AND position.status NOT IN ('CONSUMED','LOST','DISPOSED')
            AND (${if (transit) "position.status='IN_TRANSIT' OR position.custody_owner_kind='TRANSIT'" else "position.custody_owner_kind IN ('TECHNICIAN','VEHICLE','CUSTOMER')"})
            AND (request.since IS NULL OR entry.entered_at>=request.since) AND (request.until IS NULL OR entry.entered_at<request.until)""",
        """jsonb_build_object('id',id,'skuId',sku_id,'skuCode',sku_code,'name',name,'stockIdentityId',stock_identity_id,
            'serial',serial_number,'locationId',location_id,'locationName',location_name,'custodianId',custody_owner_id,
            'custodianKind',custody_owner_kind,'status',status,'condition',condition,'legalOwner',legal_owner,
            'quantity',${queryQuantity("quantity_base", "base_unit")},'enteredAt',${queryTime("entered_at")},
            'ageSeconds',floor(extract(epoch FROM transaction_timestamp()-entered_at))::text)""",
        if (query.filter.sort == "name") "name" else if (query.filter.sort == "createdAt") "entered_at" else "id")

    private fun assignments(query: WarehouseQuerySql, mode: String): String = query.page(
        """SELECT assignment.id,assignment.asset_id,assignment.work_order_id,assignment.revision,assignment.started_at created_at,
            assignment.ended_at,assignment.ownership_mode,assignment.legal_owner,asset.warehouse_sku_id sku_id,sku.name,
            asset.serial_number,asset.status,asset.location_id,acceptance.id handover_id,
            CASE WHEN loss.request_id IS NOT NULL THEN 'APPROVED_LOSS' WHEN removal.id IS NOT NULL THEN 'RECOVERED'
                WHEN assignment.ended_at IS NOT NULL THEN 'CLOSED' WHEN acceptance.id IS NULL THEN 'PENDING_HANDOVER' ELSE 'ACTIVE' END assignment_state
            FROM inventory_asset_assignment assignment JOIN inventory_serialized_asset asset ON asset.tenant_id=assignment.tenant_id AND asset.id=assignment.asset_id
            JOIN inventory_sku sku ON sku.tenant_id=asset.tenant_id AND sku.id=asset.warehouse_sku_id
            JOIN report_work_orders work ON work.id=assignment.work_order_id
            LEFT JOIN inventory_asset_handover acceptance ON acceptance.tenant_id=assignment.tenant_id AND acceptance.assignment_id=assignment.id
            LEFT JOIN inventory_asset_removal removal ON removal.tenant_id=assignment.tenant_id AND removal.assignment_id=assignment.id
            LEFT JOIN inventory_asset_loss_effect loss ON loss.tenant_id=assignment.tenant_id AND loss.assignment_id=assignment.id,request
            WHERE assignment.tenant_id=request.tenant AND assignment.warehouse_admission='VERIFIED' AND assignment.ownership_mode='$mode'
            AND asset.location_id IN (SELECT id FROM visible_locations)
            AND (request.sku IS NULL OR asset.warehouse_sku_id=request.sku) AND (request.serial IS NULL OR asset.id IN (SELECT id FROM resolved_serial_asset))
            AND (request.location IS NULL OR asset.location_id=request.location) AND (request.status IS NULL OR asset.status=request.status)
            AND (request.condition IS NULL OR asset.condition=request.condition) AND (request.owner IS NULL OR assignment.legal_owner=request.owner)
            AND (request.since IS NULL OR assignment.started_at>=request.since) AND (request.until IS NULL OR assignment.started_at<request.until)""",
        """jsonb_build_object('id',id,'assignmentId',id,'revision',revision,'assetId',asset_id,'skuId',sku_id,'name',name,
            'serial',serial_number,'workOrderId',work_order_id,'handoverId',handover_id,'ownershipMode',ownership_mode,
            'legalOwner',legal_owner,'status',status,'locationId',location_id,'assignmentState',assignment_state,
            'startedAt',${queryTime("created_at")},'endedAt',${queryTime("ended_at")})""",
        if (query.filter.sort == "name") "name" else order(query))

    private fun order(query: WarehouseQuerySql) = if (query.filter.sort == "id") "id" else "created_at"

    companion object {
        internal const val reportWorkOrders = ", report_work_orders AS (SELECT unnest(?::uuid[]) id)"
        internal val serialFilter = "(request.serial IS NULL OR event.stock_identity_id IN (SELECT id FROM resolved_serial_asset))"
        internal val ledger = """, report_ledger AS MATERIALIZED (
            SELECT leg.id,leg.tenant_id,leg.movement_id,leg.document_line_id,leg.sku_id,leg.stock_identity_id,leg.lot_id,
                leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,leg.status,leg.condition,leg.legal_owner,
                leg.direction,leg.quantity_base,leg.base_unit,movement.server_received_at created_at,movement.kind movement_kind,movement.operation_id,
                movement.document_id,movement.document_revision,movement.compensates_movement_id,document.code document_code,
                document.work_order_id,document.work_order_code_snapshot,sku.name,sku.code sku_code,location.name location_name,
                CASE leg.direction WHEN 'IN' THEN leg.quantity_base::numeric ELSE -leg.quantity_base::numeric END delta,
                coalesce(asset.origin_document_line_id,lot.origin_document_line_id) origin_line_id,
                asset.serial_number,origin.cost_total_minor,origin.cost_basis_quantity_base,origin.currency,
                origin.location_id origin_location_id,origin.destination_location_id origin_destination_id
            FROM inventory_movement_leg leg JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            JOIN inventory_document document ON document.tenant_id=movement.tenant_id AND document.id=movement.document_id
            JOIN visible_locations location ON location.id=leg.location_id
            JOIN inventory_sku sku ON sku.tenant_id=leg.tenant_id AND sku.id=leg.sku_id
            JOIN inventory_segment segment ON segment.tenant_id=leg.tenant_id AND segment.id=leg.stock_identity_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=segment.tenant_id AND lot.id=segment.lot_id
            LEFT JOIN inventory_document_line origin ON origin.tenant_id=leg.tenant_id AND origin.id=coalesce(asset.origin_document_line_id,lot.origin_document_line_id),request
            WHERE leg.tenant_id=request.tenant AND leg.warehouse_admission='VERIFIED' AND movement.state='APPLIED'
            AND leg.status<>'RECEIPT_SOURCE')"""

        internal val movementJson = """jsonb_build_object('id',id,'postingId',movement_id,'movementKind',movement_kind,'documentId',document_id,
            'documentCode',document_code,'documentRevision',document_revision,'lineId',document_line_id,
            'compensatesPostingId',compensates_movement_id,'recordedAt',${queryTime("created_at")},'direction',direction,
            'skuId',sku_id,'skuCode',sku_code,'name',name,'stockIdentityId',stock_identity_id,'serial',serial_number,
            'locationId',location_id,'locationName',location_name,'custodianId',custody_owner_id,'custodianKind',custody_owner_kind,
            'status',status,'condition',condition,'legalOwner',legal_owner,'quantity',${queryQuantity("quantity_base", "base_unit")})"""
    }
}
