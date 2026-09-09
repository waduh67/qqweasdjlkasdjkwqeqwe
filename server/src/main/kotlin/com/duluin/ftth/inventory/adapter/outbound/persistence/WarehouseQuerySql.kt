package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

data class WarehouseQueryAccess(val locations: AuthorityScope, val areas: AuthorityScope, val sites: Map<UUID, UUID?>,
    val cost: Boolean, val provenance: Boolean)

internal class WarehouseQuerySql(private val sql: PostingSql, val filter: WarehouseQueryFilter, val access: WarehouseQueryAccess) {
    private fun array(scope: AuthorityScope) = when (scope) {
        AuthorityScope.Unrestricted -> null
        is AuthorityScope.Restricted -> sql.connection.createArrayOf("uuid", scope.ids.toTypedArray())
    }
    fun result(query: String, vararg values: Any?): String {
        val parameters = listOf(sql.tenant, array(access.locations), array(access.areas), jacksonObjectMapper().writeValueAsString(access.sites),
            filter.skuId, filter.serial, filter.locationId, filter.status, filter.condition, filter.owner, filter.from, filter.until) + values
        return sql.connection.prepareStatement(prefix + query).use { statement ->
            statement.queryTimeout = 20
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1,
                if (value is java.time.Instant) java.sql.Timestamp.from(value) else value) }
            statement.executeQuery().use { result ->
                if (!result.next()) sql.fail(WarehouseErrorCode.NOT_FOUND)
                result.getString(1) ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
            }
        }
    }

    fun page(rows: String, json: String, order: String, requireTarget: Boolean = false): String = """,
        matches AS MATERIALIZED ($rows), selected AS (SELECT *${if (json == "body") "" else ", $json AS body"} FROM matches
            ORDER BY $order ${filter.direction},id ASC LIMIT ${filter.size} OFFSET ${filter.page.toLong() * filter.size})
        SELECT ${if (requireTarget) "CASE WHEN EXISTS (SELECT FROM target) THEN " else ""}
            jsonb_build_object('items',coalesce((SELECT jsonb_agg(body ORDER BY $order ${filter.direction},id ASC) FROM selected),'[]'::jsonb),
            'page',${filter.page},'size',${filter.size},'totalElements',(SELECT count(*) FROM matches))::text${if (requireTarget) " ELSE NULL END" else ""}"""

    val prefix = """WITH RECURSIVE request AS (SELECT ?::uuid tenant,?::uuid[] locations,?::uuid[] areas,?::jsonb sites,
        ?::uuid sku,?::text serial,?::uuid location,?::text status,?::text condition,?::text owner,?::timestamptz since,?::timestamptz until),
        location_ancestry AS (
            SELECT location.id root,location.id,location.parent_location_id,location.site_id,location.area_id,ARRAY[location.id] path,false cycle
            FROM inventory_location location,request WHERE location.tenant_id=request.tenant
            UNION ALL SELECT child.root,parent.id,parent.parent_location_id,parent.site_id,parent.area_id,child.path||parent.id,parent.id=ANY(child.path)
            FROM inventory_location parent JOIN location_ancestry child ON parent.id=child.parent_location_id,request
            WHERE parent.tenant_id=request.tenant AND NOT child.cycle AND cardinality(child.path)<=32),
        visible_locations AS MATERIALIZED (SELECT location.* FROM inventory_location location,request
            WHERE location.tenant_id=request.tenant AND (request.locations IS NULL OR location.id=ANY(request.locations))
            AND (request.areas IS NULL OR location.area_id=ANY(request.areas))
            AND NOT EXISTS (SELECT FROM location_ancestry ancestor WHERE ancestor.root=location.id AND
                (ancestor.cycle OR cardinality(ancestor.path)>32 OR ancestor.area_id IS DISTINCT FROM location.area_id OR
                (ancestor.parent_location_id IS NOT NULL AND NOT EXISTS (SELECT FROM inventory_location parent WHERE parent.tenant_id=request.tenant AND parent.id=ancestor.parent_location_id)) OR
                (ancestor.site_id IS NOT NULL AND NOT EXISTS (SELECT FROM jsonb_each_text(request.sites) site WHERE site.key=ancestor.site_id::text AND site.value IS NOT DISTINCT FROM ancestor.area_id::text))))
            AND (SELECT count(DISTINCT site_id) FROM location_ancestry WHERE root=location.id)<=1),
        scoped_positions AS MATERIALIZED (SELECT balance.*,sku.code sku_code,sku.name sku_name,sku.tracking,asset.serial_number,
            segment.state segment_state,location.name location_name,location.kind location_kind,
            coalesce(reserved.unpicked,0) unpicked,coalesce(reserved.picked,0) picked,
            CASE WHEN balance.warehouse_admission='VERIFIED' AND segment.warehouse_admission='VERIFIED' AND segment.state='ACTIVE'
                AND balance.status='AVAILABLE' AND balance.condition='SERVICEABLE' AND balance.legal_owner='ISP'
                AND location.state='ACTIVE' AND location.issue_eligible AND sku.state='ACTIVE'
                AND ((location.kind IN ('WAREHOUSE','BIN') AND balance.custody_owner_kind='WAREHOUSE') OR
                    (location.kind='TECHNICIAN' AND balance.custody_owner_kind='TECHNICIAN') OR
                    (location.kind='VEHICLE' AND balance.custody_owner_kind='VEHICLE'))
                THEN greatest(balance.quantity_base::numeric-coalesce(reserved.unpicked,0)-coalesce(reserved.picked,0),0) ELSE 0 END available
            FROM inventory_balance_projection balance JOIN visible_locations location ON location.id=balance.location_id
            LEFT JOIN inventory_sku sku ON sku.tenant_id=balance.tenant_id AND sku.id=balance.sku_id
            LEFT JOIN inventory_segment segment ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=balance.tenant_id AND asset.id=balance.stock_identity_id
            LEFT JOIN LATERAL (SELECT sum(reservation.reserved_unpicked_base::numeric) unpicked,sum(reservation.reserved_picked_base::numeric) picked
                FROM inventory_reservation reservation WHERE reservation.tenant_id=balance.tenant_id AND reservation.state='OPEN'
                AND reservation.sku_id=balance.sku_id AND reservation.stock_identity_id=balance.stock_identity_id
                AND reservation.lot_id IS NOT DISTINCT FROM balance.lot_id AND reservation.base_unit=balance.base_unit
                AND reservation.location_id=balance.location_id AND reservation.custodian_id=balance.custody_owner_id
                AND reservation.custodian_kind=balance.custody_owner_kind AND reservation.condition=balance.condition AND reservation.legal_owner=balance.legal_owner) reserved ON true,request
            WHERE balance.tenant_id=request.tenant AND (balance.quantity_base>0 OR (balance.warehouse_admission='LEGACY_UNRESOLVED' AND balance.quantity>0))),
        filtered_positions AS MATERIALIZED (SELECT position.* FROM scoped_positions position,request WHERE
            (request.sku IS NULL OR position.sku_id=request.sku) AND (request.serial IS NULL OR warehouse_canonical_serial(position.serial_number)=request.serial)
            AND (request.location IS NULL OR position.location_id=request.location) AND (request.status IS NULL OR position.status=request.status)
            AND (request.condition IS NULL OR position.condition=request.condition) AND (request.owner IS NULL OR position.legal_owner=request.owner)
            AND (request.since IS NULL OR position.updated_at>=request.since) AND (request.until IS NULL OR position.updated_at<request.until))
        """
}

