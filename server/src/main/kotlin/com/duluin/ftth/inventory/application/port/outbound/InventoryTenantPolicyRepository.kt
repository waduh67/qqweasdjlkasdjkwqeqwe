package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.TenantCutoverSnapshot

interface InventoryTenantPolicyRepository {
    fun read(): TenantCutoverSnapshot?
    fun lock(exclusive: Boolean): TenantCutoverSnapshot?
    fun initialize(newEmptyTenant: Boolean): TenantCutoverSnapshot
    fun beginValidation(expectedEpoch: Long): TenantCutoverSnapshot
}
