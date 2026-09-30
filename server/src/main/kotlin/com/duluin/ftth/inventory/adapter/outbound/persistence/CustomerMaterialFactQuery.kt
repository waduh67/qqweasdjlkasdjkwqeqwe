package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.CustomerMaterialFactV2
import com.duluin.ftth.inventory.MaterialConsumptionApiV2
import com.duluin.ftth.inventory.WarehousePage
import com.duluin.ftth.inventory.WarehousePageRequest
import com.duluin.ftth.inventory.application.toWarehouseQuantity
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.inventory.domain.model.StockUnit
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

@Repository
class CustomerMaterialFactQuery(private val jdbc: WarehouseCommandJdbc) : MaterialConsumptionApiV2 {
    @Transactional(readOnly = true, timeout = 20)
    override fun forCustomer(customerId: UUID, page: WarehousePageRequest): WarehousePage<CustomerMaterialFactV2> {
        if (page.page < 0 || page.size !in 1..100) throw ValidationException("Invalid material history page")
        return jdbc.execute { sql ->
            sql.connection.prepareStatement("""WITH matches AS MATERIALIZED (
                SELECT fact.id,fact.customer_id,fact.work_order_id,fact.stock_identity_id,fact.quantity_base,fact.base_unit,
                    fact.use_revision,fact.posting_id,fact.compensation_id,fact.recorded_at,coalesce(segment.sku_id,asset.sku_id) sku_id
                FROM inventory_customer_material_fact fact
                LEFT JOIN inventory_segment segment ON segment.tenant_id=fact.tenant_id AND segment.id=fact.stock_identity_id
                LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=fact.tenant_id AND asset.id=fact.stock_identity_id
                WHERE fact.tenant_id=? AND fact.customer_id=? AND fact.warehouse_admission='VERIFIED'
                UNION ALL
                SELECT assignment.id,assignment.customer_id,assignment.work_order_id,line.stock_identity_id,line.quantity_base,line.base_unit,
                    document.use_revision,movement.id,NULL::uuid,assignment.started_at,line.sku_id
                FROM inventory_asset_assignment assignment
                JOIN inventory_document document ON document.tenant_id=assignment.tenant_id AND document.id=assignment.id AND document.kind='DEPLOYMENT'
                JOIN inventory_document_line line ON line.tenant_id=document.tenant_id AND line.document_id=document.id AND line.stock_identity_id=assignment.asset_id
                JOIN inventory_movement movement ON movement.tenant_id=document.tenant_id AND movement.operation_id=document.id AND movement.kind='DEPLOY'
                WHERE assignment.tenant_id=? AND assignment.customer_id=? AND assignment.warehouse_admission='VERIFIED')
                SELECT selected.*,totals.total FROM (SELECT count(*) total FROM matches) totals
                LEFT JOIN LATERAL (SELECT * FROM matches ORDER BY recorded_at,id LIMIT ? OFFSET ?) selected ON true
                ORDER BY selected.recorded_at,selected.id""").use { statement ->
                statement.queryTimeout = 20
                statement.setObject(1, sql.tenant)
                statement.setObject(2, customerId)
                statement.setObject(3, sql.tenant)
                statement.setObject(4, customerId)
                statement.setInt(5, page.size)
                statement.setLong(6, page.page.toLong() * page.size)
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    val total = rows.getLong("total")
                    val facts = buildList {
                        do {
                            if (rows.optionalUuid("id") != null) add(fact(rows))
                        } while (rows.next())
                    }
                    WarehousePage(facts, page.page, page.size, total)
                }
            }
        }
    }

    private fun fact(row: ResultSet): CustomerMaterialFactV2 {
        val quantity = StockQuantity.parseBase(checkNotNull(row.getString("quantity_base")), StockUnit.valueOf(row.getString("base_unit")))
        return CustomerMaterialFactV2(
            factId = row.uuid("id"), customerId = row.uuid("customer_id"), workOrderId = row.uuid("work_order_id"),
            skuId = checkNotNull(row.optionalUuid("sku_id")), stockIdentityId = row.uuid("stock_identity_id"),
            quantity = quantity.toWarehouseQuantity(), useRevision = checkNotNull(row.getObject("use_revision", java.lang.Long::class.java)).toLong(),
            postingId = checkNotNull(row.optionalUuid("posting_id")), compensatesFactId = row.optionalUuid("compensation_id"),
            recordedAt = row.getTimestamp("recorded_at").toInstant(),
        )
    }
}
