package com.duluin.ftth.inventory

import java.util.UUID

data class WarehouseReplenishmentSku(val id: UUID, val code: String, val name: String, val state: WarehouseMasterState)
data class WarehouseReplenishmentLocation(val id: UUID, val code: String, val name: String?, val state: WarehouseMasterState, val replenishmentEligible: Boolean)
data class WarehouseReplenishmentRuleView(val rule: ReplenishmentRule, val sku: WarehouseReplenishmentSku, val location: WarehouseReplenishmentLocation)
data class WarehouseReplenishmentRequestView(val request: ReplenishmentRequest, val sku: WarehouseReplenishmentSku, val location: WarehouseReplenishmentLocation)
data class WarehouseReplenishmentDetails(val rule: ReplenishmentRule, val sku: WarehouseReplenishmentSku, val location: WarehouseReplenishmentLocation,
    val position: ReplenishmentPosition, val suggestedQuantityBase: String, val request: ReplenishmentRequest?)

interface InventoryReplenishmentQueryApi {
    fun rules(page: Int, size: Int, locationId: UUID?, skuId: UUID?, active: Boolean?): WarehousePage<WarehouseReplenishmentRuleView>
    fun requests(page: Int, size: Int, locationId: UUID?, skuId: UUID?, state: ReplenishmentState?): WarehousePage<WarehouseReplenishmentRequestView>
    fun rule(id: UUID): WarehouseReplenishmentDetails
    fun request(id: UUID): WarehouseReplenishmentDetails
}
