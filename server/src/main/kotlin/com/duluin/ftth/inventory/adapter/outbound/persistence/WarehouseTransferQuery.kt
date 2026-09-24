package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseTransferQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    /** Resolve current receiver eligibility through IAM before applying LIMIT or counting rows. */
    fun receiverIds(access: WarehouseQueryAccess): Set<UUID> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(), access)
        mapper.readTree(query.result(candidates + """
            SELECT coalesce(jsonb_agg(DISTINCT transfer_receiver_id),'[]'::jsonb)::text FROM transfer_candidates
        """)).asSequence().map { UUID.fromString(it.asString()) }.toSet()
    }

    fun list(filter: WarehouseTransferFilter, access: WarehouseQueryAccess, receivers: Map<UUID, Boolean>): WarehousePage<WarehouseTransferView> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(filter.page, filter.size, "createdAt", "desc",
            locationId = filter.locationId, status = filter.state?.name), access)
        val rows = """SELECT candidate.* FROM transfer_candidates candidate,request,(SELECT ?::jsonb people) receiver
            WHERE jsonb_exists(receiver.people,candidate.transfer_receiver_id::text)
                AND (candidate.destination_kind NOT IN ('TECHNICIAN','VEHICLE') OR candidate.destination_custodian=candidate.transfer_receiver_id)
                AND (candidate.destination_kind<>'TECHNICIAN' OR (receiver.people->>candidate.transfer_receiver_id::text)::boolean)
                AND (request.status IS NULL OR candidate.body->>'state'=request.status)
                AND (request.location IS NULL OR request.location IN (candidate.transfer_source_location_id,
                    candidate.transfer_transit_location_id,candidate.transfer_destination_location_id,candidate.resolution_location_id))
                AND (?::text IS NULL OR position(lower(?::text) IN lower(candidate.code))>0)"""
        val result = mapper.readTree(query.result(candidates + query.page(rows, "body", "created_at"),
            mapper.writeValueAsString(receivers), filter.query, filter.query))
        WarehousePage(result.path("items").asSequence().map { mapper.treeToValue(it, WarehouseTransferView::class.java) }.toList(),
            filter.page, filter.size, result.path("totalElements").asLong())
    }

    fun locationReferences(view: WarehouseTransferView): List<WarehouseTransferLocationRef> = jdbc.execute { sql ->
        sql.query("""SELECT id,code,name FROM inventory_location WHERE tenant_id=? AND id IN (?,?,?) ORDER BY id""",
            sql.tenant, view.sourceLocationId, view.transitLocationId, view.destinationLocationId) {
            WarehouseTransferLocationRef(it.uuid("id"), it.getString("code"), it.getString("name"))
        }
    }

    fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseTransferView> = jdbc.execute { sql ->
        val result = mapper.readTree(sql.value("""WITH matches AS MATERIALIZED (
                SELECT document_revision,original_body::jsonb body FROM inventory_operation
                WHERE tenant_id=? AND document_id=? AND namespace LIKE 'warehouse.transfer.%'),
            selected AS (SELECT * FROM matches ORDER BY document_revision DESC LIMIT ? OFFSET ?)
            SELECT jsonb_build_object('items',coalesce((SELECT jsonb_agg(body ORDER BY document_revision DESC) FROM selected),'[]'::jsonb),
                'totalElements',(SELECT count(*) FROM matches))::text""", sql.tenant, id, page.size, page.page.toLong() * page.size)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND))
        WarehousePage(result.path("items").asSequence().map { mapper.treeToValue(it, WarehouseTransferView::class.java) }.toList(),
            page.page, page.size, result.path("totalElements").asLong())
    }

    fun lineReferences(view: WarehouseTransferView): List<WarehouseTransferLineRef> = jdbc.execute { sql ->
        sql.query("""SELECT line.id,sku.code sku_code,sku.name sku_name,asset.serial_number,lot.code lot_code
            FROM inventory_document_line line
            LEFT JOIN inventory_sku sku ON sku.tenant_id=line.tenant_id AND sku.id=line.sku_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=line.tenant_id AND asset.id=line.stock_identity_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=line.tenant_id AND lot.id=line.lot_id
            WHERE line.tenant_id=? AND line.document_id=? AND line.document_revision=0 ORDER BY line.line_number""", sql.tenant, view.id) {
            WarehouseTransferLineRef(it.uuid("id"), it.getString("sku_code"), it.getString("sku_name"),
                it.getString("serial_number"), it.getString("lot_code"))
        }
    }

    private val candidates = """, transfer_candidates AS MATERIALIZED (
        SELECT document.*,operation.original_body::jsonb body,destination.kind destination_kind,
            destination.custodian_id destination_custodian,
            (resolution.transfer_remainder_request::jsonb->>'destinationLocationId')::uuid resolution_location_id
        FROM inventory_document document
        JOIN inventory_operation operation ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id
            AND operation.document_revision=document.revision AND operation.namespace LIKE 'warehouse.transfer.%'
        JOIN visible_locations source ON source.id=document.transfer_source_location_id AND source.state='ACTIVE'
        JOIN visible_locations transit ON transit.id=document.transfer_transit_location_id AND transit.state='ACTIVE'
        JOIN visible_locations destination ON destination.id=document.transfer_destination_location_id AND destination.state='ACTIVE'
        LEFT JOIN inventory_document resolution ON resolution.tenant_id=document.tenant_id AND resolution.kind='ADJUSTMENT'
            AND resolution.id=(operation.original_body::jsonb->>'resolutionDocumentId')::uuid,request
        WHERE document.tenant_id=request.tenant AND document.kind='TRANSFER' AND document.transfer_receiver_id IS NOT NULL
            AND source.kind IN ('WAREHOUSE','BIN','VEHICLE','TECHNICIAN','QUARANTINE')
            AND destination.kind IN ('WAREHOUSE','BIN','VEHICLE','TECHNICIAN','QUARANTINE')
            AND transit.kind='TRANSIT' AND NOT transit.issue_eligible AND transit.code<>'RECEIPT_SOURCE'
            AND source.id<>transit.id AND source.id<>destination.id AND transit.id<>destination.id
            AND (operation.original_body::jsonb->>'resolutionDocumentId' IS NULL OR (resolution.id IS NOT NULL AND
                (resolution.transfer_remainder_request::jsonb->>'destinationLocationId')::uuid IN
                    (SELECT id FROM visible_locations WHERE state='ACTIVE')))
            AND NOT EXISTS (SELECT FROM inventory_movement movement JOIN inventory_movement_leg leg
                ON leg.tenant_id=movement.tenant_id AND leg.movement_id=movement.id
                WHERE movement.tenant_id=document.tenant_id AND movement.document_id IN (document.id,resolution.id)
                    AND leg.location_id NOT IN (SELECT id FROM visible_locations WHERE state='ACTIVE')))
    """
}
