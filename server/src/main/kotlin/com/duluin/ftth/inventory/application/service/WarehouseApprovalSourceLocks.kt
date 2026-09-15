package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import java.util.UUID

interface WarehouseApprovalSourceLock {
    fun lock(sourceDocumentId: UUID, current: CurrentAuthority)
}
