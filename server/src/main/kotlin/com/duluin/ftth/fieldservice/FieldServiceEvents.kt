package com.duluin.ftth.fieldservice

import java.time.Instant
import java.util.UUID

data class VisitCheckedIn(val tenantId: UUID, val visitId: UUID, val orderId: UUID, val workOrderId: UUID, val technicianId: UUID, val receivedAt: Instant)
data class VisitOnSite(val tenantId: UUID, val visitId: UUID, val orderId: UUID, val workOrderId: UUID, val technicianId: UUID, val receivedAt: Instant)
data class VisitCheckedOut(val tenantId: UUID, val visitId: UUID, val orderId: UUID, val workOrderId: UUID, val technicianId: UUID, val receivedAt: Instant)
data class VisitSubmitted(val tenantId: UUID, val visitId: UUID, val orderId: UUID, val workOrderId: UUID, val technicianId: UUID, val receivedAt: Instant)
data class VisitConflict(val tenantId: UUID, val visitId: UUID, val orderId: UUID, val workOrderId: UUID, val revision: Long, val receivedAt: Instant)
