package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.order.OrderFulfillmentStallResolved
import com.duluin.ftth.order.OrderFulfillmentStalled
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
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

    /**
     * Pemicu `REQUIRES_ATTENTION` otomatis: saga yang macet WAJIB terdengar oleh module order,
     * kalau tidak pesanannya diam berkata "sedang diproses" sementara tak ada satu proses pun
     * yang masih berjalan.
     */
    @Test
    fun `stuck saga announces reconciliation for the linked order`() {
        val publisher = RecordingPublisher()
        val coordinator = FulfillmentCoordinator(
            FakeRepository(),
            FailingExecutor(FulfillmentExecutionFailure.ReconciliationRequired("ORDER_EFFECT_REJECTED")),
            events = publisher,
        )
        val order = UuidV7.generate()
        val request = request("reconcile", "d".repeat(64), orderId = order)

        coordinator.accept(request)
        assertThat(coordinator.process(request).state).isEqualTo(FulfillmentState.REQUIRES_RECONCILIATION)

        val announced = publisher.events.filterIsInstance<OrderFulfillmentStalled>().single()
        assertThat(announced.orderId).isEqualTo(order)
        assertThat(announced.tenantId).isEqualTo(tenant)
        assertThat(announced.operationKey).isEqualTo("reconcile")
        // Sebab kegagalan ikut supaya operator yang membuka pesanan bertanda bisa menelusurinya
        // tanpa membuka tabel checkpoint.
        assertThat(announced.outcome).isEqualTo("ORDER_EFFECT_REJECTED")

        // Rekonsiliasi manual MELEPAS penandanya — tanpa event ini penanda "sedang diperiksa"
        // menempel selamanya meski masalahnya sudah beres.
        coordinator.manualResolve(request, "diverifikasi manual")
        assertThat(publisher.events.filterIsInstance<OrderFulfillmentStallResolved>().single().orderId)
            .isEqualTo(order)
    }

    /**
     * Saga yang TIDAK bertaut pesanan (mis. migrasi) tidak boleh menerbitkan apa pun. Pendengarnya
     * di module order menuntut orderId non-null; menerbitkan event tanpa taut berarti memilih
     * antara NPE di worker atau menebak pesanan mana yang ditandai.
     */
    @Test
    fun `saga without an order link announces nothing`() {
        val publisher = RecordingPublisher()
        val coordinator = FulfillmentCoordinator(
            FakeRepository(),
            FailingExecutor(FulfillmentExecutionFailure.ReconciliationRequired("SUBSCRIPTION_NOT_FOUND")),
            events = publisher,
        )
        val request = request("migration", "f".repeat(64))

        coordinator.accept(request)
        assertThat(coordinator.process(request).state).isEqualTo(FulfillmentState.REQUIRES_RECONCILIATION)
        coordinator.manualResolve(request, "diverifikasi manual")

        assertThat(publisher.events).isEmpty()
    }

    /** Saga yang MULUS tidak menerbitkan apa pun: tak ada yang perlu diperiksa manusia. */
    @Test
    fun `successful saga announces nothing`() {
        val publisher = RecordingPublisher()
        val coordinator = FulfillmentCoordinator(FakeRepository(), CountingExecutor(), events = publisher)
        val request = request("smooth", "1".repeat(64), orderId = UuidV7.generate())

        coordinator.accept(request)
        assertThat(coordinator.process(request).state).isEqualTo(FulfillmentState.APPLIED)
        assertThat(publisher.events).isEmpty()
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

    private fun request(key: String, hash: String, orderId: UUID? = null) = FulfillmentRequest(
        tenant, "test.fulfillment", key, hash, FulfillmentSource.WORK_ORDER, target, null, null, null, true,
        orderId = orderId,
    )

    private class RecordingPublisher : ApplicationEventPublisher {
        val events = mutableListOf<Any>()
        override fun publishEvent(event: Any) { events += event }
    }

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
