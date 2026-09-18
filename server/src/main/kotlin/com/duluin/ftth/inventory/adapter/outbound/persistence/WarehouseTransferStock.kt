package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.PostingDimension
import com.duluin.ftth.inventory.application.service.TransferStock
import com.duluin.ftth.inventory.domain.model.InventoryStatus
import com.duluin.ftth.inventory.domain.model.OwnerKind
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class WarehouseTransferStock(private val jdbc: WarehouseCommandJdbc) {
    fun get(identity: UUID, location: UUID): TransferStock = jdbc.execute { sql ->
        sql.query("""SELECT balance.*,segment.revision piece_revision,sku.tracking,
            coalesce(origin.cost_total_minor,lot.cost_total_minor) cost_total,
            coalesce(origin.cost_basis_quantity_base,lot.cost_basis_quantity_base) cost_basis,
            coalesce(origin.currency,lot.currency) cost_currency
            FROM inventory_balance_projection balance
            JOIN inventory_segment segment ON segment.tenant_id=balance.tenant_id AND segment.id=balance.stock_identity_id
            JOIN inventory_sku sku ON sku.tenant_id=segment.tenant_id AND sku.id=segment.sku_id
            LEFT JOIN inventory_lot lot ON lot.tenant_id=segment.tenant_id AND lot.id=segment.lot_id
            LEFT JOIN inventory_serialized_asset asset ON asset.tenant_id=segment.tenant_id AND asset.id=segment.asset_id
            LEFT JOIN inventory_document_line origin ON origin.tenant_id=asset.tenant_id AND origin.id=asset.origin_document_line_id
            WHERE balance.tenant_id=? AND balance.stock_identity_id=? AND balance.location_id=? AND balance.quantity_base>0
            AND balance.warehouse_admission='VERIFIED' AND segment.warehouse_admission='VERIFIED'
            AND segment.state='ACTIVE' AND sku.state='ACTIVE'""", sql.tenant, identity, location) {
            TransferStock(PostingDimension(it.uuid("sku_id"), identity, it.optionalUuid("lot_id"), location,
                it.uuid("custody_owner_id"), OwnerKind.valueOf(it.getString("custody_owner_kind")),
                WarehouseCondition.valueOf(it.getString("condition")), AssetLegalOwner.valueOf(it.getString("legal_owner"))),
                it.getLong("quantity_base"), WarehouseBaseUnit.valueOf(it.getString("base_unit")),
                WarehouseTracking.valueOf(it.getString("tracking")), InventoryStatus.valueOf(it.getString("status")),
                it.getLong("piece_revision"), it.getString("cost_total")?.let { total ->
                    ReceiptCostSnapshot(total, it.getString("cost_currency"), it.getString("cost_basis")) })
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.INSUFFICIENT_STOCK)
    }

    fun assertUnallocated(identity: UUID) = jdbc.execute { sql ->
        if (sql.value("""WITH RECURSIVE ancestry AS (
            SELECT id,parent_segment_id FROM inventory_segment WHERE tenant_id=? AND id=?
            UNION ALL SELECT parent.id,parent.parent_segment_id FROM inventory_segment parent
            JOIN ancestry child ON child.parent_segment_id=parent.id WHERE parent.tenant_id=?)
            SELECT line.id FROM inventory_document_line line JOIN inventory_document document
            ON document.tenant_id=line.tenant_id AND document.id=line.document_id
            WHERE line.tenant_id=? AND line.stock_identity_id IN (SELECT id FROM ancestry)
            AND document.kind='ISSUE' LIMIT 1""", sql.tenant, identity, sql.tenant, sql.tenant) != null)
            sql.fail(WarehouseErrorCode.USE_WORKORDER_ASSET_WORKFLOW)
        if (sql.value("""SELECT id FROM inventory_reservation WHERE tenant_id=? AND stock_identity_id=?
            AND state='OPEN' AND (reserved_unpicked_base>0 OR reserved_picked_base>0) LIMIT 1""", sql.tenant, identity) != null)
            sql.fail(WarehouseErrorCode.INSUFFICIENT_STOCK)
    }
}
