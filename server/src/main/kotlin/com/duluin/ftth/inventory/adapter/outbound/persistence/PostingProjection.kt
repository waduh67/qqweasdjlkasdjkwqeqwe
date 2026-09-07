package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.AssetLegalOwner
import com.duluin.ftth.inventory.WarehouseCondition
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

internal class PostingProjection(private val sql: PostingSql) {
    fun lock(dimension: PostingDimension, zero: StockQuantity, status: InventoryStatus, now: Instant): PostingBalance {
        sql.update("""INSERT INTO inventory_balance_projection(id,tenant_id,item_id,sku_id,stock_identity_id,lot_id,location_id,custody_owner_id,
            custody_owner_kind,condition,legal_owner,status,quantity_base,base_unit,rebuilt_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)
            ON CONFLICT (tenant_id,sku_id,stock_identity_id,lot_id,location_id,custody_owner_id,custody_owner_kind,condition,legal_owner)
            WHERE warehouse_admission='VERIFIED' DO NOTHING""",UUID.randomUUID(),sql.tenant,dimension.stockIdentityId,dimension.skuId,
            dimension.stockIdentityId,dimension.lotId,dimension.locationId,dimension.custodianId,dimension.custodianKind,dimension.condition,dimension.legalOwner,status,zero.unit,now)
        return sql.query("SELECT quantity_base,base_unit,status FROM inventory_balance_projection WHERE $predicate FOR UPDATE",*parameters(dimension)) {
            PostingBalance(dimension,StockQuantity.of(it.getLong("quantity_base"),StockUnit.valueOf(it.getString("base_unit"))),InventoryStatus.valueOf(it.getString("status")))
        }.single()
    }

    fun set(dimension: PostingDimension, quantity: StockQuantity, status: InventoryStatus, now: Instant) {
        check(sql.update("UPDATE inventory_balance_projection SET quantity_base=?,status=?,rebuilt_at=?,revision=revision+1,updated_at=? WHERE $predicate",
            quantity.quantityBase,status,now,now,*parameters(dimension))==1)
    }

    fun rebuild(): List<PostingBalance> {
        val rebuilt=sql.query("""SELECT leg.sku_id,leg.stock_identity_id,leg.lot_id,leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,
            leg.condition,leg.legal_owner,leg.base_unit,
            sum(CASE leg.direction WHEN 'IN' THEN leg.quantity_base::numeric ELSE -leg.quantity_base::numeric END) quantity_base,
            (array_agg(leg.status ORDER BY movement.server_received_at DESC,movement.id DESC,leg.id DESC))[1] status
            FROM inventory_movement_leg leg JOIN inventory_movement movement ON movement.tenant_id=leg.tenant_id AND movement.id=leg.movement_id
            WHERE leg.tenant_id=? AND leg.warehouse_admission='VERIFIED' AND movement.state='APPLIED' AND leg.status<>'RECEIPT_SOURCE'
            GROUP BY leg.sku_id,leg.stock_identity_id,leg.lot_id,leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,leg.condition,leg.legal_owner,leg.base_unit
            ORDER BY leg.sku_id,leg.stock_identity_id,leg.lot_id,leg.location_id,leg.custody_owner_id,leg.custody_owner_kind,leg.condition,leg.legal_owner
        """,sql.tenant) { rows -> PostingBalance(dimension(rows),StockQuantity.of(rows.getLong("quantity_base"),StockUnit.valueOf(rows.getString("base_unit"))),InventoryStatus.valueOf(rows.getString("status"))) }
        val now=Instant.now()
        val existing=sql.query("SELECT * FROM inventory_balance_projection WHERE tenant_id=? AND warehouse_admission='VERIFIED' ORDER BY sku_id,stock_identity_id,location_id",sql.tenant) {
            PostingBalance(dimension(it),StockQuantity.of(it.getLong("quantity_base"),StockUnit.valueOf(it.getString("base_unit"))),InventoryStatus.valueOf(it.getString("status")))
        }
        existing.forEach { set(it.dimension,StockQuantity.of(0,it.quantity.unit),it.status,now) }
        rebuilt.forEach { position ->
            lock(position.dimension,StockQuantity.of(0,position.quantity.unit),position.status,now)
            set(position.dimension,position.quantity,position.status,now)
        }
        return rebuilt.filter { it.quantity.quantityBase>0 }
    }

    private fun parameters(dimension: PostingDimension): Array<Any?> = arrayOf(sql.tenant,dimension.skuId,dimension.stockIdentityId,dimension.lotId,
        dimension.locationId,dimension.custodianId,dimension.custodianKind,dimension.condition,dimension.legalOwner)

    companion object {
        const val predicate="tenant_id=? AND sku_id=? AND stock_identity_id=? AND lot_id IS NOT DISTINCT FROM ? AND location_id=? AND custody_owner_id=? AND custody_owner_kind=? AND condition=? AND legal_owner=? AND warehouse_admission='VERIFIED'"
        fun dimension(rows: ResultSet) = PostingDimension(rows.uuid("sku_id"),rows.uuid("stock_identity_id"),rows.optionalUuid("lot_id"),rows.uuid("location_id"),
            rows.uuid("custody_owner_id"),OwnerKind.valueOf(rows.getString("custody_owner_kind")),WarehouseCondition.valueOf(rows.getString("condition")),AssetLegalOwner.valueOf(rows.getString("legal_owner")))
    }
}
