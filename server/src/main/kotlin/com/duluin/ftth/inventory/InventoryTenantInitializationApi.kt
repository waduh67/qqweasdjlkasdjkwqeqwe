package com.duluin.ftth.inventory

interface InventoryTenantInitializationApi {
    fun initializeNewEmptyTenant(): TenantCutoverSnapshot
    fun initializeExistingTenant(): TenantCutoverSnapshot
}
