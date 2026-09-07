package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import java.util.UUID

internal class PostingLineBindings(private val sql: PostingSql, private val command: WarehousePost) {
    private val children=command.splits.flatMap { split -> split.children.map { it.id to (split.parentId to it) } }
    private val pending=children.toMap()

    fun validate(): Map<UUID,StockQuantity> {
        require(children.size==pending.size) { "A split child cannot belong to multiple parents" }
        pending.keys.forEach { identity ->
            require(sql.value("SELECT id FROM inventory_segment WHERE tenant_id=? AND id=?",sql.tenant,identity)==null) { "Split children must be new identities" }
        }
        return command.legs.groupBy { it.documentLineId }.entries.sortedBy { it.key.toString() }.associate { (id,legs) ->
            val line=sql.query("SELECT stock_identity_id,sku_id,lot_id,base_unit,quantity_base FROM inventory_document_line WHERE tenant_id=? AND document_id=? AND id=?",
                sql.tenant,command.documentId,id) {
                BoundLine(it.optionalUuid("stock_identity_id"),it.uuid("sku_id"),it.optionalUuid("lot_id"),StockQuantity.of(it.getLong("quantity_base"),StockUnit.valueOf(it.getString("base_unit"))))
            }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
            val identity=requireNotNull(line.identity) { "Physical posting line requires a stock identity" }
            legs.forEach { leg ->
                require(leg.dimension.skuId==line.sku && leg.dimension.lotId==line.lot && leg.quantity.unit==line.quantity.unit) { "Actual leg must match the locked line SKU, lot and unit" }
                requireDescendant(leg.dimension.stockIdentityId,identity)
            }
            val zero=StockQuantity.of(0,line.quantity.unit)
            val outgoing=legs.filter { it.direction==LegDirection.OUT }
            val incoming=legs.filter { it.direction==LegDirection.IN }
            val debit=outgoing.fold(zero) { sum,leg -> sum+leg.quantity }
            require(debit==incoming.fold(zero) { sum,leg -> sum+leg.quantity }) { "Each posting line must have paired base quantities" }
            val retained=incoming.filter { leg ->
                val child=pending[leg.dimension.stockIdentityId]
                child!=null && child.second.kind==SegmentKind.REMNANT && child.second.quantity==leg.quantity && outgoing.any { source ->
                    child.first==source.dimension.stockIdentityId && leg.status==source.status &&
                        leg.dimension.copy(stockIdentityId=source.dimension.stockIdentityId)==source.dimension
                }
            }.fold(zero) { sum,leg -> sum+leg.quantity }
            val allocated=debit-retained
            val quantity=if(allocated.quantityBase==0L) debit else allocated
            require(quantity.quantityBase>0 && quantity.quantityBase<=line.quantity.quantityBase) { "Actual posting quantity exceeds the locked line or has no allocated quantity" }
            id to quantity
        }
    }

    fun requireDescendant(actual: UUID, ancestor: UUID) {
        val visited=mutableSetOf<UUID>()
        var current: UUID?=actual
        while(current!=null && visited.add(current)) {
            if(current==ancestor) return
            val proposed=pending[current]
            current=if(proposed!=null) proposed.first else sql.query("SELECT parent_segment_id FROM inventory_segment WHERE tenant_id=? AND id=?",sql.tenant,current) {
                it.optionalUuid("parent_segment_id")
            }.singleOrNull()
        }
        throw IllegalArgumentException("Actual stock identity is not allocated by the locked line or source issue")
    }

    private data class BoundLine(val identity: UUID?,val sku: UUID,val lot: UUID?,val quantity: StockQuantity)
}
