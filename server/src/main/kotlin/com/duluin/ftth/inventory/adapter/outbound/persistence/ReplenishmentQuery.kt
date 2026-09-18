package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class ReplenishmentQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun scanBatch(): List<UUID> = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_replenishment_scan(tenant_id) VALUES (?) ON CONFLICT DO NOTHING", sql.tenant)
        val ids = sql.query("""SELECT rule.id FROM inventory_replenishment_rule rule
            JOIN inventory_sku sku ON sku.tenant_id=rule.tenant_id AND sku.id=rule.sku_id AND sku.state='ACTIVE'
            JOIN inventory_location location ON location.tenant_id=rule.tenant_id AND location.id=rule.location_id
            JOIN inventory_replenishment_scan scan ON scan.tenant_id=rule.tenant_id WHERE rule.tenant_id=? AND rule.active
            AND location.state='ACTIVE' AND location.issue_eligible AND location.kind IN ('WAREHOUSE','BIN')
            AND (scan.last_rule_id IS NULL OR rule.id>scan.last_rule_id) ORDER BY rule.id LIMIT 25""", sql.tenant) { it.uuid("id") }
        sql.update("UPDATE inventory_replenishment_scan SET last_rule_id=? WHERE tenant_id=?", ids.lastOrNull(), sql.tenant)
        ids
    }

    fun position(rule: ReplenishmentRule, access: WarehouseQueryAccess): ReplenishmentPosition = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(skuId = rule.skuId, locationId = rule.locationId), access)
        mapper.readValue(query.result(""",
            inbound AS (SELECT coalesce(sum(greatest(transit.quantity,0)),0) quantity
                FROM inventory_document_line line JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
                JOIN LATERAL (SELECT sum(CASE leg.direction WHEN 'IN' THEN leg.quantity_base::numeric ELSE -leg.quantity_base::numeric END) quantity
                    FROM inventory_movement_leg leg WHERE leg.tenant_id=line.tenant_id AND leg.document_line_id=line.id
                    AND leg.status='IN_TRANSIT' AND leg.legal_owner='ISP' AND leg.condition='SERVICEABLE') transit ON true,request
                WHERE line.tenant_id=request.tenant AND line.sku_id=request.sku AND line.destination_location_id=request.location
                AND document.kind='TRANSFER' AND document.state IN ('DISPATCHED','PART_RECEIVED')
                AND document.work_order_id IS NULL AND document.customer_id IS NULL),
            bound_receipts AS (SELECT coalesce(sum(greatest(inspected.quantity-putaway.quantity,0)),0) quantity
                FROM inventory_replenishment_request replenishment JOIN inventory_replenishment_rule rule
                    ON rule.tenant_id=replenishment.tenant_id AND rule.id=replenishment.rule_id
                JOIN inventory_document document ON document.tenant_id=replenishment.tenant_id AND document.id=replenishment.source_document_id
                JOIN LATERAL (SELECT coalesce(sum(accepted_base::numeric),0) quantity FROM inventory_inspection
                    WHERE tenant_id=document.tenant_id AND document_line_id=replenishment.receiving_line_id) inspected ON true
                JOIN LATERAL (SELECT coalesce(sum(leg.quantity_base::numeric),0) quantity FROM inventory_movement_leg leg
                    JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
                    JOIN inventory_operation operation ON operation.tenant_id=movement.tenant_id AND operation.id=movement.operation_id
                    WHERE leg.tenant_id=document.tenant_id AND leg.document_line_id=replenishment.receiving_line_id
                    AND leg.direction='IN' AND leg.status='AVAILABLE' AND operation.namespace='warehouse.receipt.putaway') putaway ON true,request
                WHERE replenishment.tenant_id=request.tenant AND rule.sku_id=request.sku AND rule.location_id=request.location
                    AND document.kind='RECEIPT' AND document.state IN ('RECEIVED_IN_INSPECTION','PUTAWAY')
                    AND replenishment.state<>'CANCELLED')
            SELECT jsonb_build_object('availableBase',coalesce(sum(available),0)::text,
                'reservedBase',coalesce(sum(unpicked+picked),0)::text,
                'confirmedInboundBase',((SELECT quantity FROM inbound)+(SELECT quantity FROM bound_receipts))::text)::text
            FROM filtered_positions"""), ReplenishmentPosition::class.java)
    }

    fun ruleIds(access: WarehouseQueryAccess, page: Int, size: Int, location: UUID?, sku: UUID?, requests: Boolean): Pair<List<UUID>, Long> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(skuId = sku, locationId = location), access)
        val source = if (requests) "JOIN inventory_replenishment_request pending ON pending.tenant_id=rule.tenant_id AND pending.rule_id=rule.id" else ""
        val id = if (requests) "pending.id" else "rule.id"
        val result = mapper.readTree(query.result(""", matches AS (SELECT $id id FROM inventory_replenishment_rule rule $source,request
            WHERE rule.tenant_id=request.tenant AND rule.location_id IN (SELECT id FROM visible_locations)
            AND (request.sku IS NULL OR rule.sku_id=request.sku) AND (request.location IS NULL OR rule.location_id=request.location)),
            selected AS (SELECT id FROM matches ORDER BY id LIMIT $size OFFSET ${page.toLong() * size})
            SELECT jsonb_build_object('ids',coalesce((SELECT jsonb_agg(id ORDER BY id) FROM selected),'[]'::jsonb),
                'total',(SELECT count(*) FROM matches))::text"""))
        result.path("ids").asSequence().map { UUID.fromString(it.asString()) }.toList() to result.path("total").asLong()
    }

    fun receiving(rule: ReplenishmentRule, input: ReplenishmentReceivingReference): Pair<UUID, Long> = jdbc.execute { sql ->
        sql.query("""SELECT line.location_id,line.quantity_base FROM inventory_document document JOIN inventory_document_line line
            ON line.tenant_id=document.tenant_id AND line.document_id=document.id WHERE document.tenant_id=? AND document.id=?
            AND document.revision=? AND document.kind='RECEIPT' AND document.state IN ('RECEIVED_IN_INSPECTION','PUTAWAY')
            AND line.id=? AND line.sku_id=? AND line.base_unit=? AND line.legal_owner='ISP' FOR UPDATE OF document""",
            sql.tenant, input.documentId, input.documentRevision, input.lineId, rule.skuId, rule.baseUnit) {
            it.uuid("location_id") to it.getLong("quantity_base")
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
    }
}
