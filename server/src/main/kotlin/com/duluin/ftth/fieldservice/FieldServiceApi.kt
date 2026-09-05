package com.duluin.ftth.fieldservice

import com.duluin.ftth.fieldservice.domain.model.VisitState
import java.time.Instant
import java.util.UUID

interface FieldServiceApi {
    fun visit(id: UUID): VisitRef?
    fun visitsByWorkOrder(workOrderId: UUID): List<VisitRef>
    fun applyFulfillment(command: VisitFulfillmentCommand): VisitFulfillmentResult
}

data class VisitFulfillmentCommand(
    val tenantId: UUID,
    val visitId: UUID,
    val actorId: UUID,
    val expectedRevision: Long,
    val namespace: String,
    val operationKey: String,
    val payloadHash: String,
    val receivedAt: Instant,
)

data class VisitFulfillmentResult(
    val tenantId: UUID,
    val visitId: UUID,
    val state: VisitState,
    val revision: Long,
    val replayed: Boolean,
)

data class VisitRef(
    val tenantId: UUID,
    val id: UUID,
    val orderId: UUID,
    val workOrderId: UUID,
    val technicianId: UUID,
    val state: VisitState,
    val revision: Long,
    val submittedAt: Instant?,
)
