package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper

@Repository
class WarehouseReturnQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun list(filter: WarehouseReturnFilter, access: WarehouseQueryAccess): WarehousePage<WarehouseReturnView> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(filter.page, filter.size, "createdAt", "desc",
            skuId = filter.skuId, locationId = filter.locationId, status = filter.state?.name, owner = filter.owner?.name), access)
        val rows = """SELECT intake.id,intake.created_at,operation.original_body::jsonb body
            FROM inventory_return_case intake
            JOIN inventory_document document ON document.tenant_id=intake.tenant_id AND document.id=intake.id
            JOIN inventory_operation operation ON operation.tenant_id=document.tenant_id AND operation.document_id=document.id
                AND operation.document_revision=document.revision,request
            WHERE intake.tenant_id=request.tenant
                AND intake.quarantine_location_id IN (SELECT id FROM visible_locations WHERE state='ACTIVE')
                AND (operation.original_body::jsonb->>'locationId')::uuid IN (SELECT id FROM visible_locations WHERE state='ACTIVE')
                AND (request.sku IS NULL OR operation.original_body::jsonb->>'skuId'=request.sku::text)
                AND (request.location IS NULL OR operation.original_body::jsonb->>'locationId'=request.location::text)
                AND (request.status IS NULL OR operation.original_body::jsonb->>'state'=request.status)
                AND (request.owner IS NULL OR operation.original_body::jsonb->>'legalOwner'=request.owner)
                AND (?::text IS NULL OR intake.origin=?::text)
                AND (?::uuid IS NULL OR intake.stock_identity_id=?::uuid)"""
        val result = mapper.readTree(query.result(query.page(rows, "body", "created_at"),
            filter.origin?.name, filter.origin?.name, filter.stockIdentityId, filter.stockIdentityId))
        WarehousePage(result.path("items").asSequence().map { mapper.treeToValue(it, WarehouseReturnView::class.java) }.toList(),
            filter.page, filter.size, result.path("totalElements").asLong())
    }
}
