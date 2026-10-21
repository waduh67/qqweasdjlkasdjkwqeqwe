package com.karuhun.ftth.inventory.application.port.inbound

import com.karuhun.ftth.inventory.WarehouseBaseUnit
import java.util.UUID

data class ReferenceStockAttention(val skuId: UUID, val code: String, val name: String, val warehouseId: UUID, val warehouseName: String,
    val baseUnit: WarehouseBaseUnit, val availableBase: String, val minimumBase: String)
data class ReferenceStockOverview(val safe: Int, val low: Int, val empty: Int,
    val attention: List<ReferenceStockAttention>)
data class ReferenceRequestOverview(val states: Map<ReferenceRequestState, Long>, val actionable: Long,
    val latest: List<ReferenceRequestView>)
