package com.duluin.ftth.workorder

import com.duluin.ftth.common.security.AuthorityFence
import java.util.UUID

interface WorkOrderMaterialReworkApi {
    fun lock(workOrderId: UUID, authority: AuthorityFence): WorkOrderReworkContext
}

enum class WorkOrderEvidenceSource { PHOTO, SIGNATURE }
data class WorkOrderReworkEvidence(val revisionId: UUID, val kind: String, val source: WorkOrderEvidenceSource)
data class WorkOrderReworkContext(val material: WorkOrderMaterialContext, val previousEvidenceRevision: String,
    val evidenceRevision: String, val evidence: List<WorkOrderReworkEvidence>)
