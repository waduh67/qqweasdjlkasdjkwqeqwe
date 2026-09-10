package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import java.time.Instant
import java.util.UUID

internal data class PostingPiece(val id: UUID, val sku: UUID, val lot: UUID?, val kind: String, val quantity: StockQuantity, val revision: Long)

internal class PostingStock(private val sql: PostingSql) {
    private val pieces=mutableMapOf<UUID,PostingPiece>()
    private val positions=mutableMapOf<PostingDimension,LockedPostingBalance>()

    fun lock(command: WarehousePost) {
        val dimensions=(command.legs.map { it.dimension }+command.reservations.map { it.dimension }).distinct()
        command.reservations.map { it.dimension.locationId }.distinct().sortedBy(UUID::toString).forEach { location ->
            if(sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND id=? FOR SHARE",sql.tenant,location)==null)
                sql.fail(WarehouseErrorCode.NOT_FOUND)
        }
        dimensions.mapNotNull { it.lotId }.distinct().sortedBy(UUID::toString).forEach { lot ->
            if(sql.value("SELECT id FROM inventory_lot WHERE tenant_id=? AND id=? AND warehouse_admission='VERIFIED' FOR UPDATE",sql.tenant,lot)==null)
                sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
        val newIds=command.splits.flatMap { it.children }.map { it.id }.toSet()
        dimensions.filter { it.stockIdentityId !in newIds }.sortedBy { it.orderKey() }.map { it.stockIdentityId }.distinct().forEach { id ->
            val piece=sql.query("SELECT * FROM inventory_segment WHERE tenant_id=? AND id=? AND warehouse_admission='VERIFIED' AND state='ACTIVE' FOR UPDATE",sql.tenant,id) {
                PostingPiece(id,it.uuid("sku_id"),it.optionalUuid("lot_id"),it.getString("kind"),StockQuantity.of(it.getLong("quantity_base"),StockUnit.valueOf(it.getString("base_unit"))),it.getLong("revision"))
            }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            pieces[id]=piece
        }
        command.legs.filter { it.dimension.stockIdentityId !in newIds }.forEach { leg ->
            val piece=pieces.getValue(leg.dimension.stockIdentityId)
            require(piece.sku==leg.dimension.skuId && piece.lot==leg.dimension.lotId && piece.quantity.unit==leg.quantity.unit)
            if(piece.quantity.unit==StockUnit.MM || piece.kind=="SERIAL") require(piece.quantity==leg.quantity) { "A physical piece must move whole or split atomically" }
            if(leg.endpoint==PostingEndpoint.RECEIPT_SOURCE) {
                require(sql.value("SELECT code FROM inventory_location WHERE tenant_id=? AND id=?",sql.tenant,leg.dimension.locationId)=="RECEIPT_SOURCE")
                require(sql.value("SELECT kind FROM inventory_document WHERE tenant_id=? AND id=?",sql.tenant,command.documentId)=="RECEIPT")
                require(sql.value("SELECT id FROM inventory_movement_leg WHERE tenant_id=? AND stock_identity_id=? LIMIT 1",sql.tenant,piece.id)==null) { "Identity was already received" }
                require(sql.value("""SELECT line.document_id FROM inventory_document_line line
                    LEFT JOIN inventory_lot lot ON lot.tenant_id=line.tenant_id AND lot.origin_document_line_id=line.id
                    LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=line.tenant_id AND asset.origin_document_line_id=line.id
                    WHERE line.tenant_id=? AND (lot.id=? OR asset.id=?)""",sql.tenant,piece.lot,piece.id)==command.documentId.toString())
            }
        }
        command.legs.forEach { leg ->
            val code=sql.value("SELECT code FROM inventory_location WHERE tenant_id=? AND id=? AND state='ACTIVE'",sql.tenant,leg.dimension.locationId)
                ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
            if(leg.endpoint==PostingEndpoint.CONSUMED) require(code=="CONSUMED")
            if(leg.endpoint==PostingEndpoint.PHYSICAL) require(code !in setOf("CONSUMED","RECEIPT_SOURCE"))
        }
    }

    fun split(command: WarehousePost) {
        command.splits.sortedBy { it.parentId.toString() }.forEach { split ->
            val parent=pieces.getValue(split.parentId)
            if(parent.revision!=split.expectedRevision) sql.fail(WarehouseErrorCode.STALE_REVISION)
            require(parent.kind!="SERIAL" && split.children.size>=2)
            require(split.children.map { it.id }.distinct().size==split.children.size)
            require(split.children.fold(StockQuantity.of(0,parent.quantity.unit)) { sum, child -> sum+child.quantity }==parent.quantity)
            sql.update("UPDATE inventory_segment SET state='SPLIT',revision=revision+1 WHERE tenant_id=? AND id=?",sql.tenant,parent.id)
            split.children.sortedBy { it.id.toString() }.forEach { child ->
                require(child.quantity.quantityBase>0)
                val incoming=command.legs.single { it.direction==LegDirection.IN && it.dimension.stockIdentityId==child.id }
                require(incoming.quantity==child.quantity && incoming.dimension.skuId==parent.sku && incoming.dimension.lotId==parent.lot)
                sql.update("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,parent_segment_id,kind,base_unit,quantity_base) VALUES (?,?,?,?,?,?,?,?)",
                    child.id,sql.tenant,parent.sku,parent.lot,parent.id,child.kind,child.quantity.unit,child.quantity.quantityBase)
                pieces[child.id]=PostingPiece(child.id,parent.sku,parent.lot,child.kind.name,child.quantity,0)
            }
        }
    }

    fun lockBalances(command: WarehousePost, now: Instant) {
        val projection=PostingProjection(sql)
        command.legs.filter { it.endpoint!=PostingEndpoint.RECEIPT_SOURCE }.groupBy { it.dimension }.entries.sortedBy { it.key.orderKey() }.forEach { (dimension,legs) ->
            if (dimension in positions) return@forEach
            val statuses=legs.filter { it.direction==LegDirection.IN }.map { it.status }.distinct()
            require(statuses.size<=1) { "Inbound position statuses contradict each other" }
            positions[dimension]=projection.lock(dimension,StockQuantity.of(0,legs.first().quantity.unit),statuses.singleOrNull() ?: legs.first().status,now)
        }
    }

    fun legs(command: WarehousePost, result: WarehousePostResult) {
        lockBalances(command, result.recordedAt)
        command.legs.forEach { leg ->
            val dimension=leg.dimension
            sql.update("""INSERT INTO inventory_movement_leg(id,tenant_id,movement_id,direction,item_id,sku_id,location_id,quantity,serialized,
                custody_owner_id,custody_owner_kind,status,quantity_base,base_unit,stock_identity_id,lot_id,document_line_id,condition,legal_owner,revision)
                VALUES (?,?,?,?,?,?,?,NULL,?,?,?,?,?,?,?,?,?,?,?,?)""",UUID.randomUUID(),sql.tenant,result.postingId,leg.direction,dimension.stockIdentityId,
                dimension.skuId,dimension.locationId,pieces.getValue(dimension.stockIdentityId).kind=="SERIAL",dimension.custodianId,dimension.custodianKind,
                if(leg.endpoint==PostingEndpoint.RECEIPT_SOURCE) InventoryStatus.RECEIPT_SOURCE else leg.status,leg.quantity.quantityBase,leg.quantity.unit,
                dimension.stockIdentityId,dimension.lotId,leg.documentLineId,dimension.condition,dimension.legalOwner,
                if(leg.endpoint==PostingEndpoint.RECEIPT_SOURCE) 0L else Math.addExact(positions.getValue(dimension).revision,1))
        }
    }

    fun balances(command: WarehousePost, now: Instant) {
        val physical=command.legs.filter { it.endpoint!=PostingEndpoint.RECEIPT_SOURCE }
        val projection=PostingProjection(sql)
        val changes=physical.groupBy { it.dimension }.entries.sortedBy { it.key.orderKey() }.map { (dimension,legs) ->
            val zero=StockQuantity.of(0,legs.first().quantity.unit)
            val before=positions.getValue(dimension)
            val outgoing=legs.filter { it.direction==LegDirection.OUT }.fold(zero) { sum,leg -> sum+leg.quantity }
            val incoming=legs.filter { it.direction==LegDirection.IN }.fold(zero) { sum,leg -> sum+leg.quantity }
            if(before.quantity.quantityBase<outgoing.quantityBase) sql.fail(WarehouseErrorCode.INSUFFICIENT_STOCK)
            require(legs.filter { it.direction==LegDirection.OUT }.all { it.status==before.status }) { "Source position state mismatch" }
            val status=legs.filter { it.direction==LegDirection.IN }.map { it.status }.distinct().singleOrNull() ?: before.status
            Triple(dimension,(before.quantity-outgoing)+incoming,status)
        }.sortedBy { it.first.orderKey() }
        changes.sortedBy { it.second.quantityBase>0 }.forEach { (dimension,quantity,status) -> projection.set(dimension,quantity,status,now) }
        pieces.values.forEach { piece ->
            val total=sql.value("SELECT coalesce(sum(quantity_base::numeric),0) FROM inventory_balance_projection WHERE tenant_id=? AND stock_identity_id=? AND warehouse_admission='VERIFIED'",sql.tenant,piece.id)!!.toLong()
            val split=command.splits.any { it.parentId==piece.id }
            require(total==(if(split) 0L else piece.quantity.quantityBase)) { "Physical positions $total must equal piece capacity ${piece.quantity.quantityBase}, split=$split" }
        }
    }

    fun custody(command: WarehousePost) {
        pieces.values.filter { it.kind=="SERIAL" }.sortedBy { it.id.toString() }.forEach { piece ->
            val target=command.legs.singleOrNull { it.direction==LegDirection.IN && it.dimension.stockIdentityId==piece.id } ?: return@forEach
            val dimension=target.dimension
            check(sql.update("""UPDATE inventory_serialized_asset SET location_id=?,custody_owner_id=?,custody_owner_kind=?,status=?,condition=?,legal_owner=?,revision=revision+1
                WHERE tenant_id=? AND id=? AND warehouse_admission='VERIFIED'""",dimension.locationId,dimension.custodianId,dimension.custodianKind,target.status,
                dimension.condition,dimension.legalOwner,sql.tenant,piece.id)==1)
        }
    }
}
