package com.duluin.ftth.fulfillment

import java.time.Instant
import java.util.UUID

enum class FulfillmentSource { WORK_ORDER, MIGRATION }
enum class FulfillmentState { READY, DISPATCHED, APPLYING, APPLIED, FAILED_RETRYABLE, REQUIRES_RECONCILIATION, MANUAL_RESOLVED, FAILED_PERMANENT }
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

data class FulfillmentOutcome(val state: FulfillmentState, val replayed: Boolean, val outcome: String?)
data class FulfillmentOutboxRecord(val id: UUID, val tenantId: UUID, val payloadHash: String, val payload: String,
    val claimedBy: String, val leaseUntil: Instant, val eventType: String = "FULFILLMENT_APPLY")

interface FulfillmentOutboxRepository {
    fun claimPending(tenantId: UUID, workerId: String, now: Instant, leaseUntil: Instant): FulfillmentOutboxRecord?
    fun markOutboxConsumed(id: UUID, workerId: String)
    fun reconcile(delivery: FulfillmentOutboxRecord, reason: String): FulfillmentOutcome
}

interface FulfillmentCheckpointRepository {
    fun find(tenantId: UUID, namespace: String, operationKey: String): FulfillmentCheckpoint?
    fun claim(tenantId: UUID, namespace: String, operationKey: String): FulfillmentCheckpoint? = find(tenantId, namespace, operationKey)
    fun claimOrCreate(request: FulfillmentRequest): FulfillmentCheckpoint = claim(request.tenantId, request.namespace, request.operationKey)
        ?: save(FulfillmentCheckpoint(request.tenantId, request.namespace, request.operationKey, request.canonicalHash, request.source,
            request.targetId, FulfillmentState.READY, null, 0, null, Instant.now(), request.subscriptionId, request.workOrderId,
            request.workOrderKind, request.requiredEffects, request.orderId, request.approvalActorId))
    fun save(checkpoint: FulfillmentCheckpoint): FulfillmentCheckpoint
    fun enqueueOutbox(checkpoint: FulfillmentCheckpoint) = Unit
    fun markOutboxConsumed(checkpoint: FulfillmentCheckpoint) = Unit
    fun completedEffects(tenantId: UUID, namespace: String, operationKey: String): Set<FulfillmentEffectType> = emptySet()
    fun markEffectStarted(tenantId: UUID, namespace: String, operationKey: String, effect: FulfillmentEffectType, at: Instant) = Unit
    fun markEffectCompleted(tenantId: UUID, namespace: String, operationKey: String, effect: FulfillmentEffectType, at: Instant) = Unit
}

interface FulfillmentEffectExecutor {
    fun apply(request: FulfillmentRequest)
    fun lock(request: FulfillmentRequest) = Unit
    fun preflight(request: FulfillmentRequest) = Unit
    fun apply(request: FulfillmentRequest, effect: FulfillmentEffectType) = apply(request)
}

sealed class FulfillmentExecutionFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Retryable(message: String, cause: Throwable? = null) : FulfillmentExecutionFailure(message, cause)
    class ReconciliationRequired(message: String, cause: Throwable? = null) : FulfillmentExecutionFailure(message, cause)
    class Permanent(message: String, cause: Throwable? = null) : FulfillmentExecutionFailure(message, cause)
}
