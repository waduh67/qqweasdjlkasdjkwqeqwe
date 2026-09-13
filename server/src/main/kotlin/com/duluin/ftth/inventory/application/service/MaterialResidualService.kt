package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialLifecycleStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialResidualStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialUsageStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseOperationStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialHandoverStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.ResidualStockSource
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = [Exception::class])
class MaterialResidualService(
    private val authority: CurrentAuthorityApi, private val store: MaterialResidualStore,
    private val lifecycle: MaterialLifecycleStore, private val usage: MaterialUsageStore,
    private val operations: WarehouseOperationStore, private val posting: WarehousePosting,
    private val cutovers: InventoryTenantCutoverApi, private val scopes: InventoryWarehouseScopeApi,
    private val locations: WarehouseReceiptService,
    private val handovers: MaterialHandoverStore,
) {
    private val mapper = jacksonObjectMapper()

    fun dispatch(context: MaterialPlanningContext, request: MaterialResidualRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        val cutover = cutovers.lockForCommand(context.cutover.snapshot.epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        context.authority.assertHeld()
        receiptPermission(current, "workorder.order.field")
        receiptKey(metadata.idempotencyKey)
        if (!request.quantityBase.matches(Regex("[1-9][0-9]{0,18}")) || request.quantityBase.toLongOrNull() == null ||
            request.reason.isBlank() || request.reason.length > 1000 || request.evidenceReference.isBlank() || request.evidenceReference.length > 500)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        lifecycle.lock(context.workOrderId)
        usage.lockSources(listOf(request.receiptId))
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        val prior = operations.lockKey("warehouse.material.residual.dispatch", metadata.idempotencyKey)
        if (prior != null) {
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.resourceId != context.workOrderId || prior.hash != canonical.hash) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            val snapshot = store.get(prior.receipt.documentId)
            locations.authorizeLocation(snapshot.source.locationId, current, scopes.currentUnderFence(current.fence))
            return prior.receipt
        }
        if (request.workOrderRevision != context.workOrderRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val target = store.target(request.targetLocationId)
        val authorization = request.authorizationId?.let { handovers.get(it) }
        val purpose = if (authorization == null) ResidualPurpose.RETURN else ResidualPurpose.HANDOVER
        when (purpose) {
            ResidualPurpose.RETURN -> if (target.first != "QUARANTINE") masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            ResidualPurpose.HANDOVER -> {
                val grant = requireNotNull(authorization)
                if (grant.workOrderId != context.workOrderId || grant.request != request.copy(authorizationId = null) ||
                    grant.senderId != current.fence.identity.userId || target.second != grant.receiverId || target.first != "TECHNICIAN" ||
                    grant.receiverId !in context.activeAssigneeIds) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            }
        }
        val source = store.source(context, ResidualStockSource(request.receiptId, request.issueLineId, request.usageId, request.stockIdentityId, request.baseUnit))
        locations.authorizeLocation(source.dimension.locationId, current, scopes.currentUnderFence(current.fence))
        val amount = request.quantityBase.toLong()
        if (amount > source.quantity) masterFailure(WarehouseErrorCode.INSUFFICIENT_STOCK)
        val split = request.baseUnit == WarehouseBaseUnit.MM && amount < source.quantity
        val transit = source.dimension.copy(stockIdentityId = if (split) UUID.randomUUID() else source.dimension.stockIdentityId,
            locationId = request.targetLocationId, condition = WarehouseCondition.QUARANTINE)
        val remainder = if (amount == source.quantity) null else source.dimension.copy(stockIdentityId = if (split) UUID.randomUUID() else source.dimension.stockIdentityId)
        val id = UUID.randomUUID()
        val operationId = UUID.randomUUID()
        val time = lifecycle.now()
        val snapshot = MaterialResidualSnapshot(id, context.workOrderId, UUID.randomUUID(), request, purpose,
            source.dimension, transit, remainder, source.revision, source.quantity, authorization?.receiverId, operationId,
            UUID.nameUUIDFromBytes("warehouse:$operationId".toByteArray(Charsets.UTF_8)), time)
        val body = mapper.writeValueAsString(snapshot)
        val operation = PostingOperation(operationId, "warehouse.material.residual.dispatch", metadata.idempotencyKey, current.fence.identity.userId,
            context.workOrderId, "workorder:${context.workOrderId}", canonical.hash, "RESIDUAL_DISPATCH", 200, body, current.fence.epoch, time)
        store.create(context, snapshot)
        val unit = StockUnit.valueOf(request.baseUnit.name)
        val legs = buildList {
            add(PostingLeg(LegDirection.OUT, source.dimension, StockQuantity.of(if (split) source.quantity else amount, unit), snapshot.lineId, InventoryStatus.ISSUED))
            add(PostingLeg(LegDirection.IN, transit, StockQuantity.of(amount, unit), snapshot.lineId, InventoryStatus.IN_TRANSIT))
            if (split) add(PostingLeg(LegDirection.IN, requireNotNull(remainder), StockQuantity.of(source.quantity - amount, unit), snapshot.lineId, InventoryStatus.ISSUED))
        }
        val splits = if (split) listOf(PostingSplit(source.dimension.stockIdentityId, source.revision, listOf(
            SegmentChild(transit.stockIdentityId, StockQuantity.of(amount, unit), SegmentKind.CUT),
            SegmentChild(requireNotNull(remainder).stockIdentityId, StockQuantity.of(source.quantity - amount, unit), SegmentKind.REMNANT)))) else emptyList()
        val movementKind = when (purpose) { ResidualPurpose.RETURN -> MovementKind.RETURN; ResidualPurpose.HANDOVER -> MovementKind.TRANSFER }
        posting.post(WarehousePost(id, 0, "DISPATCHED", operation, movementKind, request.reason, legs, splits = splits), cutover)
        operations.storeIdentity(operationId, canonical.json, current.fence.identity.sessionId)
        return WarehouseOperationReceipt(operationId, id, 1, 200, body, time)
    }

    fun acknowledge(context: MaterialPlanningContext, request: MaterialResidualAcknowledgement, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        val cutover = cutovers.lockForCommand(context.cutover.snapshot.epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptKey(metadata.idempotencyKey)
        if (request.evidenceReference.isBlank() || request.evidenceReference.length > 500) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        lifecycle.lock(context.workOrderId)
        val source = store.get(request.documentId)
        when (source.purpose) {
            ResidualPurpose.RETURN -> receiptPermission(current, "inventory.return.manage")
            ResidualPurpose.HANDOVER -> {
                receiptPermission(current, "workorder.order.field")
                if (source.receiverId != current.fence.identity.userId || source.receiverId !in context.activeAssigneeIds)
                    masterFailure(WarehouseErrorCode.WRONG_CUSTODIAN)
            }
        }
        if (source.workOrderId != context.workOrderId) masterFailure(WarehouseErrorCode.NOT_FOUND)
        if (source.source.custodianId == current.fence.identity.userId) masterFailure(WarehouseErrorCode.INDEPENDENT_APPROVER_REQUIRED)
        locations.authorizeLocation(source.request.targetLocationId, current, scopes.currentUnderFence(current.fence))
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(request))
        val prior = operations.lockKey("warehouse.material.residual.acknowledge", metadata.idempotencyKey)
        if (prior != null) {
            if (prior.actorId != current.fence.identity.userId) masterFailure(WarehouseErrorCode.FORBIDDEN)
            if (prior.hash != canonical.hash || prior.resourceId != context.workOrderId) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
            if (prior.cutoverEpoch != cutover.snapshot.epoch) masterFailure(WarehouseErrorCode.STALE_CUTOVER)
            return prior.receipt
        }
        if (request.expectedRevision != 1L || store.acknowledged(source.id)) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val id = UUID.randomUUID()
        val time = lifecycle.now()
        val state = when (source.purpose) { ResidualPurpose.RETURN -> "RECEIVED_IN_INSPECTION"; ResidualPurpose.HANDOVER -> "RECEIVED" }
        val body = mapper.writeValueAsString(mapOf("documentId" to source.id, "revision" to 2, "state" to state, "quantityBase" to source.request.quantityBase))
        val operation = PostingOperation(id, "warehouse.material.residual.acknowledge", metadata.idempotencyKey, current.fence.identity.userId,
            context.workOrderId, "workorder:${context.workOrderId}", canonical.hash, "RESIDUAL_ACKNOWLEDGE", 200, body, current.fence.epoch, time)
        val quantity = StockQuantity.of(source.request.quantityBase.toLong(), StockUnit.valueOf(source.request.baseUnit.name))
        val destination = when (source.purpose) {
            ResidualPurpose.RETURN -> source.transit.copy(custodianKind = OwnerKind.WAREHOUSE, custodianId = source.request.targetLocationId)
            ResidualPurpose.HANDOVER -> source.transit.copy(custodianKind = OwnerKind.TECHNICIAN, custodianId = requireNotNull(source.receiverId), condition = WarehouseCondition.SERVICEABLE)
        }
        val status = when (source.purpose) { ResidualPurpose.RETURN -> InventoryStatus.QUARANTINE; ResidualPurpose.HANDOVER -> InventoryStatus.ISSUED }
        val movementKind = when (source.purpose) { ResidualPurpose.RETURN -> MovementKind.RETURN; ResidualPurpose.HANDOVER -> MovementKind.TRANSFER }
        posting.post(WarehousePost(source.id, 1, state, operation, movementKind, "Acknowledged residual custody", listOf(
            PostingLeg(LegDirection.OUT, source.transit, quantity, source.lineId, InventoryStatus.IN_TRANSIT),
            PostingLeg(LegDirection.IN, destination, quantity, source.lineId, status))), cutover)
        val receipt = WarehouseOperationReceipt(id, source.id, 2, 200, body, time)
        store.acknowledge(source, receipt, request.evidenceReference, current.fence.identity.userId)
        operations.storeIdentity(id, canonical.json, current.fence.identity.sessionId)
        return receipt
    }
}
