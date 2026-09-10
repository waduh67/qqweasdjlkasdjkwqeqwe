package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.inventory.domain.model.StockUnit
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class MaterialPlanValidation(private val masters: WarehouseMasterStore) {
    fun lines(input: List<MaterialPlanLine>, current: CurrentAuthority, prior: MaterialPlanSnapshot?): List<MaterialPlanSnapshotLine> {
        receiptPermission(current, "inventory.sku.view")
        if (input.isEmpty() || input.size > 100 || input.distinctBy { it.skuId }.size != input.size) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        masters.lockTopology()
        val ids = (input.map { it.skuId } + input.mapNotNull { it.substitution?.originalSkuId }).distinct().sortedBy(UUID::toString)
        val snapshots = ids.associateWith { sku(it) }
        return input.mapIndexed { index, line ->
            val sku = snapshots.getValue(line.skuId)
            val quantity = StockQuantity.parseBase(line.quantityBase, StockUnit.valueOf(line.baseUnit.name))
            if (quantity.quantityBase == 0L || sku.baseUnit != line.baseUnit) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            val original = line.substitution?.let { substitution ->
                receiptPermission(current, "inventory.request.override")
                if (substitution.reason.isBlank() || substitution.reason.length > 1000 || substitution.originalSkuId == line.skuId)
                    masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
                val source = prior?.lines?.singleOrNull { it.id == substitution.originalPlanLineId && it.sku.id == substitution.originalSkuId }
                    ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                if (source.sku.baseUnit != sku.baseUnit || source.sku.tracking != sku.tracking) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                source.sku
            }
            MaterialPlanSnapshotLine(UUID.randomUUID(), index + 1, sku, quantity.quantityBase.toString(), line.continuousCut, line.substitution, original)
        }
    }
    fun revalidate(lines: List<MaterialPlanSnapshotLine>, current: CurrentAuthority) {
        receiptPermission(current, "inventory.sku.view")
        masters.lockTopology()
        lines.sortedBy { it.sku.id.toString() }.forEach { line ->
            val live = sku(line.sku.id)
            if (live.baseUnit != line.sku.baseUnit || live.tracking != line.sku.tracking) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        }
    }
    private fun sku(id: UUID): MaterialSkuSnapshot {
        val row = masters.get(MasterKind.SKU, id, true) as SkuSnapshot
        if (row.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.NOT_FOUND)
        return MaterialSkuSnapshot(row.id, row.revision, row.code, row.name, row.tracking, row.baseUnit)
    }
}
