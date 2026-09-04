package com.duluin.ftth.fulfillment

import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.bng.BngProvisioningApi
import com.duluin.ftth.fieldservice.FieldServiceApi
import com.duluin.ftth.fieldservice.VisitFulfillmentCommand
import com.duluin.ftth.inventory.InventoryApi
import com.duluin.ftth.inventory.InventoryFulfillmentCommand
import com.duluin.ftth.order.OrderApi
import com.duluin.ftth.order.OrderFulfillmentCommand
import com.duluin.ftth.order.OrderTransition
import com.duluin.ftth.workorder.FulfillmentApproved
import com.duluin.ftth.workorder.WorkOrderFulfillmentApi
import com.duluin.ftth.workorder.WorkOrderFulfillmentCommand
import com.duluin.ftth.onboarding.MigrationImportApproved
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class FulfillmentSource { WORK_ORDER, MIGRATION }

enum class FulfillmentState {
    READY,
    DISPATCHED,
    APPLYING,
    APPLIED,
    FAILED_RETRYABLE,
    REQUIRES_RECONCILIATION,
    MANUAL_RESOLVED,
    FAILED_PERMANENT,
}

enum class FulfillmentEffectType { SUBSCRIPTION, PROVISIONING, INVENTORY, ORDER, WORK_ORDER, VISIT }

data class FulfillmentRequest(
    val tenantId: UUID,
    val namespace: String,
    val operationKey: String,
    val canonicalHash: String,
    val source: FulfillmentSource,
    val targetId: UUID,
    val subscriptionId: UUID?,
    val workOrderId: UUID?,
    val workOrderKind: String?,
    val approved: Boolean,
    val requiredEffects: Set<FulfillmentEffectType> = emptySet(),
    val orderId: UUID? = null,
    val approvalActorId: UUID? = null,
) {
    init {
        require(namespace.isNotBlank() && operationKey.isNotBlank() && canonicalHash.matches(Regex("[0-9a-f]{64}")))
        require(approved) { "FULFILLMENT_APPROVAL_REQUIRED" }
    }
}

data class FulfillmentCheckpoint(
    val tenantId: UUID,
    val namespace: String,
    val operationKey: String,
    val canonicalHash: String,
    val source: FulfillmentSource,
    val targetId: UUID,
    val state: FulfillmentState,
    val lastEffect: FulfillmentEffectType?,
    val attempts: Int,
    val outcome: String?,
    val updatedAt: Instant,
    val subscriptionId: UUID? = null,
    val workOrderId: UUID? = null,
    val workOrderKind: String? = null,
    val requiredEffects: Set<FulfillmentEffectType> = emptySet(),
    val orderId: UUID? = null,
    val approvalActorId: UUID? = null,
)

data class FulfillmentOutcome(
    val state: FulfillmentState,
    val replayed: Boolean,
    val outcome: String?,
)

data class FulfillmentOutboxRecord(
    val id: UUID,
    val tenantId: UUID,
    val payloadHash: String,
    val payload: String,
    val claimedBy: String,
    val leaseUntil: Instant,
)

interface FulfillmentOutboxRepository {
    fun claimPending(tenantId: UUID, workerId: String, now: Instant, leaseUntil: Instant): FulfillmentOutboxRecord?
    fun markOutboxConsumed(id: UUID, workerId: String)
}

interface FulfillmentCheckpointRepository {
    fun find(tenantId: UUID, namespace: String, operationKey: String): FulfillmentCheckpoint?
    fun claim(tenantId: UUID, namespace: String, operationKey: String): FulfillmentCheckpoint? = find(tenantId, namespace, operationKey)
    fun claimOrCreate(request: FulfillmentRequest): FulfillmentCheckpoint = claim(request.tenantId, request.namespace, request.operationKey)
        ?: save(FulfillmentCheckpoint(request.tenantId, request.namespace, request.operationKey, request.canonicalHash, request.source, request.targetId, FulfillmentState.READY, null, 0, null, Instant.now(), request.subscriptionId, request.workOrderId, request.workOrderKind, request.requiredEffects, request.orderId, request.approvalActorId))
    fun save(checkpoint: FulfillmentCheckpoint): FulfillmentCheckpoint
    fun enqueueOutbox(checkpoint: FulfillmentCheckpoint) = Unit
    fun markOutboxConsumed(checkpoint: FulfillmentCheckpoint) = Unit
    fun completedEffects(tenantId: UUID, namespace: String, operationKey: String): Set<FulfillmentEffectType> = emptySet()
    fun markEffectStarted(tenantId: UUID, namespace: String, operationKey: String, effect: FulfillmentEffectType, at: Instant) = Unit
    fun markEffectCompleted(tenantId: UUID, namespace: String, operationKey: String, effect: FulfillmentEffectType, at: Instant) = Unit
}

