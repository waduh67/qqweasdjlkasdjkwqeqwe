package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WarehouseAssetQueries(private val jdbc: WarehouseCommandJdbc) {
    fun assets(filter: WarehouseQueryFilter, access: WarehouseQueryAccess, id: UUID? = null, history: Boolean = false): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter, access)
        val base = """SELECT asset.id,asset.id asset_id,asset.warehouse_sku_id sku_id,sku.name,sku.code,asset.serial_number,asset.mac_address,
            asset.status,asset.condition,asset.legal_owner,asset.location_id,asset.custody_owner_id,asset.custody_owner_kind,
            asset.quantity_base,asset.base_unit,asset.warehouse_admission,asset.origin_document_line_id,asset.created_at,asset.installed_onu_id
            FROM inventory_serialized_asset asset LEFT JOIN inventory_sku sku ON sku.tenant_id=asset.tenant_id AND sku.id=asset.warehouse_sku_id,request
            WHERE asset.tenant_id=request.tenant AND asset.location_id IN (SELECT id FROM visible_locations)
            AND (asset.warehouse_admission='VERIFIED' ${if (access.provenance && id != null) "OR asset.warehouse_admission='LEGACY_UNRESOLVED'" else ""})
            AND (request.sku IS NULL OR asset.warehouse_sku_id=request.sku) AND (request.serial IS NULL OR asset.canonical_serial=request.serial)
            ${if (history) "" else """AND (request.location IS NULL OR asset.location_id=request.location) AND (request.status IS NULL OR asset.status=request.status)
            AND (request.condition IS NULL OR asset.condition=request.condition) AND (request.owner IS NULL OR asset.legal_owner=request.owner)
            AND (request.since IS NULL OR asset.created_at>=request.since) AND (request.until IS NULL OR asset.created_at<request.until)"""}"""
        val origin = queryOrigin("origin_document_line_id")
        val cost = if (access.cost) """coalesce((SELECT ${queryCost("line", true)} FROM inventory_document_line line,request
            WHERE line.tenant_id=request.tenant AND line.id=matches.origin_document_line_id AND $origin IS NOT NULL),'{}'::jsonb)""" else "'{}'::jsonb"
        val json = """jsonb_build_object('id',id,'assetId',asset_id,'skuId',sku_id,'skuCode',code,'name',name,'serial',serial_number,'mac',mac_address,
            'status',status,'condition',condition,'legalOwner',legal_owner,'locationId',location_id,'custodianId',custody_owner_id,
            'custodianKind',custody_owner_kind,'quantity',${queryQuantity("quantity_base", "base_unit")},'admission',warehouse_admission,
            'installedOnuId',installed_onu_id,'origin',$origin) || $cost"""
        when {
            history -> query.result(",target AS ($base AND asset.id=?),target_segments AS (SELECT id FROM target)" + warehouseTimeline(query), id)
            id != null -> query.result(",matches AS ($base AND asset.id=?) SELECT ($json)::text FROM matches", id)
            else -> query.result(query.page(base, json, when (filter.sort) { "name" -> "name"; "createdAt" -> "created_at"; else -> "id" }))
        }
    }
}
