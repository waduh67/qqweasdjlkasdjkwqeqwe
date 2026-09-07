package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*

internal class PostingReservations(private val sql: PostingSql) {
    fun apply(command: WarehousePost, result: WarehousePostResult) {
        command.reservations.sortedBy { it.dimension.orderKey()+it.id }.forEach { change ->
            val dimension=change.dimension
            val total=change.unpicked+change.picked
            val before=sql.query("SELECT revision,reserved_unpicked_base,reserved_picked_base FROM inventory_reservation WHERE tenant_id=? AND id=? FOR UPDATE",sql.tenant,change.id) {
                Triple(it.getLong("revision"),it.getLong("reserved_unpicked_base"),it.getLong("reserved_picked_base"))
            }.singleOrNull()
            if(before?.first != change.expectedRevision) sql.fail(WarehouseErrorCode.STALE_REVISION)
            val previousTotal=before?.let { Math.addExact(it.second,it.third) } ?: 0L
            if(total.quantityBase>previousTotal || change.picked.quantityBase>(before?.third ?: 0L)) {
                require(dimension.condition==com.duluin.ftth.inventory.WarehouseCondition.SERVICEABLE && dimension.legalOwner==com.duluin.ftth.inventory.AssetLegalOwner.ISP)
                val position=PostingProjection(sql).readLocked(dimension) ?: sql.fail(WarehouseErrorCode.INSUFFICIENT_STOCK)
                require(position.status==InventoryStatus.AVAILABLE && position.quantity.unit==total.unit && position.quantity.quantityBase>0) { "Reservation requires available physical stock" }
                val kind=sql.value("SELECT kind FROM inventory_location WHERE tenant_id=? AND id=? AND state='ACTIVE' AND issue_eligible FOR SHARE",sql.tenant,dimension.locationId)
                require(when(dimension.custodianKind) {
                    OwnerKind.WAREHOUSE -> kind in setOf("BIN","WAREHOUSE")
                    OwnerKind.TECHNICIAN -> kind=="TECHNICIAN"
                    OwnerKind.VEHICLE -> kind=="VEHICLE"
                    else -> false
                }) { "Reservation location is not approved for this custody scope" }
            }
            if(before == null) {
                require(change.state==ReservationState.OPEN && total.quantityBase>0)
                sql.update("""INSERT INTO inventory_reservation(id,tenant_id,document_line_id,sku_id,stock_identity_id,lot_id,base_unit,location_id,custodian_id,
                    custodian_kind,condition,legal_owner,reserved_unpicked_base,reserved_picked_base,state,submitted_at,expires_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",change.id,sql.tenant,change.documentLineId,dimension.skuId,dimension.stockIdentityId,dimension.lotId,
                    change.unpicked.unit,dimension.locationId,dimension.custodianId,dimension.custodianKind,dimension.condition,dimension.legalOwner,
                    change.unpicked.quantityBase,change.picked.quantityBase,change.state,result.recordedAt,change.expiresAt)
            } else {
                if(change.state==ReservationState.DISPATCHED) {
                    val dispatched=command.legs.filter { it.direction==LegDirection.OUT && it.dimension==dimension }.fold(StockQuantity.of(0,total.unit)) { sum,leg -> sum+leg.quantity }
                    require(dispatched.quantityBase==Math.addExact(before.second,before.third) && total.quantityBase==0L)
                } else if(command.splits.any { it.parentId==dimension.stockIdentityId }) {
                    val split=command.splits.single { it.parentId==dimension.stockIdentityId }
                    val children=command.reservations.filter { it.dimension.stockIdentityId in split.children.map { child -> child.id } && it.documentLineId==change.documentLineId }
                    require(change.state==ReservationState.RELEASED && total.quantityBase==0L)
                    require(children.fold(StockQuantity.of(0,total.unit)) { sum,child -> sum+child.unpicked }.quantityBase==before.second)
                    require(children.fold(StockQuantity.of(0,total.unit)) { sum,child -> sum+child.picked }.quantityBase==before.third)
                } else require(command.legs.none { it.direction==LegDirection.OUT && it.dimension==dimension }) { "Physical dispatch must consume its reservation" }
                check(sql.update("""UPDATE inventory_reservation SET reserved_unpicked_base=?,reserved_picked_base=?,state=?,expires_at=?,revision=revision+1
                    WHERE tenant_id=? AND id=? AND sku_id=? AND stock_identity_id=? AND lot_id IS NOT DISTINCT FROM ? AND location_id=? AND custodian_id=?
                    AND custodian_kind=? AND condition=? AND legal_owner=? AND document_line_id=? AND base_unit=?""",change.unpicked.quantityBase,change.picked.quantityBase,change.state,change.expiresAt,
                    sql.tenant,change.id,dimension.skuId,dimension.stockIdentityId,dimension.lotId,dimension.locationId,dimension.custodianId,dimension.custodianKind,
                    dimension.condition,dimension.legalOwner,change.documentLineId,total.unit)==1)
            }
        }
        (command.legs.map { it.dimension }+command.reservations.map { it.dimension }).distinct().forEach { dimension ->
            val reserved=sql.value("""SELECT coalesce(sum(reserved_unpicked_base::numeric+reserved_picked_base::numeric),0) FROM inventory_reservation
                WHERE tenant_id=? AND stock_identity_id=? AND lot_id IS NOT DISTINCT FROM ? AND location_id=? AND custodian_id=? AND custodian_kind=? AND condition=? AND legal_owner=? AND state='OPEN'""",
                sql.tenant,dimension.stockIdentityId,dimension.lotId,dimension.locationId,dimension.custodianId,dimension.custodianKind,dimension.condition,dimension.legalOwner)!!.toLong()
            val physical=sql.value("SELECT quantity_base FROM inventory_balance_projection WHERE ${PostingProjection.predicate}",sql.tenant,dimension.skuId,dimension.stockIdentityId,dimension.lotId,
                dimension.locationId,dimension.custodianId,dimension.custodianKind,dimension.condition,dimension.legalOwner)?.toLong() ?: 0
            if(reserved>physical) sql.fail(WarehouseErrorCode.INSUFFICIENT_STOCK)
        }
    }
}