internal fun queryQuantity(quantity: String, unit: String) = """jsonb_build_object('quantityBase',($quantity)::text,'baseUnit',$unit,
    'displayQuantity',CASE WHEN $unit='MM' THEN trunc(($quantity)::numeric/1000)::text||'.'||lpad(mod(($quantity)::numeric,1000)::text,3,'0') ELSE ($quantity)::text END,
    'displayUnit',CASE WHEN $unit='MM' THEN 'M' ELSE 'EA' END)"""

internal fun queryTime(column: String) = "to_char($column AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"

internal fun queryOrigin(line: String) = """(SELECT jsonb_build_object('documentId',document.id,'documentCode',document.code,'kind',document.kind,
    'lineId',origin.id,'customerLabelSnapshot',document.customer_label_snapshot,'workOrderCodeSnapshot',document.work_order_code_snapshot)
    FROM inventory_document_line origin JOIN inventory_document document ON document.tenant_id=origin.tenant_id AND document.id=origin.document_id,request
    WHERE origin.tenant_id=request.tenant AND origin.id=$line AND
        (origin.location_id IS NULL OR origin.location_id IN (SELECT id FROM visible_locations)) AND
        (origin.destination_location_id IS NULL OR origin.destination_location_id IN (SELECT id FROM visible_locations)))"""

internal fun queryCost(alias: String, allowed: Boolean): String = if (!allowed) "'{}'::jsonb" else """jsonb_build_object('cost',
    jsonb_build_object('state',CASE WHEN $alias.cost_total_minor IS NULL THEN 'UNKNOWN' ELSE 'KNOWN' END,
        'totalMinor',$alias.cost_total_minor::text,'costBasisQuantityBase',$alias.cost_basis_quantity_base::text,'currency',$alias.currency))"""