interface FulfillmentEffectExecutor {
    fun apply(request: FulfillmentRequest)
    fun preflight(request: FulfillmentRequest) = Unit
    fun apply(request: FulfillmentRequest, effect: FulfillmentEffectType) = apply(request)
}

sealed class FulfillmentExecutionFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Retryable(message: String, cause: Throwable? = null) : FulfillmentExecutionFailure(message, cause)
    class ReconciliationRequired(message: String, cause: Throwable? = null) : FulfillmentExecutionFailure(message, cause)
    class Permanent(message: String, cause: Throwable? = null) : FulfillmentExecutionFailure(message, cause)
}

@Component
class PublicApiFulfillmentEffectExecutor(
    private val customer: CustomerApi,
    private val bng: BngProvisioningApi,
    private val inventory: InventoryApi,
    private val orders: OrderApi,
    private val workOrders: WorkOrderFulfillmentApi,
    private val fieldService: FieldServiceApi,
) : FulfillmentEffectExecutor {
    override fun preflight(request: FulfillmentRequest) {
        request.requiredEffects.forEach { effect ->
            when (effect) {
                FulfillmentEffectType.SUBSCRIPTION, FulfillmentEffectType.PROVISIONING -> {
                    val id = request.subscriptionId ?: throw FulfillmentExecutionFailure.ReconciliationRequired("SUBSCRIPTION_LINK_NOT_FOUND")
                    if (customer.findSubscription(id) == null) throw FulfillmentExecutionFailure.ReconciliationRequired("SUBSCRIPTION_NOT_FOUND")
                }
                FulfillmentEffectType.INVENTORY -> {
                    val workOrderId = request.workOrderId ?: throw FulfillmentExecutionFailure.ReconciliationRequired("INVENTORY_WORK_ORDER_NOT_FOUND")
                    if (inventory.fulfillmentAllocations(workOrderId).isEmpty()) throw FulfillmentExecutionFailure.ReconciliationRequired("INVENTORY_ALLOCATIONS_NOT_FOUND")
                }
                FulfillmentEffectType.ORDER -> {
                    val orderId = request.orderId ?: throw FulfillmentExecutionFailure.ReconciliationRequired("ORDER_LINK_NOT_FOUND")
                    if (orders.fulfillmentRevision(orderId) == null) throw FulfillmentExecutionFailure.ReconciliationRequired("ORDER_NOT_FOUND")
                }
                FulfillmentEffectType.WORK_ORDER -> {
                    val workOrderId = request.workOrderId ?: throw FulfillmentExecutionFailure.ReconciliationRequired("WORK_ORDER_LINK_NOT_FOUND")
                    workOrders.validateFulfillment(
                        WorkOrderFulfillmentCommand(request.tenantId, workOrderId, request.namespace, request.operationKey, request.canonicalHash, request.source.name, "PREFLIGHT"),
                    )
                }
                FulfillmentEffectType.VISIT -> {
                    val workOrderId = request.workOrderId ?: throw FulfillmentExecutionFailure.ReconciliationRequired("VISIT_WORK_ORDER_NOT_FOUND")
                    if (fieldService.visitsByWorkOrder(workOrderId).size != 1) throw FulfillmentExecutionFailure.ReconciliationRequired("VISIT_LINK_NOT_UNIQUE")
                }
            }
        }
        if (request.source == FulfillmentSource.WORK_ORDER && request.approvalActorId == null) {
            throw FulfillmentExecutionFailure.ReconciliationRequired("APPROVAL_ACTOR_NOT_FOUND")
        }
    }

    override fun apply(request: FulfillmentRequest) {
        request.requiredEffects.ifEmpty { setOf(FulfillmentEffectType.SUBSCRIPTION, FulfillmentEffectType.PROVISIONING) }
            .forEach { apply(request, it) }
    }

    override fun apply(request: FulfillmentRequest, effect: FulfillmentEffectType) {
        when (effect) {
            FulfillmentEffectType.SUBSCRIPTION -> applySubscription(request)
            FulfillmentEffectType.PROVISIONING -> confirmProvisioning(request)
            FulfillmentEffectType.INVENTORY -> applyInventory(request)
            FulfillmentEffectType.ORDER -> applyOrder(request)
            FulfillmentEffectType.WORK_ORDER -> applyWorkOrder(request)
            FulfillmentEffectType.VISIT -> applyVisit(request)
        }
    }

    private fun applySubscription(request: FulfillmentRequest) {
        val subscriptionId = request.subscriptionId ?: return
        when (request.workOrderKind) {
            "DISMANTLE" -> customer.terminateForDismantle(subscriptionId)
            else -> customer.activateForInstallation(subscriptionId)
        }
    }

    private fun confirmProvisioning(request: FulfillmentRequest) {
        val subscriptionId = request.subscriptionId ?: return
        val access = bng.findAccess(subscriptionId)
        if (request.workOrderKind == "DISMANTLE") {
            if (access?.accountStatus != "TERMINATED") throw FulfillmentExecutionFailure.ReconciliationRequired("BNG_TERMINATION_NOT_CONFIRMED")
        } else if (access?.accountStatus != "ACTIVE") {
            throw FulfillmentExecutionFailure.ReconciliationRequired("BNG_ACTIVATION_NOT_CONFIRMED")
        }
    }

    private fun applyInventory(request: FulfillmentRequest) {
        val workOrderId = request.workOrderId ?: throw FulfillmentExecutionFailure.ReconciliationRequired("INVENTORY_WORK_ORDER_NOT_FOUND")
        val allocations = inventory.fulfillmentAllocations(workOrderId)
        if (allocations.isEmpty()) throw FulfillmentExecutionFailure.ReconciliationRequired("INVENTORY_ALLOCATIONS_NOT_FOUND")
        try {
            allocations.forEach { allocation ->
                val result = inventory.consumeFulfillment(InventoryFulfillmentCommand(
                    tenantId = request.tenantId,
                    targetId = allocation.targetId,
                    itemId = allocation.itemId,
                    skuId = allocation.skuId,
                    locationId = allocation.locationId,
                    customerId = allocation.customerId,
                    workOrderId = workOrderId,
                    quantity = allocation.quantity,
                    serialized = allocation.serialized,
                    installed = request.workOrderKind != "DISMANTLE",
                    actorId = allocation.actorId,
                    namespace = request.namespace,
                    operationKey = "${request.operationKey}:${allocation.targetId}",
                    payloadHash = request.canonicalHash,
                    reason = "FULFILLMENT_${request.workOrderKind ?: request.source.name}",
                    itemCategory = allocation.itemCategory,
                ))
                if (!result.applied) throw FulfillmentExecutionFailure.Retryable("INVENTORY_EFFECT_NOT_APPLIED")
            }
        } catch (failure: FulfillmentExecutionFailure) {
            throw failure
        } catch (failure: RuntimeException) {
            throw FulfillmentExecutionFailure.ReconciliationRequired("INVENTORY_EFFECT_REJECTED", failure)
        }
    }

    private fun applyOrder(request: FulfillmentRequest) {
        try {
            val orderId = request.orderId ?: throw FulfillmentExecutionFailure.ReconciliationRequired("ORDER_LINK_NOT_FOUND")
            val revision = orders.fulfillmentRevision(orderId)
                ?: throw FulfillmentExecutionFailure.ReconciliationRequired("ORDER_NOT_FOUND")
            orders.applyFulfillment(
                OrderFulfillmentCommand(
                    tenantId = request.tenantId,
                    orderId = orderId,
                    transition = OrderTransition.FULFILL,
                    expectedRevision = revision,
                    namespace = request.namespace,
                    operationKey = request.operationKey,
                    payloadHash = request.canonicalHash,
                ),
            )
        } catch (failure: FulfillmentExecutionFailure) {
            throw failure
        } catch (failure: RuntimeException) {
            throw FulfillmentExecutionFailure.ReconciliationRequired("ORDER_EFFECT_REJECTED", failure)
        }
    }

    private fun applyWorkOrder(request: FulfillmentRequest) {
        try {
            workOrders.recordFulfillmentResult(
                WorkOrderFulfillmentCommand(
                    tenantId = request.tenantId,
                    workOrderId = request.workOrderId ?: request.targetId,
                    namespace = request.namespace,
                    operationKey = request.operationKey,
                    payloadHash = request.canonicalHash,
                    source = request.source.name,
                    result = "APPLIED",
                ),
            )
        } catch (failure: FulfillmentExecutionFailure) {
            throw failure
        } catch (failure: RuntimeException) {
            throw FulfillmentExecutionFailure.ReconciliationRequired("WORK_ORDER_EFFECT_REJECTED", failure)
        }
    }

    private fun applyVisit(request: FulfillmentRequest) {
        val visit = fieldService.visitsByWorkOrder(request.workOrderId ?: request.targetId).singleOrNull()
            ?: throw FulfillmentExecutionFailure.ReconciliationRequired("VISIT_LINK_NOT_UNIQUE")
        val visitId = visit.id
        try {
            fieldService.applyFulfillment(
                VisitFulfillmentCommand(
                    tenantId = request.tenantId,
                    visitId = visitId,
                    actorId = visit.technicianId,
                    expectedRevision = visit.revision,
                    namespace = request.namespace,
                    operationKey = request.operationKey,
                    payloadHash = request.canonicalHash,
                    receivedAt = nowInstant(),
                ),
            )
        } catch (failure: FulfillmentExecutionFailure) {
            throw failure
        } catch (failure: RuntimeException) {
            throw FulfillmentExecutionFailure.ReconciliationRequired("VISIT_EFFECT_REJECTED", failure)
        }
    }

    private fun nowInstant() = Instant.now()
}

