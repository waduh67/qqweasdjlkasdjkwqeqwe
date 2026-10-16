package com.karuhun.ftth.inventory

import com.karuhun.ftth.iam.CurrentAuthority
import java.util.UUID

interface InventoryCommandWorkOrderPort {
    fun lock(id: UUID, expectedRevision: Long?, authority: CurrentAuthority, customerId: UUID?, fieldAction: Boolean): Long
}
