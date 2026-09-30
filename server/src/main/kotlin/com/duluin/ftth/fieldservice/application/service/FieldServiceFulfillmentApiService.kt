package com.duluin.ftth.fieldservice.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.fieldservice.FieldServiceApi
import com.duluin.ftth.fieldservice.VisitFulfillmentCommand
import com.duluin.ftth.fieldservice.VisitFulfillmentResult
import com.duluin.ftth.fieldservice.application.port.outbound.CommandOutcomeStore
import com.duluin.ftth.fieldservice.application.port.outbound.VisitRepository
import com.duluin.ftth.fieldservice.domain.model.CommandMetadata
import com.duluin.ftth.fieldservice.domain.model.VisitState
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class FieldServiceFulfillmentApiService(
    private val visits: VisitRepository,
    private val outcomes: CommandOutcomeStore,
    private val visitRepository: VisitRepository,
    private val receipts: com.duluin.ftth.fieldservice.adapter.outbound.persistence.VisitFulfillmentReceiptStore,
    private val operationScope: com.duluin.ftth.common.infrastructure.persistence.FulfillmentOperationScope,
) : FieldServiceApi {
    override fun visit(id: UUID) = visitRepository.findById(
        com.duluin.ftth.common.tenant.TenantContext.tenantId(),
        id,
    )?.let {
        com.duluin.ftth.fieldservice.VisitRef(it.tenantId, it.id, it.orderId, it.workOrderId, it.technicianId, it.state, it.revision, null)
    }

    override fun visitsByWorkOrder(workOrderId: UUID): List<com.duluin.ftth.fieldservice.VisitRef> =
        visitRepository.findByWorkOrderId(com.duluin.ftth.common.tenant.TenantContext.tenantId(), workOrderId).map {
            com.duluin.ftth.fieldservice.VisitRef(it.tenantId, it.id, it.orderId, it.workOrderId, it.technicianId, it.state, it.revision, null)
        }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    override fun lockFulfillment(visitId: UUID, workOrderId: UUID): com.duluin.ftth.fieldservice.VisitRef {
        val tenant = com.duluin.ftth.common.tenant.TenantContext.tenantId()
        val visit = visitRepository.findById(tenant,visitId) ?: throw ConflictException("FULFILLMENT_VISIT_BINDING")
        if (visit.workOrderId != workOrderId || visit.state != VisitState.CHECKED_OUT || !visit.assignmentActive)
            throw ConflictException("FULFILLMENT_VISIT_BINDING")
        return com.duluin.ftth.fieldservice.VisitRef(tenant,visit.id,visit.orderId,visit.workOrderId,visit.technicianId,visit.state,visit.revision,null)
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    override fun applyFulfillment(command: VisitFulfillmentCommand): VisitFulfillmentResult {
        require(command.namespace.isNotBlank() && command.operationKey.isNotBlank() && command.payloadHash.isNotBlank())
        val metadata = CommandMetadata(
            tenantId = command.tenantId,
            actorId = command.actorId,
            namespace = command.namespace,
            operationKey = command.operationKey,
            payloadHash = command.payloadHash,
            revision = command.expectedRevision,
        )
        val prior = outcomes.find(metadata)
        if (prior != null && prior.payloadHash != command.payloadHash) {
            throw ConflictException("Operation key was used with a different payload")
        }
        if (prior != null) {
            receipts.requireReplay(command)
            val replay = visits.findById(command.tenantId, command.visitId) ?: throw ConflictException("Visit is not available in this tenant")
            return VisitFulfillmentResult(command.tenantId, command.visitId, replay.state, replay.revision, true)
        }
        val visit = visits.findById(command.tenantId, command.visitId) ?: throw ConflictException("Visit is not available in this tenant")
        command.reference?.let(operationScope::enter)
        visit.submit(metadata, command.receivedAt)
        visits.save(visit)
        outcomes.record(metadata, command.visitId, visit.state.name)
        val result = VisitFulfillmentResult(command.tenantId, command.visitId, visit.state, visit.revision, false)
        receipts.record(command,result)
        return result
    }
}
