package com.duluin.ftth.fulfillment

import com.duluin.ftth.onboarding.MigrationImportApproved
import com.duluin.ftth.workorder.FulfillmentApproved
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Service
class FulfillmentCoordinator(private val checkpoints: FulfillmentCheckpointRepository, private val effects: FulfillmentEffectExecutor,
    private val now: () -> Instant = { Instant.now() }, transactionManager: PlatformTransactionManager? = null) {
    private val transaction = transactionManager?.let { TransactionTemplate(it).apply {
        timeout = 30
        propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
    } }

    @Transactional
    fun accept(request: FulfillmentRequest): FulfillmentOutcome {
        effects.lock(request)
        val existing = checkpoints.claimOrCreate(request)
        require(existing.canonicalHash == request.canonicalHash) { "FULFILLMENT_OPERATION_HASH_CONFLICT" }
        if (existing.state == FulfillmentState.APPLIED || existing.state == FulfillmentState.FAILED_PERMANENT)
            return FulfillmentOutcome(existing.state, true, existing.outcome)
        if (existing.state == FulfillmentState.READY) {
            val dispatched = checkpoints.save(existing.copy(state = FulfillmentState.DISPATCHED, updatedAt = now()))
            checkpoints.enqueueOutbox(dispatched)
        }
        return FulfillmentOutcome(FulfillmentState.DISPATCHED, existing.state != FulfillmentState.READY, "QUEUED")
    }

    fun process(request: FulfillmentRequest): FulfillmentOutcome = try {
        inTransaction { processLocked(request) }
    } catch (failure: RuntimeException) {
        inTransaction {
            val checkpoint = checkpoints.claim(request.tenantId, request.namespace, request.operationKey) ?: throw failure
            require(checkpoint.canonicalHash == request.canonicalHash) { "FULFILLMENT_OPERATION_HASH_CONFLICT" }
            val state = when (failure) {
                is FulfillmentExecutionFailure.Retryable -> FulfillmentState.FAILED_RETRYABLE
                is FulfillmentExecutionFailure.Permanent -> FulfillmentState.FAILED_PERMANENT
                else -> FulfillmentState.REQUIRES_RECONCILIATION
            }
            val message = if (failure is com.duluin.ftth.inventory.WarehouseContractException) failure.error.code.name
                else failure.message?.take(1000) ?: "FULFILLMENT_OWNER_REJECTED"
            if (checkpoint.state == FulfillmentState.APPLIED) throw failure
            checkpoints.save(checkpoint.copy(state = state, outcome = message, updatedAt = now()))
            FulfillmentOutcome(state, false, message)
        }
    }

    private fun processLocked(request: FulfillmentRequest): FulfillmentOutcome {
        effects.lock(request)
        var checkpoint = checkpoints.claim(request.tenantId, request.namespace, request.operationKey)
            ?: throw IllegalArgumentException("FULFILLMENT_HANDOFF_NOT_FOUND")
        require(checkpoint.canonicalHash == request.canonicalHash) { "FULFILLMENT_OPERATION_HASH_CONFLICT" }
        if (checkpoint.state == FulfillmentState.APPLIED || checkpoint.state == FulfillmentState.FAILED_PERMANENT)
            return FulfillmentOutcome(checkpoint.state, true, checkpoint.outcome)
        val completed = checkpoints.completedEffects(request.tenantId, request.namespace, request.operationKey)
        effects.preflight(request)
        checkpoint = checkpoints.save(checkpoint.copy(state = FulfillmentState.APPLYING, attempts = checkpoint.attempts + 1, updatedAt = now()))
        if (request.requiredEffects.isEmpty()) effects.apply(request)
        FulfillmentEffectType.entries.filter { it in request.requiredEffects && it !in completed }.forEach { effect ->
            checkpoints.markEffectStarted(request.tenantId, request.namespace, request.operationKey, effect, now())
            effects.apply(request, effect)
            checkpoints.markEffectCompleted(request.tenantId, request.namespace, request.operationKey, effect, now())
            checkpoint = checkpoints.save(checkpoint.copy(lastEffect = effect, updatedAt = now()))
        }
        val saved = checkpoints.save(checkpoint.copy(state = FulfillmentState.APPLIED, outcome = "APPLIED", updatedAt = now()))
        checkpoints.markOutboxConsumed(saved)
        return FulfillmentOutcome(saved.state, false, saved.outcome)
    }

    fun manualResolve(request: FulfillmentRequest, outcome: String): FulfillmentOutcome = inTransaction {
        val existing = checkpoints.claim(request.tenantId, request.namespace, request.operationKey)
            ?: throw IllegalArgumentException("FULFILLMENT_CHECKPOINT_NOT_FOUND")
        require(existing.canonicalHash == request.canonicalHash) { "FULFILLMENT_OPERATION_HASH_CONFLICT" }
        require(existing.state == FulfillmentState.REQUIRES_RECONCILIATION) { "FULFILLMENT_MANUAL_RESOLUTION_NOT_REQUIRED" }
        val saved = checkpoints.save(existing.copy(state = FulfillmentState.MANUAL_RESOLVED, outcome = outcome, updatedAt = now()))
        FulfillmentOutcome(saved.state, false, saved.outcome)
    }

    private fun inTransaction(action: () -> FulfillmentOutcome): FulfillmentOutcome =
        transaction?.let { requireNotNull(it.execute { action() }) } ?: action()

    companion object {
        fun forWorkOrder(event: FulfillmentApproved): FulfillmentRequest {
            val namespace = "workorder.fulfillment.approve"
            val key = "${event.workOrderId}:${event.proofOfWorkHash}"
            val hash = MessageDigest.getInstance("SHA-256").digest("${event.tenantId}:$namespace:$key".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return FulfillmentRequest(event.tenantId, namespace, key, hash, FulfillmentSource.WORK_ORDER,
                event.workOrderId, event.subscriptionId, event.workOrderId, event.workOrderType, true,
                event.applicableEffects.mapNotNullTo(linkedSetOf()) { name -> FulfillmentEffectType.entries.singleOrNull { it.name == name } },
                event.orderId, event.approvalActorId)
        }

        fun forMigration(event: MigrationImportApproved) = FulfillmentRequest(event.tenantId, "onboarding.import.migration.approve",
            event.operationKey, event.canonicalHash, FulfillmentSource.MIGRATION, event.subscriptionId, event.subscriptionId,
            null, "MIGRATION", true, setOf(FulfillmentEffectType.SUBSCRIPTION, FulfillmentEffectType.PROVISIONING))
    }
}
