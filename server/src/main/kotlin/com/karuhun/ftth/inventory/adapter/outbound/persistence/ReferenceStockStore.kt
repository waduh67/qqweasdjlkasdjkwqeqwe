package com.karuhun.ftth.inventory.adapter.outbound.persistence

import com.karuhun.ftth.inventory.*
import com.karuhun.ftth.inventory.application.port.inbound.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReferenceStockStore(private val jdbc: WarehouseCommandJdbc) {
    fun overview(roots: Map<UUID, UUID>): ReferenceStockOverview = jdbc.execute { sql ->
        if (roots.isEmpty()) return@execute ReferenceStockOverview(0, 0, 0, emptyList())
        val projection = """WITH visible_location(id,warehouse_id) AS (VALUES ${roots.keys.joinToString(",") { "(?::uuid,?::uuid)" }}),
            stock AS (SELECT balance.sku_id,visible.warehouse_id,sum(balance.quantity_base) quantity
                FROM inventory_balance_projection balance JOIN inventory_segment segment
                  ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
                JOIN visible_location visible ON visible.id=balance.location_id
                WHERE balance.tenant_id=?
                  AND balance.quantity_base>0 AND balance.warehouse_admission='VERIFIED'
                  AND segment.state='ACTIVE' AND balance.status='AVAILABLE' AND balance.custody_owner_kind='WAREHOUSE'
                GROUP BY balance.sku_id,visible.warehouse_id),
            positions AS (SELECT sku.id,sku.code,sku.name,sku.base_unit,sku.minimum_quantity_base,
                warehouse.id warehouse_id,coalesce(warehouse.name,warehouse.code) warehouse_name,coalesce(stock.quantity,0) quantity
                FROM inventory_sku sku CROSS JOIN inventory_location warehouse
                LEFT JOIN stock ON stock.sku_id=sku.id AND stock.warehouse_id=warehouse.id
                WHERE sku.tenant_id=? AND sku.state='ACTIVE' AND warehouse.tenant_id=sku.tenant_id
                  AND warehouse.state='ACTIVE' AND warehouse.id IN (SELECT warehouse_id FROM visible_location))"""
        val parameters = buildList<Any> {
            roots.forEach { (location, warehouse) -> add(location); add(warehouse) }
            add(sql.tenant); add(sql.tenant)
        }.toTypedArray()
        val counts = sql.query("""$projection SELECT count(*) FILTER (WHERE quantity>0 AND quantity>=minimum_quantity_base) safe,
            count(*) FILTER (WHERE quantity>0 AND quantity<minimum_quantity_base) low,
            count(*) FILTER (WHERE quantity=0) empty FROM positions""", *parameters) {
            Triple(it.getInt("safe"), it.getInt("low"), it.getInt("empty"))
        }.single()
        val rows = sql.query("""$projection SELECT *,quantity::text available FROM positions
            WHERE quantity=0 OR quantity<minimum_quantity_base ORDER BY quantity,code,warehouse_name,id,warehouse_id LIMIT 5""", *parameters) {
            ReferenceStockAttention(it.uuid("id"), it.getString("code"), it.getString("name"),
                it.uuid("warehouse_id"), it.getString("warehouse_name"), WarehouseBaseUnit.valueOf(it.getString("base_unit")),
                it.getString("available"), it.getLong("minimum_quantity_base").toString())
        }
        ReferenceStockOverview(counts.first, counts.second, counts.third, rows)
    }

    private val joins = """FROM inventory_balance_projection balance JOIN inventory_segment segment
        ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
        JOIN inventory_sku sku ON sku.tenant_id=balance.tenant_id AND sku.id=balance.sku_id
        JOIN inventory_location location ON location.tenant_id=balance.tenant_id AND location.id=balance.location_id
        LEFT JOIN app_user holder ON holder.tenant_id=balance.tenant_id AND holder.id=balance.custody_owner_id
        LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id"""
    private fun predicate(locations: Set<UUID>, holder: UUID?) = """balance.tenant_id=? AND balance.sku_id=?
        AND balance.location_id IN (${locations.joinToString(",") { "?" }})
        AND balance.quantity_base>0 AND balance.warehouse_admission='VERIFIED' AND segment.state='ACTIVE'
        AND location.state='ACTIVE' AND balance.status NOT IN ('CONSUMED','CUSTOMER_INSTALLED','DISPOSED')""" +
        if (holder == null) "" else " AND (balance.custody_owner_kind NOT IN ('TECHNICIAN','VEHICLE') OR balance.custody_owner_id=?)"

    fun quantities(sku: UUID, locations: Set<UUID>): Map<UUID, java.math.BigInteger> = jdbc.execute { sql ->
        if (locations.isEmpty()) return@execute emptyMap()
        sql.query("""SELECT balance.location_id,sum(balance.quantity_base)::text quantity $joins
            WHERE ${predicate(locations, null)} AND balance.status='AVAILABLE' AND balance.custody_owner_kind='WAREHOUSE'
            GROUP BY balance.location_id""", sql.tenant, sku, *locations.toTypedArray()) {
            it.uuid("location_id") to it.getString("quantity").toBigInteger()
        }.toMap()
    }

    fun technicianQuantity(sku: UUID, technician: UUID): java.math.BigInteger = jdbc.execute { sql ->
        requireNotNull(sql.value("""SELECT coalesce(sum(balance.quantity_base),0)::text $joins
            WHERE balance.tenant_id=? AND balance.sku_id=? AND balance.custody_owner_id=? AND balance.custody_owner_kind='TECHNICIAN'
            AND balance.quantity_base>0 AND balance.warehouse_admission='VERIFIED' AND segment.state='ACTIVE'
            AND balance.status='ISSUED' AND location.state='ACTIVE'""", sql.tenant, sku, technician)).toBigInteger()
    }

    fun positions(sku: UUID, locations: Set<UUID>, holder: UUID?, page: Int, size: Int, search: String?,
        holderKind: String?, availableOnly: Boolean): WarehousePage<ReferenceStockPosition> = jdbc.execute { sql ->
        if (locations.isEmpty()) return@execute WarehousePage(emptyList(), page, size, 0)
        val where = predicate(locations, holder) +
            " AND (sku.code ILIKE ? OR sku.name ILIKE ? OR coalesce(asset.serial_number,'') ILIKE ? OR coalesce(asset.mac_address,'') ILIKE ?)" +
            (if (holderKind == null) "" else " AND balance.custody_owner_kind=?") +
            (if (availableOnly) " AND balance.status='AVAILABLE' AND balance.custody_owner_kind='WAREHOUSE'" else "")
        val term = "%${search?.trim().orEmpty()}%"
        val values = buildList<Any?> {
            add(sql.tenant); add(sku); addAll(locations); if (holder != null) add(holder)
            repeat(4) { add(term) }; if (holderKind != null) add(holderKind)
        }.toTypedArray()
        val total = requireNotNull(sql.value("SELECT count(*) $joins WHERE $where", *values)).toLong()
        val rows = sql.query("""SELECT balance.*,sku.code sku_code,sku.name sku_name,sku.tracking,
            coalesce(location.name,location.code) location_name,holder.name holder_name,holder.email holder_email,
            asset.serial_number,asset.mac_address $joins WHERE $where
            ORDER BY location.code,holder.name,segment.id LIMIT ? OFFSET ?""", *values, size, page.toLong() * size) {
            val locationName = it.getString("location_name")
            ReferenceStockPosition(it.uuid("stock_identity_id"), sku, it.getString("sku_code"), it.getString("sku_name"),
                WarehouseTracking.valueOf(it.getString("tracking")), WarehouseBaseUnit.valueOf(it.getString("base_unit")),
                it.getLong("quantity_base").toString(), it.uuid("location_id"), locationName, it.uuid("custody_owner_id"),
                it.getString("holder_name") ?: locationName, it.getString("holder_email"), it.getString("custody_owner_kind"),
                it.getString("status"), it.getString("serial_number"), it.getString("mac_address"), it.getLong("revision"))
        }
        WarehousePage(rows, page, size, total)
    }
}
