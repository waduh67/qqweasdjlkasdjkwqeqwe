package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import java.util.UUID

internal data class PostingLineAllocation(
    val quantity: StockQuantity,
    val inbound: List<PostingLeg>,
    val retained: List<PostingLeg>,
    val factual: List<PostingLeg>,
) {
    val factCapacity: StockQuantity = factual.fold(StockQuantity.of(0,quantity.unit)) { sum,leg -> sum+leg.quantity }
}

internal class PostingLineBindings(private val sql: PostingSql, private val command: WarehousePost) {
    private val children=command.splits.flatMap { split -> split.children.map { it.id to (split.parentId to it) } }
    private val pending=children.toMap()

    fun validate(): Map<UUID,PostingLineAllocation> {
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
                    child.first==source.dimension.stockIdentityId && unchangedPosition(leg,source)
                }
            }
            val allocated=debit-retained.fold(zero) { sum,leg -> sum+leg.quantity }
            val quantity=if(allocated.quantityBase==0L) debit else allocated
            require(quantity.quantityBase>0 && quantity.quantityBase<=line.quantity.quantityBase) { "Actual posting quantity exceeds the locked line or has no allocated quantity" }
            val factual=incoming.filter { leg -> command.legs.none { source -> source.direction==LegDirection.OUT && unchangedPosition(leg,source) } }
            id to PostingLineAllocation(quantity,incoming,retained,factual)
        }
    }

    fun validateFacts(allocations: Map<UUID,PostingLineAllocation>) {
        val incoming=allocations.flatMap { (line,allocation) -> allocation.inbound.map { line to it } }
        val totals=mutableMapOf<UUID,StockQuantity>()
        command.facts.forEach { fact ->
            val matches=incoming.filter { it.second.dimension.stockIdentityId==fact.stockIdentityId }
            require(matches.size==1) { "Material fact must identify exactly one inbound leg and document line" }
            val (line,leg)=matches.single()
            val allocation=allocations.getValue(line)
            require(leg !in allocation.retained && leg in allocation.factual) { "Unchanged retained stock cannot produce a material movement fact" }
            require(fact.quantity==leg.quantity) { "Material fact quantity must equal its allocated inbound leg" }
            val total=(totals[line] ?: StockQuantity.of(0,allocation.quantity.unit))+fact.quantity
            require(total.quantityBase<=allocation.factCapacity.quantityBase && total.quantityBase<=allocation.quantity.quantityBase) { "Material facts exceed the actual allocated quantity of their line" }
            totals[line]=total
        }
    }

    private fun unchangedPosition(inbound: PostingLeg,outbound: PostingLeg): Boolean {
        val samePiece=inbound.dimension.stockIdentityId==outbound.dimension.stockIdentityId ||
            pending[inbound.dimension.stockIdentityId]?.first==outbound.dimension.stockIdentityId
        return samePiece && inbound.status==outbound.status &&
            inbound.dimension.copy(stockIdentityId=outbound.dimension.stockIdentityId)==outbound.dimension
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
