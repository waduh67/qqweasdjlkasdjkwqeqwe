package com.karuhun.ftth.fieldservice.application.service

import com.karuhun.ftth.common.domain.error.ConflictException
import com.karuhun.ftth.fieldservice.FieldServiceApi
import com.karuhun.ftth.fieldservice.VisitFulfillmentCommand
import com.karuhun.ftth.fieldservice.VisitFulfillmentResult
import com.karuhun.ftth.fieldservice.application.port.outbound.CommandOutcomeStore
import com.karuhun.ftth.fieldservice.application.port.outbound.VisitRepository
import com.karuhun.ftth.fieldservice.domain.model.CommandMetadata
import com.karuhun.ftth.fieldservice.domain.model.VisitState
import com.karuhun.ftth.fieldservice.domain.model.VisitPurpose
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class FieldServiceFulfillmentApiService(
    private val visits: VisitRepository,
    private val outcomes: CommandOutcomeStore,
    private val visitRepository: VisitRepository,
    private val receipts: com.karuhun.ftth.fieldservice.adapter.outbound.persistence.VisitFulfillmentReceiptStore,
    private val operationScope: com.karuhun.ftth.common.infrastructure.persistence.FulfillmentOperationScope,
) : FieldServiceApi {
    override fun visit(id: UUID) = visitRepository.findById(
        com.karuhun.ftth.common.tenant.TenantContext.tenantId(),
        id,
    )?.takeIf { it.purpose == VisitPurpose.FULFILLMENT }?.let {
        com.karuhun.ftth.fieldservice.VisitRef(it.tenantId, it.id, it.orderId, it.workOrderId, it.technicianId, it.state, it.revision, null)
    }

    override fun visitsByWorkOrder(workOrderId: UUID): List<com.karuhun.ftth.fieldservice.VisitRef> =
        visitRepository.findByWorkOrderId(com.karuhun.ftth.common.tenant.TenantContext.tenantId(), workOrderId).filter { it.purpose == VisitPurpose.FULFILLMENT }.map {
            com.karuhun.ftth.fieldservice.VisitRef(it.tenantId, it.id, it.orderId, it.workOrderId, it.technicianId, it.state, it.revision, null)
        }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    override fun lockFulfillment(visitId: UUID, workOrderId: UUID): com.karuhun.ftth.fieldservice.VisitRef {
        val tenant = com.karuhun.ftth.common.tenant.TenantContext.tenantId()
        val visit = visitRepository.findByIdForUpdate(tenant,visitId) ?: throw ConflictException("FULFILLMENT_VISIT_BINDING")
        if (visit.purpose != VisitPurpose.FULFILLMENT || visit.workOrderId != workOrderId || visit.state != VisitState.CHECKED_OUT || !visit.assignmentActive)
            throw ConflictException("FULFILLMENT_VISIT_BINDING")
        return com.karuhun.ftth.fieldservice.VisitRef(tenant,visit.id,visit.orderId,visit.workOrderId,visit.technicianId,visit.state,visit.revision,null)
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    override fun applyFulfillment(command: VisitFulfillmentCommand): VisitFulfillmentResult {
        require(command.namespace.isNotBlank() && command.operationKey.isNotBlank() && command.payloadHash.isNotBlank())
        val bound = visits.findByIdForUpdate(command.tenantId, command.visitId) ?: throw ConflictException("FULFILLMENT_VISIT_BINDING")
        if (bound.purpose != VisitPurpose.FULFILLMENT) throw ConflictException("FULFILLMENT_VISIT_BINDING")
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
