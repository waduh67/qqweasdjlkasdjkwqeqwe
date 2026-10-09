package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReferenceStockStore(private val jdbc: WarehouseCommandJdbc) {
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
