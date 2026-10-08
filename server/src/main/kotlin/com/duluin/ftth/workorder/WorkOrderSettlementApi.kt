package com.duluin.ftth.workorder

import com.duluin.ftth.iam.CurrentAuthority
import java.util.UUID

interface WorkOrderSettlementApi {
    fun lockApproved(id: UUID, authority: CurrentAuthority): ApprovedWorkOrderContext
    fun lockCompleted(id: UUID, authority: CurrentAuthority): ReferenceWorkOrderSettlement
}

data class ApprovedWorkOrderContext(val material: WorkOrderMaterialContext, val approvedBy: UUID?,
    val completedBy: UUID, val proofHash: String)

data class ReferenceWorkOrderSettlement(val workOrder: ApprovedWorkOrderContext,
    val completion: com.duluin.ftth.workorder.application.port.inbound.ReferenceWorkOrderCompletionView)
