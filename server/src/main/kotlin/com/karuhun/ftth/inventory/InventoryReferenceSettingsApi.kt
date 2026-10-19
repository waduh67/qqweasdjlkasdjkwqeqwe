package com.karuhun.ftth.inventory

interface InventoryReferenceSettingsApi {
    fun settings(): ReferenceOperationalSettings
}

data class ReferenceOperationalSettings(val revision: Long = 0, val requireManagerApproval: Boolean = true, val overdueDays: Int = 3)
