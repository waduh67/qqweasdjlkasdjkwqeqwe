package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseCountQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun ids(filter: WarehouseCountFilter, access: WarehouseQueryAccess, actor: UUID): WarehousePage<UUID> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter.queryFilter(), access)
        val rows = """SELECT document.id,document.created_at FROM inventory_document document
            JOIN inventory_count_scope scope ON scope.tenant_id=document.tenant_id AND scope.id=document.id,request
            WHERE document.tenant_id=request.tenant AND scope.location_id IN (SELECT id FROM visible_locations WHERE state='ACTIVE')
                AND (document.actor_id=?::uuid OR EXISTS (SELECT FROM inventory_count_entry entry
                    WHERE entry.tenant_id=document.tenant_id AND entry.document_id=document.id AND entry.counter_id=?::uuid))
                AND (request.location IS NULL OR scope.location_id=request.location)
                AND (request.status IS NULL OR document.state=request.status)
                AND (request.since IS NULL OR document.created_at>=request.since)
                AND (request.until IS NULL OR document.created_at<request.until)
                AND (?::text IS NULL OR position(lower(?::text) IN lower(document.code))>0)
                AND ((request.sku IS NULL AND request.serial IS NULL) OR EXISTS (
                    SELECT FROM inventory_document_line line
                    LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=line.tenant_id AND asset.id=line.stock_identity_id
                    WHERE line.tenant_id=document.tenant_id AND line.document_id=document.id
                        AND (request.sku IS NULL OR line.sku_id=request.sku)
                        AND (request.serial IS NULL OR asset.canonical_serial=request.serial)))"""
        val result = mapper.readTree(query.result(query.page(rows, "jsonb_build_object('id',id)", "created_at"), actor, actor, filter.query, filter.query))
        WarehousePage(result.path("items").asSequence().map { UUID.fromString(it.path("id").asString()) }.toList(),
            filter.page, filter.size, result.path("totalElements").asLong())
    }

    fun positions(filter: WarehouseCountFilter, access: WarehouseQueryAccess, selectedIds: List<UUID>? = null): WarehousePage<WarehouseCountPositionOption> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter.queryFilter().copy(sort = "name", direction = "asc"), access)
        val rows = """SELECT balance.id,sku.name, jsonb_build_object('id',balance.id,'stockIdentityId',balance.stock_identity_id,
                'baseUnit',balance.base_unit,'item',${itemJson("sku.id")},
                'location',jsonb_build_object('id',location.id,'code',location.code,'name',location.name),
                'custodianId',balance.custody_owner_id,'custodianKind',balance.custody_owner_kind,
                'condition',balance.condition,'legalOwner',balance.legal_owner,'status',balance.status) body
            FROM inventory_balance_projection balance
            JOIN visible_locations location ON location.id=balance.location_id AND location.state='ACTIVE'
            JOIN inventory_segment segment ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
                AND segment.warehouse_admission='VERIFIED' AND segment.state='ACTIVE'
            JOIN inventory_sku sku ON sku.tenant_id=balance.tenant_id AND sku.id=balance.sku_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=balance.tenant_id AND asset.id=balance.stock_identity_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=balance.tenant_id AND lot.id=balance.lot_id,request
            WHERE balance.tenant_id=request.tenant AND balance.warehouse_admission='VERIFIED'
                AND balance.location_id=request.location
                AND (?::uuid[] IS NULL OR balance.id=ANY(?::uuid[]))
                AND (request.sku IS NULL OR balance.sku_id=request.sku)
                AND (request.serial IS NULL OR asset.canonical_serial=request.serial)
                AND (?::text IS NULL OR position(lower(?::text) IN lower(concat_ws(' ',sku.code,sku.name,asset.serial_number,lot.code)))>0)"""
        val selected = selectedIds?.let { sql.connection.createArrayOf("uuid", it.toTypedArray()) }
        val result = mapper.readTree(query.result(query.page(rows, "body", "name"), selected, selected, filter.query, filter.query))
        WarehousePage(result.path("items").asSequence().map { mapper.treeToValue(it, WarehouseCountPositionOption::class.java) }.toList(),
            filter.page, filter.size, result.path("totalElements").asLong())
    }

    fun requester(id: UUID): UUID = jdbc.execute { sql ->
        sql.query("SELECT actor_id FROM inventory_document WHERE tenant_id=? AND id=? AND kind='COUNT'", sql.tenant, id) { it.uuid("actor_id") }
            .singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun references(view: WarehouseCountView, names: Map<UUID, String>): WarehouseCountReferences = jdbc.execute { sql ->
        val lines = sql.query("""SELECT entry.balance_id,line.custodian_id,line.custodian_kind,line.condition,line.legal_owner,
                ${itemJson("sku.id")} item
            FROM inventory_count_entry entry
            JOIN inventory_document_line line ON line.tenant_id=entry.tenant_id AND line.id=entry.id
            JOIN inventory_sku sku ON sku.tenant_id=line.tenant_id AND sku.id=line.sku_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=line.tenant_id AND asset.id=line.stock_identity_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=line.tenant_id AND lot.id=line.lot_id
            WHERE entry.tenant_id=? AND entry.document_id=? ORDER BY line.line_number""", sql.tenant, view.id) {
            WarehouseCountLineRef(it.uuid("balance_id"), mapper.readValue(it.getString("item"), WarehouseCountItemRef::class.java),
                it.uuid("custodian_id"), it.getString("custodian_kind"), WarehouseCondition.valueOf(it.getString("condition")),
                AssetLegalOwner.valueOf(it.getString("legal_owner")))
        }
        sql.query("""SELECT document.code,document.reason,document.created_at,document.actor_id,
                location.id location_id,location.code location_code,location.name location_name
            FROM inventory_document document JOIN inventory_count_scope scope ON scope.tenant_id=document.tenant_id AND scope.id=document.id
            JOIN inventory_location location ON location.tenant_id=scope.tenant_id AND location.id=scope.location_id
            WHERE document.tenant_id=? AND document.id=?""", sql.tenant, view.id) {
            WarehouseCountReferences(it.getString("code"), it.getString("reason"), it.getTimestamp("created_at").toInstant(),
                WarehouseCountLocationRef(it.uuid("location_id"), it.getString("location_code"), it.getString("location_name")),
                WarehouseCountPersonRef(it.uuid("actor_id"), names[it.uuid("actor_id")]),
                view.entries.map { entry -> entry.counterId }.distinct().sortedBy(UUID::toString).map { id -> WarehouseCountPersonRef(id, names[id]) }, lines)
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun history(id: UUID, page: WarehousePageRequest, counter: UUID?, latestFirst: Boolean = true): WarehousePage<WarehouseCountHistoryEntry> = jdbc.execute { sql ->
        val direction = if (latestFirst) "DESC" else "ASC"
        val result = mapper.readTree(sql.value("""WITH matches AS MATERIALIZED (
                SELECT id,created_at,jsonb_build_object('recordedAt',${queryTime("created_at")},'fact',jsonb_build_object(
                    'id',id,'balanceId',balance_id,'counterId',counter_id,'roundRevision',document_revision,
                    'quantityBase',observed_quantity_base::text,'baseUnit',base_unit,'reason',reason,'documentReference',evidence_reference)) body
                FROM inventory_cycle_count WHERE tenant_id=? AND document_id=? AND (?::uuid IS NULL OR counter_id=?::uuid)),
            selected AS (SELECT * FROM matches ORDER BY created_at $direction,id LIMIT ? OFFSET ?)
            SELECT jsonb_build_object('items',coalesce((SELECT jsonb_agg(body ORDER BY created_at $direction,id) FROM selected),'[]'::jsonb),
                'totalElements',(SELECT count(*) FROM matches))::text""", sql.tenant, id, counter, counter, page.size, page.page.toLong() * page.size)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND))
        WarehousePage(result.path("items").asSequence().map { mapper.treeToValue(it, WarehouseCountHistoryEntry::class.java) }.toList(),
            page.page, page.size, result.path("totalElements").asLong())
    }

    private fun WarehouseCountFilter.queryFilter() = WarehouseQueryFilter(page, size, "createdAt", "desc",
        skuId = skuId, serial = serial, locationId = locationId, status = state?.name, from = from, until = until)
    private fun itemJson(skuId: String) = """jsonb_build_object('skuId',$skuId,'code',sku.code,'name',sku.name,
        'tracking',sku.tracking,'serial',asset.serial_number,'lotCode',lot.code)"""
}
