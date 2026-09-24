package com.duluin.ftth.inventory

import java.util.UUID

interface InventorySettingsQueryApi {
    fun history(page: WarehousePageRequest): WarehousePage<WarehousePolicyDetails>
    fun delegations(page: WarehousePageRequest, locationId: UUID?, state: String?): WarehousePage<WarehouseDelegationView>
    fun candidates(page: WarehousePageRequest, locationId: UUID, operation: PolicyOperation, kind: String,
        approverId: UUID?, sourceRoleId: UUID?, query: String?): WarehousePage<WarehousePolicyChoice>
}
data class WarehouseDelegationEntry(val delegation: WarehouseDelegation, val state: String)
data class WarehouseDelegationView(val delegation: WarehouseDelegation, val state: String,
    val approver: WarehousePolicyChoice?, val delegate: WarehousePolicyChoice?, val sourceRole: WarehousePolicyChoice?, val location: WarehouseApprovalLocation)
