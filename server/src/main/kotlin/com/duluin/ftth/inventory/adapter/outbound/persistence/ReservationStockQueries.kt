package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.application.service.ReservationCandidate
import com.duluin.ftth.inventory.application.service.ReservationDemandLine
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class ReservationStockQueries(private val jdbc: WarehouseCommandJdbc) {
    fun select(line: ReservationDemandLine, quantity: Long, identity: UUID?, access: WarehouseQueryAccess,
        deductions: Map<UUID, Long>): List<ReservationCandidate> {
        require(quantity > 0)
        val continuous = line.continuous && line.unit == StockUnit.MM
        val limit = if (continuous) 1 else minOf(quantity, 2001).toInt()
        val selection = if (continuous) "SELECT * FROM eligible WHERE available>=? ORDER BY received_at,stock_identity_id,id LIMIT 1" else
            """, bounded AS (SELECT * FROM eligible WHERE available>0 ORDER BY received_at,stock_identity_id,id LIMIT $limit),
                running AS (SELECT *,coalesce(sum(available) OVER (ORDER BY received_at,stock_identity_id,id ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0) prior
                    FROM bounded) SELECT * FROM running WHERE prior<? ORDER BY received_at,stock_identity_id,id"""
        return query(selection, line.sku, line.unit, identity, null, access, deductions, quantity).also {
            if (it.size > 2000) throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Allocation requires more than 2000 stock positions"))
        }
    }

    fun refresh(candidates: List<ReservationCandidate>): List<ReservationCandidate> = if (candidates.isEmpty()) emptyList() else
        query("SELECT * FROM eligible ORDER BY id", null, null, null, candidates.map { it.balanceId }, null, emptyMap())

    fun position(dimension: PostingDimension): ReservationCandidate = jdbc.execute { sql ->
        val id = sql.query("SELECT id FROM inventory_balance_projection WHERE ${PostingProjection.predicate}", sql.tenant, dimension.skuId,
            dimension.stockIdentityId, dimension.lotId, dimension.locationId, dimension.custodianId, dimension.custodianKind, dimension.condition, dimension.legalOwner) { it.uuid("id") }
            .singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        query("SELECT * FROM eligible", null, null, null, listOf(id), null, emptyMap()).singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }

    private fun query(selection: String, sku: UUID?, unit: StockUnit?, identity: UUID?, balances: List<UUID>?, access: WarehouseQueryAccess?,
        deductions: Map<UUID, Long>, vararg extra: Any?): List<ReservationCandidate> = jdbc.execute { sql ->
        fun array(ids: Collection<UUID>?) = ids?.let { sql.connection.createArrayOf("uuid", it.toTypedArray()) }
        fun scope(value: AuthorityScope?) = array((value as? AuthorityScope.Restricted)?.ids)
        sql.query(base + selection, sql.tenant, sku, unit, identity, array(balances), scope(access?.locations), scope(access?.areas),
            access?.sites?.let { jacksonObjectMapper().writeValueAsString(it) }, jacksonObjectMapper().writeValueAsString(deductions), *extra) {
            ReservationCandidate(PostingDimension(it.uuid("sku_id"), it.uuid("stock_identity_id"), it.optionalUuid("lot_id"), it.uuid("location_id"),
                it.uuid("custody_owner_id"), OwnerKind.valueOf(it.getString("custody_owner_kind")), WarehouseCondition.SERVICEABLE, AssetLegalOwner.ISP),
                StockUnit.valueOf(it.getString("base_unit")), it.getBigDecimal("available").longValueExact(), it.getTimestamp("received_at").toInstant(),
                it.uuid("origin_document"), it.uuid("origin_line"), it.getLong("origin_revision"), it.getLong("stock_revision"), it.uuid("id"))
        }
    }

    private val base = """WITH request AS (SELECT ?::uuid tenant,?::uuid sku,?::text unit,?::uuid identity,?::uuid[] balances,
        ?::uuid[] locations,?::uuid[] areas,?::jsonb sites,?::jsonb deductions), eligible AS (
        SELECT balance.*,segment.revision stock_revision,coalesce(lot.received_at,
            (SELECT min(server_received_at) FROM inventory_movement movement WHERE movement.tenant_id=source.tenant_id
                AND movement.document_id=source.id AND movement.kind='RECEIVE' AND movement.state='APPLIED')) received_at,
            source.id origin_document,origin.id origin_line,source.revision origin_revision,
            balance.quantity_base::numeric-coalesce((SELECT sum(reserved_unpicked_base::numeric+reserved_picked_base::numeric)
                FROM inventory_reservation reservation WHERE reservation.tenant_id=balance.tenant_id AND reservation.state='OPEN'
                AND reservation.sku_id=balance.sku_id AND reservation.base_unit=balance.base_unit
                AND reservation.stock_identity_id=balance.stock_identity_id AND reservation.lot_id IS NOT DISTINCT FROM balance.lot_id
                AND reservation.location_id=balance.location_id AND reservation.custodian_id=balance.custody_owner_id
                AND reservation.custodian_kind=balance.custody_owner_kind AND reservation.condition=balance.condition AND reservation.legal_owner=balance.legal_owner),0)
                -coalesce((request.deductions->>balance.id::text)::numeric,0) available
        FROM inventory_balance_projection balance JOIN inventory_segment segment ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
        JOIN inventory_sku sku ON sku.tenant_id=balance.tenant_id AND sku.id=balance.sku_id
        JOIN inventory_location location ON location.tenant_id=balance.tenant_id AND location.id=balance.location_id
        LEFT JOIN inventory_lot lot ON lot.tenant_id=segment.tenant_id AND lot.id=segment.lot_id
        LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
        JOIN inventory_document_line origin ON origin.tenant_id=segment.tenant_id AND origin.id=coalesce(lot.origin_document_line_id,asset.origin_document_line_id)
        JOIN inventory_document source ON source.tenant_id=origin.tenant_id AND source.id=origin.document_id CROSS JOIN request
        WHERE balance.tenant_id=request.tenant AND (request.sku IS NULL OR balance.sku_id=request.sku)
        AND (request.unit IS NULL OR balance.base_unit=request.unit) AND (request.identity IS NULL OR balance.stock_identity_id=request.identity)
        AND (request.balances IS NULL OR balance.id=ANY(request.balances))
        AND (request.locations IS NULL OR location.id=ANY(request.locations)) AND (request.areas IS NULL OR location.area_id=ANY(request.areas))
        AND (request.sites IS NULL OR NOT EXISTS (WITH RECURSIVE ancestors AS (
            SELECT parent.id,parent.parent_location_id,parent.site_id,parent.area_id,ARRAY[parent.id] path,false cycle
                FROM inventory_location parent WHERE parent.tenant_id=location.tenant_id AND parent.id=location.id
            UNION ALL SELECT parent.id,parent.parent_location_id,parent.site_id,parent.area_id,child.path||parent.id,parent.id=ANY(child.path)
                FROM inventory_location parent JOIN ancestors child ON parent.id=child.parent_location_id
                WHERE parent.tenant_id=location.tenant_id AND NOT child.cycle AND cardinality(child.path)<=32)
            SELECT FROM ancestors WHERE cycle OR cardinality(path)>32 OR area_id IS DISTINCT FROM location.area_id
                OR (SELECT count(DISTINCT site_id) FROM ancestors)>1
                OR (parent_location_id IS NOT NULL AND NOT EXISTS (SELECT FROM inventory_location parent WHERE parent.tenant_id=location.tenant_id AND parent.id=ancestors.parent_location_id))
                OR (site_id IS NOT NULL AND NOT EXISTS (SELECT FROM jsonb_each_text(request.sites) site WHERE site.key=site_id::text AND site.value IS NOT DISTINCT FROM area_id::text))))
        AND balance.warehouse_admission='VERIFIED' AND segment.warehouse_admission='VERIFIED' AND segment.state='ACTIVE' AND sku.state='ACTIVE'
        AND balance.status='AVAILABLE' AND balance.condition='SERVICEABLE' AND balance.legal_owner='ISP' AND balance.quantity_base>0
        AND location.state='ACTIVE' AND location.issue_eligible AND sku.base_unit=balance.base_unit AND segment.base_unit=balance.base_unit
        AND ((location.kind IN ('WAREHOUSE','BIN') AND balance.custody_owner_kind='WAREHOUSE') OR
            (location.kind='TECHNICIAN' AND balance.custody_owner_kind='TECHNICIAN') OR (location.kind='VEHICLE' AND balance.custody_owner_kind='VEHICLE'))) """
}
