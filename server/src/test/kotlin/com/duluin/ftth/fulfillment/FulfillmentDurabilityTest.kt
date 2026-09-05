package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.tenant.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class FulfillmentDurabilityTest {
    private val tenant = UuidV7.generate()

    @AfterEach
    fun clearTenant() = TenantContext.clear()

    @Test
    fun `worker claims and reconstructs persisted delivery under its tenant`() {
        val request = FulfillmentRequest(
            tenantId = tenant,
            namespace = "workorder.fulfillment.approve",
            operationKey = "wo-1",
            canonicalHash = "a".repeat(64),
            source = FulfillmentSource.WORK_ORDER,
            targetId = UuidV7.generate(),
            subscriptionId = UuidV7.generate(),
            workOrderId = UuidV7.generate(),
            workOrderKind = "PSB",
            approved = true,
            requiredEffects = FulfillmentEffectType.entries.toSet(),
        )
        val repository = DurableFakeRepository(request)
        val observedTenants = mutableListOf<UUID>()
        val worker = FulfillmentOutboxWorker(
            coordinator = FulfillmentCoordinator(repository, object : FulfillmentEffectExecutor {
                override fun apply(request: FulfillmentRequest) = error("legacy path must not be used")
                override fun apply(request: FulfillmentRequest, effect: FulfillmentEffectType) {
                    observedTenants += TenantContext.tenantId()
                }
            }),
            outbox = repository,
        )

        val outcome = worker.processNext(tenant, "worker-1", Instant.parse("2026-09-04T00:00:00Z"))

        assertThat(outcome?.state).isEqualTo(FulfillmentState.APPLIED)
        assertThat(repository.claimedTenant).isEqualTo(tenant)
        assertThat(observedTenants).containsOnly(tenant)
        assertThat(repository.completed).containsExactlyInAnyOrderElementsOf(FulfillmentEffectType.entries.toSet())
    }

    @Test
    fun `restart resumes from durable effect progress without repeating completed effect`() {
        val request = FulfillmentRequest(
            tenantId = tenant,
            namespace = "workorder.fulfillment.approve",
            operationKey = "wo-2",
            canonicalHash = "b".repeat(64),
            source = FulfillmentSource.WORK_ORDER,
            targetId = UuidV7.generate(),
            subscriptionId = null,
            workOrderId = UuidV7.generate(),
            workOrderKind = "REPAIR",
            approved = true,
            requiredEffects = setOf(FulfillmentEffectType.INVENTORY, FulfillmentEffectType.ORDER),
        )
        val repository = DurableFakeRepository(request)
        repository.completed += FulfillmentEffectType.INVENTORY
        val calls = mutableListOf<FulfillmentEffectType>()
        val coordinator = FulfillmentCoordinator(repository, object : FulfillmentEffectExecutor {
            override fun apply(request: FulfillmentRequest) = error("legacy path must not be used")
            override fun apply(request: FulfillmentRequest, effect: FulfillmentEffectType) {
                calls += effect
            }
        })

        val outcome = coordinator.process(request)

        assertThat(outcome.state).isEqualTo(FulfillmentState.APPLIED)
        assertThat(calls).containsExactly(FulfillmentEffectType.ORDER)
    }

    @Test
    fun `preflight failure leaves owner effects and progress untouched`() {
        val request = FulfillmentRequest(
            tenantId = tenant,
            namespace = "workorder.fulfillment.approve",
            operationKey = "preflight",
            canonicalHash = "c".repeat(64),
            source = FulfillmentSource.WORK_ORDER,
            targetId = UuidV7.generate(),
            subscriptionId = UuidV7.generate(),
            workOrderId = UuidV7.generate(),
            workOrderKind = "PSB",
            approved = true,
            requiredEffects = setOf(FulfillmentEffectType.SUBSCRIPTION, FulfillmentEffectType.ORDER),
        )
        val repository = DurableFakeRepository(request)
        var effectsApplied = 0
        val coordinator = FulfillmentCoordinator(repository, object : FulfillmentEffectExecutor {
            override fun preflight(request: FulfillmentRequest) = throw FulfillmentExecutionFailure.ReconciliationRequired("ORDER_LINK_NOT_FOUND")
            override fun apply(request: FulfillmentRequest) { effectsApplied++ }
        })
        coordinator.accept(request)

        val outcome = coordinator.process(request)

        assertThat(outcome.state).isEqualTo(FulfillmentState.REQUIRES_RECONCILIATION)
        assertThat(effectsApplied).isZero()
        assertThat(repository.completed).isEmpty()
    }

    private class DurableFakeRepository(private val request: FulfillmentRequest) :
        FulfillmentCheckpointRepository,
        FulfillmentOutboxRepository {
        private val checkpoint = FulfillmentCheckpoint(
            request.tenantId, request.namespace, request.operationKey, request.canonicalHash,
            request.source, request.targetId, FulfillmentState.DISPATCHED, null, 0, null, Instant.EPOCH,
        )
        val completed = mutableSetOf<FulfillmentEffectType>()
        var claimedTenant: UUID? = null

        override fun find(tenantId: UUID, namespace: String, operationKey: String) = checkpoint
        override fun save(checkpoint: FulfillmentCheckpoint) = checkpoint
        override fun claimPending(tenantId: UUID, workerId: String, now: Instant, leaseUntil: Instant): FulfillmentOutboxRecord {
            claimedTenant = tenantId
            return FulfillmentOutboxRecord(UUID.randomUUID(), tenantId, request.canonicalHash, request.encode(), workerId, leaseUntil)
        }
        override fun markOutboxConsumed(id: UUID, workerId: String) = Unit
        override fun completedEffects(tenantId: UUID, namespace: String, operationKey: String) = completed.toSet()
        override fun markEffectCompleted(tenantId: UUID, namespace: String, operationKey: String, effect: FulfillmentEffectType, at: Instant) {
            completed += effect
        }
        override fun markEffectStarted(tenantId: UUID, namespace: String, operationKey: String, effect: FulfillmentEffectType, at: Instant) = Unit
    }
}
