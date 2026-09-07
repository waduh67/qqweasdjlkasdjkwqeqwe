package com.duluin.ftth.inventory

import com.duluin.ftth.iam.CurrentAuthority
import java.util.UUID

interface InventoryCommandWorkOrderPort {
    fun lock(id: UUID, expectedRevision: Long?, authority: CurrentAuthority, customerId: UUID?, fieldAction: Boolean): Long
}
