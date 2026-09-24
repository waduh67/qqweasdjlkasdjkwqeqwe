package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseReturnQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun list(filter: WarehouseReturnFilter, access: WarehouseQueryAccess): WarehousePage<WarehouseReturnView> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(filter.page, filter.size, "createdAt", "desc",
            skuId = filter.skuId, serial = filter.serial, locationId = filter.locationId, status = filter.state?.name,
            owner = filter.owner?.name, from = filter.from, until = filter.until), access)
        val rows = """SELECT intake.id,intake.created_at,operation.original_body::jsonb body
            FROM inventory_return_case intake
            JOIN inventory_document document ON document.tenant_id=intake.tenant_id AND document.id=intake.id
            JOIN inventory_sku sku ON sku.tenant_id=intake.tenant_id AND sku.id=(intake.body::jsonb->'view'->>'skuId')::uuid
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=intake.tenant_id AND asset.id=intake.stock_identity_id
            JOIN inventory_operation operation ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id
                AND operation.document_revision=document.revision,request
            WHERE intake.tenant_id=request.tenant
                AND intake.quarantine_location_id IN (SELECT id FROM visible_locations WHERE state='ACTIVE')
                AND (operation.original_body::jsonb->>'locationId')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE')
                AND (operation.original_body::jsonb->'repair'->>'repairLocationId' IS NULL OR
                    (operation.original_body::jsonb->'repair'->>'repairLocationId')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE'))
                AND (request.sku IS NULL OR operation.original_body::jsonb->>'skuId'=request.sku::text)
                AND (request.location IS NULL OR operation.original_body::jsonb->>'locationId'=request.location::text)
                AND (request.status IS NULL OR operation.original_body::jsonb->>'state'=request.status)
                AND (request.owner IS NULL OR operation.original_body::jsonb->>'legalOwner'=request.owner)
                AND (request.serial IS NULL OR asset.canonical_serial=request.serial)
                AND (request.since IS NULL OR intake.created_at>=request.since)
                AND (request.until IS NULL OR intake.created_at<request.until)
                AND (?::text IS NULL OR intake.origin=?::text)
                AND (?::uuid IS NULL OR intake.stock_identity_id=?::uuid)
                AND (?::text IS NULL OR position(lower(?::text) IN lower(concat_ws(' ',document.code,sku.code,sku.name,asset.serial_number)))>0)"""
        val result = mapper.readTree(query.result(query.page(rows, "body", "created_at"),
            filter.origin?.name, filter.origin?.name, filter.stockIdentityId, filter.stockIdentityId, filter.query, filter.query))
        WarehousePage(result.path("items").asSequence().map { mapper.treeToValue(it, WarehouseReturnView::class.java) }.toList(),
            filter.page, filter.size, result.path("totalElements").asLong())
    }

    fun references(view: WarehouseReturnView, receivedByName: String?): WarehouseReturnReferences = jdbc.execute { sql ->
        val locations = sql.query("""SELECT location.id,location.code,location.name FROM inventory_location location
            JOIN inventory_return_case intake ON intake.tenant_id=location.tenant_id AND intake.id=?
            WHERE location.tenant_id=? AND location.id IN (intake.quarantine_location_id,?::uuid,?::uuid) ORDER BY location.id""",
            view.id, sql.tenant, view.locationId, view.repair?.repairLocationId) {
            WarehouseReturnNamedRef(it.uuid("id"), it.getString("code"), it.getString("name"))
        }
        sql.query("""SELECT document.code,source.code source_code,source.work_order_id,source.work_order_code_snapshot,
                sku.code sku_code,sku.name sku_name,sku.tracking,asset.serial_number,lot.code lot_code,
                supplier.id supplier_id,supplier.code supplier_code,supplier.name supplier_name,handover.id handover_id,
                assignment.id assignment_id,assignment.customer_id origin_customer_id,assignment.work_order_id origin_work_order_id,assignment.legal_owner origin_legal_owner
            FROM inventory_document document
            JOIN inventory_document source ON source.tenant_id=document.tenant_id AND source.id=document.source_document_id
            LEFT JOIN inventory_asset_removal removal ON removal.tenant_id=source.tenant_id AND removal.id=source.id
            LEFT JOIN inventory_asset_assignment assignment ON assignment.tenant_id=removal.tenant_id AND assignment.id=removal.assignment_id
                AND assignment.asset_id=removal.asset_id
            JOIN inventory_sku sku ON sku.tenant_id=document.tenant_id AND sku.id=?
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=document.tenant_id AND asset.id=?
            LEFT JOIN inventory_lot lot ON lot.tenant_id=document.tenant_id AND lot.id=?
            LEFT JOIN inventory_supplier supplier ON supplier.tenant_id=document.tenant_id AND supplier.id=?
            LEFT JOIN inventory_rma_handover handover ON handover.tenant_id=document.tenant_id AND handover.return_id=document.id
            WHERE document.tenant_id=? AND document.id=?""", view.skuId, view.stockIdentityId, view.lotId, view.repair?.vendorId, sql.tenant, view.id) {
            WarehouseReturnReferences(it.getString("code"), it.getString("source_code"), it.optionalUuid("work_order_id"),
                it.getString("work_order_code_snapshot"), WarehouseReturnItemRef(view.skuId, it.getString("sku_code"), it.getString("sku_name"),
                    WarehouseTracking.valueOf(it.getString("tracking")), it.getString("serial_number"), it.getString("lot_code")),
                locations, receivedByName, it.optionalUuid("supplier_id")?.let { id ->
                    WarehouseReturnNamedRef(id, it.getString("supplier_code"), it.getString("supplier_name"))
                }, it.optionalUuid("handover_id"), it.optionalUuid("assignment_id")?.let { id ->
                    WarehouseReturnAssetOriginRef(id, it.uuid("origin_customer_id"), it.uuid("origin_work_order_id"), AssetLegalOwner.valueOf(it.getString("origin_legal_owner")))
                })
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun history(id: UUID, page: WarehousePageRequest): WarehousePage<WarehouseReturnView> = jdbc.execute { sql ->
        val result = mapper.readTree(sql.value("""WITH matches AS MATERIALIZED (
                SELECT document_revision,original_body::jsonb body FROM inventory_operation
                WHERE tenant_id=? AND document_id=? AND namespace LIKE 'warehouse.return.%'),
            selected AS (SELECT * FROM matches ORDER BY document_revision DESC LIMIT ? OFFSET ?)
            SELECT jsonb_build_object('items',coalesce((SELECT jsonb_agg(body ORDER BY document_revision DESC) FROM selected),'[]'::jsonb),
                'totalElements',(SELECT count(*) FROM matches))::text""", sql.tenant, id, page.size, page.page.toLong() * page.size)
            ?: sql.fail(WarehouseErrorCode.NOT_FOUND))
        WarehousePage(result.path("items").asSequence().map { mapper.treeToValue(it, WarehouseReturnView::class.java) }.toList(),
            page.page, page.size, result.path("totalElements").asLong())
    }
}
