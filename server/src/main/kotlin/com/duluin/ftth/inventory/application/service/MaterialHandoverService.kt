package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialHandoverStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialLifecycleStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialResidualStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

data class MaterialHandoverAuthorization(val authorizationId: UUID, val workOrderId: UUID, val request: MaterialResidualRequest,
    val senderId: UUID, val receiverId: UUID, val dispatcherId: UUID, val recordedAt: Instant, val sourceLocationId: UUID? = null)

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class MaterialHandoverService(private val authority: CurrentAuthorityApi, private val lifecycle: MaterialLifecycleStore,
    private val residuals: MaterialResidualStore, private val store: MaterialHandoverStore, private val scopes: InventoryWarehouseScopeApi,
    private val locations: WarehouseReceiptService, private val masters: WarehouseMasterStore) {
    private val mapper = jacksonObjectMapper()

    fun authorize(context: MaterialPlanningContext, request: MaterialResidualRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        context.authority.assertHeld()
        context.cutover.assertHeld()
        val current = authority.lockCurrent()
        receiptPermission(current, "workorder.order.assign")
        receiptPermission(current, "inventory.issue.manage")
        receiptKey(metadata.idempotencyKey)
        if (request.authorizationId != null || (request.expectedSenderId == null) != (request.expectedReceiverId == null) || request.quantityBase.toLongOrNull()?.let { it > 0 } != true ||
            request.evidenceReference.isBlank() || request.reason.isBlank()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        masters.lockTopology()
        val scope = scopes.currentUnderFence(current.fence)
        lifecycle.lock(context.workOrderId)
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        store.replay(context, metadata.idempotencyKey, canonical.hash)?.let { receipt ->
            val original = mapper.readValue(receipt.originalBody, MaterialHandoverAuthorization::class.java)
            original.sourceLocationId?.let { locations.authorizeLocation(it, current, scope) }
            locations.authorizeLocation(original.request.targetLocationId, current, scope)
            return receipt
        }
        if (request.workOrderRevision != context.workOrderRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        locations.authorizeLocation(request.targetLocationId, current, scope)
        val target = residuals.target(request.targetLocationId)
        val receiver = target.second ?: masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (target.first != "TECHNICIAN" || receiver !in context.activeAssigneeIds) masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
        val (sender, sourceLocation) = store.sender(context, request)
        locations.authorizeLocation(sourceLocation, current, scope)
        if ((request.expectedSenderId != null && request.expectedSenderId != sender) ||
            (request.expectedReceiverId != null && request.expectedReceiverId != receiver)) masterFailure(WarehouseErrorCode.STALE_REVISION)
        if (sender == receiver || current.fence.identity.userId in setOf(sender, receiver)) masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        val authorization = MaterialHandoverAuthorization(UUID.randomUUID(), context.workOrderId, request, sender, receiver,
            current.fence.identity.userId, lifecycle.now(), sourceLocation)
        return store.record(context, authorization, metadata.idempotencyKey, canonical.hash)
    }
}
