package com.duluin.ftth.provisioning.adapter.inbound.event

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.SubscriptionActivated
import com.duluin.ftth.customer.SubscriptionIsolated
import com.duluin.ftth.customer.SubscriptionTerminated
import com.duluin.ftth.provisioning.application.service.ServiceIntentLifecycleCoordinator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class SubscriptionProvisioningListener(
    private val coordinator: ServiceIntentLifecycleCoordinator,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: SubscriptionActivated) = run(event.tenantId) { coordinator.onActivated(event.subscriptionId) }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: SubscriptionIsolated) = run(event.tenantId) { coordinator.onIsolated(event.subscriptionId) }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: SubscriptionTerminated) = run(event.tenantId) { coordinator.onTerminated(event.subscriptionId) }

    private fun run(tenantId: java.util.UUID, block: () -> Unit) {
        try {
            TenantContext.runAs(tenantId, block)
        } catch (exception: Exception) {
            log.warn("Sinkronisasi intent layanan gagal untuk tenant {}", tenantId, exception)
        }
    }
}