@Service
class FulfillmentCoordinator(
    private val checkpoints: FulfillmentCheckpointRepository,
    private val effects: FulfillmentEffectExecutor,
    private val now: () -> Instant = { Instant.now() },
) {
    @Transactional
    fun accept(request: FulfillmentRequest): FulfillmentOutcome {
        val existing = checkpoints.claimOrCreate(request)
        require(existing.canonicalHash == request.canonicalHash) { "FULFILLMENT_OPERATION_HASH_CONFLICT" }
        if (existing.state == FulfillmentState.APPLIED || existing.state == FulfillmentState.FAILED_PERMANENT) {
            return FulfillmentOutcome(existing.state, replayed = true, existing.outcome)
        }
        if (existing.state == FulfillmentState.READY) {
            val dispatched = checkpoints.save(existing.copy(state = FulfillmentState.DISPATCHED, updatedAt = now()))
            checkpoints.enqueueOutbox(dispatched)
        }
        return FulfillmentOutcome(FulfillmentState.DISPATCHED, replayed = existing.state != FulfillmentState.READY, "QUEUED")
    }

    @Transactional
    fun process(request: FulfillmentRequest): FulfillmentOutcome {
        val existing = checkpoints.claim(request.tenantId, request.namespace, request.operationKey)
            ?: throw IllegalArgumentException("FULFILLMENT_HANDOFF_NOT_FOUND")
        require(existing.canonicalHash == request.canonicalHash) { "FULFILLMENT_OPERATION_HASH_CONFLICT" }
        if (existing.state == FulfillmentState.APPLIED || existing.state == FulfillmentState.FAILED_PERMANENT) {
            return FulfillmentOutcome(existing.state, replayed = true, existing.outcome)
        }
        var checkpoint = existing
        val completed = checkpoints.completedEffects(request.tenantId, request.namespace, request.operationKey)
        val effectsToApply: List<FulfillmentEffectType?> = if (request.requiredEffects.isEmpty()) {
            listOf(null)
        } else {
            FulfillmentEffectType.entries.filter { it in request.requiredEffects }
        }
        return try {
            effects.preflight(request)
            checkpoint = checkpoints.save(checkpoint.copy(state = FulfillmentState.APPLYING, attempts = checkpoint.attempts + 1, updatedAt = now()))
            effectsToApply.filterNot { it != null && it in completed }.forEach { effect ->
                effect?.let {
                    checkpoints.markEffectStarted(request.tenantId, request.namespace, request.operationKey, it, now())
                    effects.apply(request, it)
                    checkpoints.markEffectCompleted(request.tenantId, request.namespace, request.operationKey, it, now())
                    checkpoint = checkpoints.save(checkpoint.copy(lastEffect = it, updatedAt = now()))
                } ?: effects.apply(request)
            }
            checkpoints.save(checkpoint.copy(state = FulfillmentState.APPLIED, outcome = "APPLIED", updatedAt = now()))
                .also(checkpoints::markOutboxConsumed)
                .let { FulfillmentOutcome(it.state, replayed = false, it.outcome) }
        } catch (failure: FulfillmentExecutionFailure.Retryable) {
            val saved = checkpoints.save(checkpoint.copy(state = FulfillmentState.FAILED_RETRYABLE, outcome = failure.message, updatedAt = now()))
            FulfillmentOutcome(saved.state, replayed = false, saved.outcome)
        } catch (failure: FulfillmentExecutionFailure.ReconciliationRequired) {
            val saved = checkpoints.save(checkpoint.copy(state = FulfillmentState.REQUIRES_RECONCILIATION, outcome = failure.message, updatedAt = now()))
            FulfillmentOutcome(saved.state, replayed = false, saved.outcome)
        } catch (failure: FulfillmentExecutionFailure.Permanent) {
            val saved = checkpoints.save(checkpoint.copy(state = FulfillmentState.FAILED_PERMANENT, outcome = failure.message, updatedAt = now()))
            FulfillmentOutcome(saved.state, replayed = false, saved.outcome)
        } catch (failure: RuntimeException) {
            val saved = checkpoints.save(checkpoint.copy(state = FulfillmentState.REQUIRES_RECONCILIATION, outcome = failure.message ?: "FULFILLMENT_OWNER_REJECTED", updatedAt = now()))
            FulfillmentOutcome(saved.state, replayed = false, saved.outcome)
        }
    }

    fun manualResolve(request: FulfillmentRequest, outcome: String): FulfillmentOutcome {
        val existing = checkpoints.find(request.tenantId, request.namespace, request.operationKey)
            ?: throw IllegalArgumentException("FULFILLMENT_CHECKPOINT_NOT_FOUND")
        require(existing.canonicalHash == request.canonicalHash) { "FULFILLMENT_OPERATION_HASH_CONFLICT" }
        require(existing.state == FulfillmentState.REQUIRES_RECONCILIATION) { "FULFILLMENT_MANUAL_RESOLUTION_NOT_REQUIRED" }
        val saved = checkpoints.save(existing.copy(state = FulfillmentState.MANUAL_RESOLVED, outcome = outcome, updatedAt = now()))
        return FulfillmentOutcome(saved.state, replayed = false, saved.outcome)
    }

    companion object {
        fun forWorkOrder(event: FulfillmentApproved): FulfillmentRequest = request(
            tenantId = event.tenantId,
            namespace = "workorder.fulfillment.approve",
            operationKey = "${event.workOrderId}:${event.proofOfWorkHash}",
            source = FulfillmentSource.WORK_ORDER,
            targetId = event.workOrderId,
            subscriptionId = event.subscriptionId,
            workOrderId = event.workOrderId,
            workOrderKind = event.workOrderType,
            requiredEffects = event.applicableEffects.takeIf { it.isNotEmpty() }
                ?.mapTo(linkedSetOf(), FulfillmentEffectType::valueOf)
                ?: setOf(FulfillmentEffectType.SUBSCRIPTION, FulfillmentEffectType.PROVISIONING, FulfillmentEffectType.WORK_ORDER),
            orderId = event.orderId,
            approvalActorId = event.approvalActorId,
        )

        fun forMigration(event: MigrationImportApproved): FulfillmentRequest = request(
            tenantId = event.tenantId,
            namespace = "onboarding.import.migration.approve",
            operationKey = event.operationKey,
            source = FulfillmentSource.MIGRATION,
            targetId = event.subscriptionId,
            subscriptionId = event.subscriptionId,
            workOrderId = null,
            workOrderKind = "MIGRATION",
            requiredEffects = setOf(FulfillmentEffectType.SUBSCRIPTION, FulfillmentEffectType.PROVISIONING),
            suppliedHash = event.canonicalHash,
        )

        private fun request(
            tenantId: UUID, namespace: String, operationKey: String, source: FulfillmentSource,
            targetId: UUID, subscriptionId: UUID?, workOrderId: UUID?, workOrderKind: String?, suppliedHash: String? = null,
            requiredEffects: Set<FulfillmentEffectType> = emptySet(), orderId: UUID? = null, approvalActorId: UUID? = null,
        ): FulfillmentRequest {
            val canonical = listOf(tenantId, namespace, operationKey, source, targetId, subscriptionId, workOrderId, workOrderKind, orderId, approvalActorId).joinToString("\u001f")
            val hash = suppliedHash ?: MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return FulfillmentRequest(tenantId, namespace, operationKey, hash, source, targetId, subscriptionId, workOrderId, workOrderKind, true, requiredEffects, orderId, approvalActorId)
        }

        private fun effectsForWorkOrder(kind: String): Set<FulfillmentEffectType> = when (kind) {
            "PSB", "DISMANTLE" -> FulfillmentEffectType.entries.toSet()
            "REPAIR", "MIGRATION", "PREVENTIVE" -> setOf(
                FulfillmentEffectType.INVENTORY,
                FulfillmentEffectType.WORK_ORDER,
                FulfillmentEffectType.VISIT,
            )
            else -> setOf(FulfillmentEffectType.WORK_ORDER)
        }
    }
}
