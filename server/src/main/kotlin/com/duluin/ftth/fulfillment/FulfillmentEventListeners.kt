package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.onboarding.MigrationImportApproved
import com.duluin.ftth.workorder.FulfillmentApproved
import com.duluin.ftth.tenancy.TenantApi
import java.util.UUID
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class WorkOrderFulfillmentListener(
    private val coordinator: FulfillmentCoordinator,
    private val worker: FulfillmentOutboxWorker,
) {
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    @Transactional
    fun on(event: FulfillmentApproved) {
        TenantContext.runAs(event.tenantId) { coordinator.accept(FulfillmentCoordinator.forWorkOrder(event)) }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun afterCommit(event: FulfillmentApproved) {
        TenantContext.runAs(event.tenantId) { worker.processNext(event.tenantId, "after-commit-${event.workOrderId}") }
    }
}

@Component
class MigrationFulfillmentListener(
    private val coordinator: FulfillmentCoordinator,
    private val worker: FulfillmentOutboxWorker,
) {
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    @Transactional
    fun on(event: MigrationImportApproved) {
        TenantContext.runAs(event.tenantId) { coordinator.accept(FulfillmentCoordinator.forMigration(event)) }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun afterCommit(event: MigrationImportApproved) {
        TenantContext.runAs(event.tenantId) { worker.processNext(event.tenantId, "after-commit-${event.operationKey}") }
    }
}

@Component
class FulfillmentOutboxWorker(
    private val coordinator: FulfillmentCoordinator,
    private val outbox: FulfillmentOutboxRepository,
    private val tenants: TenantApi? = null,
    /**
     * Rujukan ke diri sendiri lewat proxy Spring, `@Lazy` supaya bukan dependensi melingkar.
     * [drain] WAJIB memanggil [processNext] lewat ini: panggilan langsung `this.processNext(...)`
     * melewati proxy, sehingga `@Transactional(REQUIRES_NEW)` DIAM-DIAM tidak berlaku dan klaim
     * baris outbox ikut commit walau pemrosesannya gagal. Jalur listener tidak kena karena
     * mereka memanggil lewat bean yang sudah ter-proxy.
     */
    @Lazy private val self: FulfillmentOutboxWorker? = null,
) {
    fun process(request: FulfillmentRequest): FulfillmentOutcome =
        TenantContext.runAs(request.tenantId) { coordinator.process(request) }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "\${ftth.fulfillment.worker-delay:PT5S}")
    fun drain() {
        val workerId = "fulfillment-${UUID.randomUUID()}"
        val proxied = self ?: this
        tenants?.findActiveTenantIds()?.forEach { tenantId ->
            proxied.processNext(tenantId, workerId)
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processNext(tenantId: java.util.UUID, workerId: String, now: java.time.Instant = java.time.Instant.now()): FulfillmentOutcome? =
        TenantContext.runAs(tenantId) {
            val delivery = outbox.claimPending(tenantId, workerId, now, now.plusSeconds(60)) ?: return@runAs null
            val request = delivery.payload.decodeFulfillmentRequest(tenantId, delivery.payloadHash)
            val outcome = coordinator.process(request)
            if (outcome.state == FulfillmentState.APPLIED || outcome.state == FulfillmentState.MANUAL_RESOLVED) {
                outbox.markOutboxConsumed(delivery.id, workerId)
            }
            outcome
        }
}
