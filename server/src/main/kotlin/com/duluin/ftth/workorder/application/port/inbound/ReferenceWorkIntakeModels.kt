package com.duluin.ftth.workorder.application.port.inbound

import com.duluin.ftth.workorder.domain.model.WorkOrderPriority
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import java.time.Instant
import java.util.UUID

enum class ReferenceWorkSource { PSB, HELPDESK, PREVENTIVE }
data class ReferenceWorkIntakeView(val id: UUID, val code: String, val source: ReferenceWorkSource, val sourceId: UUID,
    val type: WorkOrderType, val title: String, val description: String, val priority: WorkOrderPriority,
    val customerId: UUID, val areaId: UUID?, val scheduledAt: Instant?, val createdAt: Instant, val dispatched: Boolean)
data class ReferenceWorkDispatchInput(val typeId: UUID, val technicianId: UUID, val areaId: UUID, val scheduledAt: Instant? = null)
