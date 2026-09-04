package com.duluin.ftth.fieldservice.domain.model

import java.time.Instant
import java.util.UUID

data class WorkSession(
    val id: UUID,
    val tenantId: UUID,
    val visitId: UUID,
    val workOrderId: UUID,
    val technicianId: UUID,
    val startedAt: Instant?,
    val endedAt: Instant?,
    val submittedAt: Instant?,
)
