package com.duluin.ftth.workorder

import com.duluin.ftth.iam.CurrentAuthority
import java.util.UUID

interface WorkOrderSettlementApi {
    fun lockApproved(id: UUID, authority: CurrentAuthority): ApprovedWorkOrderContext
}

data class ApprovedWorkOrderContext(val material: WorkOrderMaterialContext, val approvedBy: UUID,
    val completedBy: UUID, val proofHash: String)
