package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.domain.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import com.duluin.ftth.workorder.FulfillmentApproved

class FulfillmentCoordinatorTest {
    private val tenant = UuidV7.generate()
    private val target = UuidV7.generate()

    @Test
    fun `same approved operation replays without repeating external effect`() {
        val repository = FakeRepository()
        val executor = CountingExecutor()
        val coordinator = FulfillmentCoordinator(repository, executor)
        val request = request("operation", "a".repeat(64))

        assertThat(coordinator.accept(request).state).isEqualTo(FulfillmentState.DISPATCHED)
        assertThat(coordinator.process(request).state).isEqualTo(FulfillmentState.APPLIED)
        assertThat(coordinator.accept(request).replayed).isTrue()
        assertThat(executor.calls).isEqualTo(1)
    }

    @Test
    fun `same operation with a different canonical hash conflicts`() {
        val coordinator = FulfillmentCoordinator(FakeRepository(), CountingExecutor())
        coordinator.accept(request("operation", "a".repeat(64)))

        assertThatThrownBy { coordinator.accept(request("operation", "b".repeat(64))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("FULFILLMENT_OPERATION_HASH_CONFLICT")
    }

    @Test
    fun `external retry and reconciliation outcomes remain durable`() {
        val retryRepository = FakeRepository()
        val retry = FulfillmentCoordinator(retryRepository, FailingExecutor(FulfillmentExecutionFailure.Retryable("timeout")))
        val retryRequest = request("retry", "c".repeat(64))
        retry.accept(retryRequest)
        assertThat(retry.process(retryRequest).state).isEqualTo(FulfillmentState.FAILED_RETRYABLE)
        assertThat(retryRepository.values.single().attempts).isEqualTo(1)

        val reconciliationRepository = FakeRepository()
        val reconciliation = FulfillmentCoordinator(reconciliationRepository, FailingExecutor(FulfillmentExecutionFailure.ReconciliationRequired("unknown receipt")))
        val reconciliationRequest = request("reconcile", "d".repeat(64))
        reconciliation.accept(reconciliationRequest)
        assertThat(reconciliation.process(reconciliationRequest).state).isEqualTo(FulfillmentState.REQUIRES_RECONCILIATION)
        assertThat(reconciliation.manualResolve(reconciliationRequest, "verified externally").state)
            .isEqualTo(FulfillmentState.MANUAL_RESOLVED)
    }

    @Test
    fun `unapproved input cannot enter the coordinator`() {
        assertThatThrownBy {
            FulfillmentRequest(tenant, "test", "key", "e".repeat(64), FulfillmentSource.WORK_ORDER, target, null, null, null, false)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessage("FULFILLMENT_APPROVAL_REQUIRED")
    }

    @Test
    fun `approved work order event becomes an accepted request`() {
        val event = FulfillmentApproved(tenant, UuidV7.generate(), "PSB", target, "a".repeat(64))
        assertThat(FulfillmentCoordinator.forWorkOrder(event).approved).isTrue()
    }

    private fun request(key: String, hash: String) = FulfillmentRequest(
        tenant, "test.fulfillment", key, hash, FulfillmentSource.WORK_ORDER, target, null, null, null, true,
    )

    private class CountingExecutor : FulfillmentEffectExecutor {
        var calls = 0
        override fun apply(request: FulfillmentRequest) { calls++ }
    }

    private class FailingExecutor(private val failure: FulfillmentExecutionFailure) : FulfillmentEffectExecutor {
        override fun apply(request: FulfillmentRequest): Unit = throw failure
    }

    private class FakeRepository : FulfillmentCheckpointRepository {
        val values = mutableListOf<FulfillmentCheckpoint>()
        override fun find(tenantId: UUID, namespace: String, operationKey: String) = values.firstOrNull {
            it.tenantId == tenantId && it.namespace == namespace && it.operationKey == operationKey
        }
        override fun save(checkpoint: FulfillmentCheckpoint): FulfillmentCheckpoint {
            values.removeIf { it.tenantId == checkpoint.tenantId && it.namespace == checkpoint.namespace && it.operationKey == checkpoint.operationKey }
            values += checkpoint
            return checkpoint
        }
    }
}
