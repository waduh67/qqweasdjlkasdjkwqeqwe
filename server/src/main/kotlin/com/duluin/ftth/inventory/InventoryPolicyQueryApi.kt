package com.duluin.ftth.inventory

import java.util.UUID

interface InventoryPolicyQueryApi {
    fun details(): WarehousePolicyDetails
    fun approvers(locations: Set<UUID>, kind: String, query: String?, page: WarehousePageRequest): WarehousePage<WarehousePolicyChoice>
}
data class WarehousePolicyChoice(val id: UUID, val name: String)
data class WarehousePolicyReferences(val users: List<WarehousePolicyChoice>, val roles: List<WarehousePolicyChoice>, val locations: List<WarehouseApprovalLocation>)
data class WarehousePolicyDetails(val configured: Boolean, val current: WarehousePolicyVersion?, val references: WarehousePolicyReferences)
