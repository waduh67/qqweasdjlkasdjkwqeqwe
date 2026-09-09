package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WarehouseQueryPersistence(private val jdbc: WarehouseCommandJdbc) {
    fun legacyStock(access: WarehouseQueryAccess): String = jdbc.execute { sql ->
        WarehouseQuerySql(sql, WarehouseQueryFilter(), access).result(""", counts AS (
            SELECT asset.sku_id,asset.location_id,asset.status,count(*) quantity FROM inventory_serialized_asset asset,request
            WHERE asset.tenant_id=request.tenant AND asset.location_id IN (SELECT id FROM visible_locations)
            GROUP BY asset.sku_id,asset.location_id,asset.status), grouped AS (
            SELECT sku_id,location_id,jsonb_object_agg(status,quantity) quantities FROM counts GROUP BY sku_id,location_id)
            SELECT coalesce(jsonb_agg(jsonb_build_object('skuId',sku_id,'locationId',location_id,'quantities',quantities)
                ORDER BY sku_id,location_id),'[]'::jsonb)::text FROM grouped""")
    }

    fun history(filter: WarehouseQueryFilter, access: WarehouseQueryAccess, id: UUID): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter, access)
        query.result(""",target AS (SELECT * FROM scoped_positions WHERE id=? AND warehouse_admission='VERIFIED'),
            target_segments AS (SELECT stock_identity_id id FROM target)""" + warehouseTimeline(query), id)
    }

    fun stock(filter: WarehouseQueryFilter, access: WarehouseQueryAccess): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter, access)
        val rows = """SELECT sku_id id,sku_id,sku_code,sku_name name,tracking,base_unit,sum(quantity_base::numeric) physical,
            sum(unpicked) unpicked,sum(picked) picked,sum(available) available,min(created_at) created_at
            FROM filtered_positions WHERE warehouse_admission='VERIFIED' AND legal_owner<>'UNKNOWN' GROUP BY sku_id,sku_code,sku_name,tracking,base_unit"""
        fun buckets(column: String) = """(SELECT jsonb_object_agg(bucket,quantity::text) FROM (SELECT $column bucket,sum(quantity_base::numeric) quantity
            FROM filtered_positions position WHERE position.sku_id=matches.sku_id AND position.warehouse_admission='VERIFIED' AND position.legal_owner<>'UNKNOWN' GROUP BY $column) grouped)"""
        val json = """jsonb_build_object('id',id,'skuId',sku_id,'skuCode',sku_code,'name',name,'tracking',tracking,
            'physical',${queryQuantity("physical", "base_unit")},'reservedUnpicked',${queryQuantity("unpicked", "base_unit")},
            'reservedPicked',${queryQuantity("picked", "base_unit")},'available',${queryQuantity("available", "base_unit")},
            'statusBuckets',${buckets("status")},'conditionBuckets',${buckets("condition")},'ownerBuckets',${buckets("legal_owner")})"""
        query.result(query.page(rows, json, order(filter)))
    }

    fun positions(filter: WarehouseQueryFilter, access: WarehouseQueryAccess, id: UUID? = null): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter, access)
        val rows = "SELECT *,sku_name name FROM filtered_positions WHERE warehouse_admission='VERIFIED' AND legal_owner<>'UNKNOWN'"
        if (id == null) query.result(query.page(rows, positionJson, order(filter)))
        else query.result(",matches AS ($rows) SELECT ($positionJson)::text FROM matches WHERE id=?", id)
    }

    fun unknown(filter: WarehouseQueryFilter, access: WarehouseQueryAccess): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter, access)
        val rows = """SELECT position.id,position.sku_id,position.sku_name name,position.location_id,position.status,
            position.quantity::text raw_quantity,position.quantity_base::text quantity_base,position.base_unit,position.legal_owner,
            position.warehouse_admission,position.created_at,'BALANCE'::text source,position.serial_number
            FROM filtered_positions position WHERE position.warehouse_admission<>'VERIFIED' OR position.legal_owner='UNKNOWN'
            UNION ALL SELECT asset.id,asset.sku_id,NULL,asset.location_id,asset.status,NULL,asset.quantity_base::text,asset.base_unit,asset.legal_owner,
            asset.warehouse_admission,asset.created_at,'ASSET',asset.serial_number FROM inventory_serialized_asset asset,request
            WHERE asset.tenant_id=request.tenant AND asset.warehouse_admission='LEGACY_UNRESOLVED' AND asset.location_id IN (SELECT id FROM visible_locations)
            AND NOT EXISTS (SELECT FROM scoped_positions WHERE stock_identity_id=asset.id OR item_id=asset.id)
            AND (request.sku IS NULL OR asset.sku_id=request.sku) AND (request.serial IS NULL OR warehouse_canonical_serial(asset.serial_number)=request.serial)
            AND (request.location IS NULL OR asset.location_id=request.location) AND (request.status IS NULL OR asset.status=request.status)
            AND (request.condition IS NULL OR asset.condition=request.condition) AND (request.owner IS NULL OR asset.legal_owner=request.owner)
            AND (request.since IS NULL OR asset.created_at>=request.since) AND (request.until IS NULL OR asset.created_at<request.until)"""
        query.result(query.page(rows, """jsonb_build_object('id',id,'source',source,'skuId',sku_id,'name',name,'locationId',location_id,
            'status',status,'rawQuantity',raw_quantity,'quantityBase',quantity_base,'baseUnit',base_unit,'legalOwner',legal_owner,
            'admission',warehouse_admission,'serial',serial_number,'available',false,'reason','UNVERIFIED_UNIT_OR_ORIGIN_OR_TITLE')""", order(filter)))
    }

    private fun order(filter: WarehouseQueryFilter) = when (filter.sort) { "name" -> "name"; "createdAt" -> "created_at"; else -> "id" }

    private val positionJson = """jsonb_build_object('id',id,'skuId',sku_id,'skuCode',sku_code,'name',sku_name,'tracking',tracking,
        'stockIdentityId',stock_identity_id,'lotId',lot_id,'serial',serial_number,'locationId',location_id,'locationName',location_name,
        'custodianId',custody_owner_id,'custodianKind',custody_owner_kind,'condition',condition,'legalOwner',legal_owner,'status',status,
        'physical',${queryQuantity("quantity_base", "base_unit")},'reservedUnpicked',${queryQuantity("unpicked", "base_unit")},
        'reservedPicked',${queryQuantity("picked", "base_unit")},'available',${queryQuantity("available", "base_unit")})"""
}
