package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WarehouseLotQueries(private val jdbc: WarehouseCommandJdbc) {
    fun lots(filter: WarehouseQueryFilter, access: WarehouseQueryAccess, id: UUID? = null, part: String? = null, segmentId: UUID? = null): String = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, filter, access)
        val base = """SELECT lot.id,lot.sku_id,lot.code,sku.name,lot.base_unit,lot.received_quantity_base,lot.received_at created_at,
            lot.origin_document_line_id,lot.warehouse_admission ${if (access.cost) ",lot.cost_total_minor,lot.cost_basis_quantity_base,lot.currency" else ""}
            FROM inventory_lot lot JOIN inventory_sku sku ON sku.tenant_id=lot.tenant_id AND sku.id=lot.sku_id,request
            WHERE lot.tenant_id=request.tenant AND lot.warehouse_admission='VERIFIED'
            AND (request.sku IS NULL OR lot.sku_id=request.sku) AND request.serial IS NULL
            AND EXISTS (SELECT FROM scoped_positions WHERE lot_id=lot.id)
            AND NOT EXISTS (SELECT FROM inventory_balance_projection balance WHERE balance.tenant_id=request.tenant AND balance.lot_id=lot.id
                AND balance.quantity_base>0 AND balance.location_id NOT IN (SELECT id FROM visible_locations))
            AND NOT EXISTS (SELECT FROM inventory_movement_leg leg WHERE leg.tenant_id=request.tenant AND leg.lot_id=lot.id
                AND leg.location_id NOT IN (SELECT id FROM visible_locations))
            ${if (part != null) "" else """AND (request.since IS NULL OR lot.received_at>=request.since) AND (request.until IS NULL OR lot.received_at<request.until)
            AND EXISTS (SELECT FROM filtered_positions WHERE lot_id=lot.id)"""}"""
        val json = """jsonb_build_object('id',id,'skuId',sku_id,'code',code,'name',name,'received',${queryQuantity("received_quantity_base", "base_unit")},
            'receivedAt',${queryTime("created_at")},'admission',warehouse_admission,'origin',${queryOrigin("origin_document_line_id")}) || ${queryCost("matches", access.cost)}"""
        if (id == null) query.result(query.page(base, json, when (filter.sort) { "name" -> "name"; "createdAt" -> "created_at"; else -> "id" }))
        else {
            val target = """,target AS ($base AND lot.id=?),target_segments AS (SELECT segment.* FROM inventory_segment segment,request
                WHERE segment.tenant_id=request.tenant AND segment.lot_id IN (SELECT id FROM target))"""
            when (part) {
                "history" -> query.result(target + warehouseTimeline(query), id)
                "segments" -> {
                    val segments = """SELECT segment.*,segment.kind name FROM target_segments segment,request WHERE
                        (request.status IS NULL OR segment.state=request.status) AND (request.since IS NULL OR segment.created_at>=request.since)
                        AND (request.until IS NULL OR segment.created_at<request.until) AND
                        ((request.location IS NULL AND request.condition IS NULL AND request.owner IS NULL) OR EXISTS (
                            SELECT FROM scoped_positions position WHERE position.stock_identity_id=segment.id
                                AND (request.location IS NULL OR position.location_id=request.location)
                                AND (request.condition IS NULL OR position.condition=request.condition) AND (request.owner IS NULL OR position.legal_owner=request.owner)))"""
                    if (segmentId == null) query.result(target + query.page(segments, segmentJson, when (filter.sort) {
                        "name" -> "name"; "createdAt" -> "created_at"; else -> "id"
                    }, requireTarget = true), id)
                    else query.result(target + ",matches AS ($segments AND segment.id=?) SELECT ($segmentJson)::text FROM matches", id, segmentId)
                }
                else -> query.result(target + """,matches AS (SELECT * FROM target) SELECT ($json || jsonb_build_object('conservation',
                    jsonb_build_object('consistent',received_quantity_base::numeric=(SELECT coalesce(sum(quantity_base::numeric),0) FROM target_segments WHERE parent_segment_id IS NULL)
                        AND received_quantity_base::numeric=(SELECT coalesce(sum(quantity_base::numeric),0) FROM target_segments WHERE state<>'SPLIT')
                        AND NOT EXISTS (SELECT FROM target_segments piece WHERE
                            (SELECT coalesce(sum(balance.quantity_base::numeric),0) FROM inventory_balance_projection balance
                                WHERE balance.tenant_id=piece.tenant_id AND balance.stock_identity_id=piece.id)<>
                            CASE WHEN piece.state='SPLIT' THEN 0 ELSE piece.quantity_base END)
                        AND NOT EXISTS (SELECT FROM target_segments parent WHERE parent.state='SPLIT' AND parent.quantity_base::numeric<>
                            (SELECT coalesce(sum(child.quantity_base::numeric),0) FROM target_segments child WHERE child.parent_segment_id=parent.id)),
                        'physicalQuantityBase',(SELECT coalesce(sum(quantity_base::numeric),0)::text FROM scoped_positions WHERE lot_id=matches.id),
                        'rootQuantityBase',(SELECT coalesce(sum(quantity_base::numeric),0)::text FROM target_segments WHERE parent_segment_id IS NULL),
                        'activeQuantityBase',(SELECT coalesce(sum(quantity_base::numeric),0)::text FROM target_segments WHERE state='ACTIVE'),
                        'terminalQuantityBase',(SELECT coalesce(sum(quantity_base::numeric),0)::text FROM target_segments WHERE state='RETIRED'),
                        'rootCount',(SELECT count(*) FROM target_segments WHERE parent_segment_id IS NULL),
                        'splitCount',(SELECT count(*) FROM target_segments WHERE state='SPLIT'))))::text FROM matches""", id)
            }
        }
    }

    private val segmentJson = """jsonb_build_object('id',id,'stockIdentityId',id,'lotId',lot_id,'parentSegmentId',parent_segment_id,
        'kind',kind,'state',state,'quantity',${queryQuantity("quantity_base", "base_unit")},'createdAt',${queryTime("created_at")},
        'origin',(SELECT ${queryOrigin("origin_document_line_id")} FROM target),
        'children',(SELECT coalesce(jsonb_agg(child.id ORDER BY child.id),'[]'::jsonb) FROM
            (SELECT id FROM target_segments WHERE parent_segment_id=matches.id ORDER BY id LIMIT 100) child),
        'childCount',(SELECT count(*) FROM target_segments WHERE parent_segment_id=matches.id),
        'conserved',CASE WHEN state='SPLIT' THEN quantity_base::numeric=(SELECT coalesce(sum(child.quantity_base::numeric),0)
            FROM target_segments child WHERE child.parent_segment_id=matches.id) ELSE true END)"""
}
