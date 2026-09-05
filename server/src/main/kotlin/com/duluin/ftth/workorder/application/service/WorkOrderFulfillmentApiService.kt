package com.duluin.ftth.workorder.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.workorder.WorkOrderFulfillmentApi
import com.duluin.ftth.workorder.WorkOrderFulfillmentCommand
import com.duluin.ftth.workorder.WorkOrderFulfillmentResult
import com.duluin.ftth.workorder.application.port.outbound.WorkOrderRepository
import com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus
import org.springframework.stereotype.Service
import java.util.UUID
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderFulfillmentResultJpaEntity
import com.duluin.ftth.workorder.adapter.outbound.persistence.WorkOrderFulfillmentResultJpaRepository
import org.springframework.transaction.annotation.Transactional

@Service
class WorkOrderFulfillmentApiService(
    private val workOrders: WorkOrderRepository,
    private val outcomes: WorkOrderFulfillmentResultJpaRepository,
) : WorkOrderFulfillmentApi {

    override fun validateFulfillment(command: WorkOrderFulfillmentCommand) {
        val workOrder = workOrders.findById(command.workOrderId)
            ?: throw NotFoundException("Work order tidak ditemukan")
        if (workOrder.tenantId != command.tenantId) throw NotFoundException("Work order tidak ditemukan")
        if (workOrder.approvalStatus != WorkOrderApprovalStatus.APPROVED) {
            throw ConflictException("Work order fulfillment requires approval")
        }
    }

    @Transactional
    override fun recordFulfillmentResult(command: WorkOrderFulfillmentCommand): WorkOrderFulfillmentResult {
        require(command.namespace.isNotBlank() && command.operationKey.isNotBlank() && command.payloadHash.isNotBlank())
        val prior = outcomes.findByTenantIdAndNamespaceAndOperationKey(command.tenantId, command.namespace, command.operationKey)
        if (prior != null) {
            if (prior.payloadHash != command.payloadHash || prior.workOrderId != command.workOrderId) throw ConflictException("Operation key was used with a different payload")
            return WorkOrderFulfillmentResult(command.tenantId, command.workOrderId, prior.result, replayed = true)
        }
        val workOrder = workOrders.findById(command.workOrderId)
            ?: throw NotFoundException("Work order tidak ditemukan")
        if (workOrder.tenantId != command.tenantId) throw NotFoundException("Work order tidak ditemukan")
        if (workOrder.approvalStatus != WorkOrderApprovalStatus.APPROVED) {
            throw ConflictException("Work order fulfillment requires approval")
        }
        val inserted = outcomes.insertIfAbsent(UUID.randomUUID(), command.tenantId, command.workOrderId, command.namespace, command.operationKey, command.payloadHash, command.source, command.result)
        if (inserted == 0) {
            val replay = outcomes.findByTenantIdAndNamespaceAndOperationKey(command.tenantId, command.namespace, command.operationKey)
                ?: throw ConflictException("Fulfillment result disappeared during concurrent insert")
            if (replay.payloadHash != command.payloadHash || replay.workOrderId != command.workOrderId) throw ConflictException("Operation key was used with a different payload")
            return WorkOrderFulfillmentResult(command.tenantId, command.workOrderId, replay.result, replayed = true)
        }
        return WorkOrderFulfillmentResult(command.tenantId, command.workOrderId, command.result, replayed = false)
    }
}
