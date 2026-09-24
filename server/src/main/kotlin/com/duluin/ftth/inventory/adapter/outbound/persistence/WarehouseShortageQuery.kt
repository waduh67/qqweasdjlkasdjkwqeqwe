package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository

@Repository
class WarehouseShortageQuery(private val jdbc: WarehouseCommandJdbc) {
    /** Active SKUs with no admitted stock still have zero available, provided there is a visible location. */
    fun list(filter: WarehouseQueryFilter, access: WarehouseQueryAccess): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter, access)
        query.result(""", quantities AS (
            SELECT sku_id,sum(available) available FROM filtered_positions
            WHERE warehouse_admission='VERIFIED' AND legal_owner='ISP' GROUP BY sku_id)""" + query.page(
            """SELECT sku.id,sku.code,sku.name,sku.base_unit,sku.minimum_quantity_base,
                coalesce(quantities.available,0) available,sku.minimum_quantity_base-coalesce(quantities.available,0) shortage
                FROM inventory_sku sku LEFT JOIN quantities ON quantities.sku_id=sku.id,request
                WHERE sku.tenant_id=request.tenant AND sku.state='ACTIVE' AND sku.minimum_quantity_base>0
                AND (request.sku IS NULL OR sku.id=request.sku)
                AND EXISTS (SELECT FROM visible_locations WHERE request.location IS NULL OR id=request.location)
                AND coalesce(quantities.available,0)<sku.minimum_quantity_base""",
            """jsonb_build_object('id',id,'skuId',id,'skuCode',code,'name',name,'baseUnit',base_unit,
                'availableBase',available::text,'minimumBase',minimum_quantity_base::text,'shortageBase',shortage::text)""", "name"))
    }
}
