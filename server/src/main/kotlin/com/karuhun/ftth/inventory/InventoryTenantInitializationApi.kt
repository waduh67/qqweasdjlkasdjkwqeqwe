package com.karuhun.ftth.inventory

interface InventoryTenantInitializationApi {
    fun initializeNewEmptyTenant(): TenantCutoverSnapshot
    fun initializeExistingTenant(): TenantCutoverSnapshot
}
