package com.karuhun.ftth.inventory.application.service

import com.karuhun.ftth.iam.CurrentAuthority
import java.util.UUID

interface WarehouseApprovalSourceLock {
    fun lock(sourceDocumentId: UUID, current: CurrentAuthority)
}
