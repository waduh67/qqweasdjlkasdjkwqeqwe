package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialHandoverStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialLifecycleStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialResidualStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

data class MaterialHandoverAuthorization(val authorizationId: UUID, val workOrderId: UUID, val request: MaterialResidualRequest,
    val senderId: UUID, val receiverId: UUID, val dispatcherId: UUID, val recordedAt: Instant)

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class MaterialHandoverService(private val authority: CurrentAuthorityApi, private val lifecycle: MaterialLifecycleStore,
    private val residuals: MaterialResidualStore, private val store: MaterialHandoverStore) {
    private val mapper = jacksonObjectMapper()

    fun authorize(context: MaterialPlanningContext, request: MaterialResidualRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        context.authority.assertHeld()
        context.cutover.assertHeld()
        val current = authority.lockCurrent()
        receiptPermission(current, "workorder.order.assign")
        receiptPermission(current, "inventory.issue.manage")
        receiptKey(metadata.idempotencyKey)
        if (request.authorizationId != null || request.quantityBase.toLongOrNull()?.let { it > 0 } != true ||
            request.evidenceReference.isBlank() || request.reason.isBlank()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        lifecycle.lock(context.workOrderId)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        store.replay(context, metadata.idempotencyKey, canonical.hash)?.let { return it }
        if (request.workOrderRevision != context.workOrderRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val target = residuals.target(request.targetLocationId)
        val receiver = target.second ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (target.first != "TECHNICIAN" || receiver !in context.activeAssigneeIds) masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        val sender = store.sender(context, request)
        if (sender == receiver || current.fence.identity.userId in setOf(sender, receiver)) masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        val authorization = MaterialHandoverAuthorization(UUID.randomUUID(), context.workOrderId, request, sender, receiver,
            current.fence.identity.userId, lifecycle.now())
        return store.record(context, authorization, metadata.idempotencyKey, canonical.hash)
    }
}
