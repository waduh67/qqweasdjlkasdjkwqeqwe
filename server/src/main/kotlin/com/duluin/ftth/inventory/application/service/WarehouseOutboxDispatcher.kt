package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.WarehouseOutboxDeliveryPort
import com.duluin.ftth.inventory.adapter.outbound.persistence.*
import com.duluin.ftth.tenancy.TenantApi
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

@Component
class WarehouseOutboxDispatcher(
    private val deliveries: WarehouseOutboxDeliveryStore,
    private val reader: WarehouseDeliveryReader,
    private val destination: WarehouseOutboxDeliveryPort,
    private val tenants: TenantApi,
) {
    private val node = UUID.randomUUID()
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    fun dispatchOne(): Boolean {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "Delivery must run outside local lock transactions" }
        val lease = deliveries.claim(node) ?: return false
        val message = try { reader.read(lease) } catch (failure: UnsupportedWarehouseDelivery) {
            deliveries.failed(lease, WarehouseDeliveryFailure.NO_HANDLER); return true
        } catch (failure: Exception) {
            deliveries.failed(lease, WarehouseDeliveryFailure.RECONCILIATION_REQUIRED); return true
        }
        try {
            check(!TransactionSynchronizationManager.isActualTransactionActive())
            destination.deliver(message)
            deliveries.delivered(lease)
        } catch (failure: com.duluin.ftth.inventory.WarehouseContractException) {
            deliveries.failed(lease, WarehouseDeliveryFailure.RECONCILIATION_REQUIRED)
        } catch (failure: Exception) {
            deliveries.failed(lease, WarehouseDeliveryFailure.RETRYABLE)
        }
        return true
    }

    fun drain() {
        tenants.findActiveTenantIds().forEach { tenant ->
            try {
                TenantContext.runAs(tenant) {
                    repeat(25) { if (!dispatchOne()) return@runAs }
                }
            } catch (failure: Exception) {
                val code = if (failure is com.duluin.ftth.inventory.WarehouseContractException) failure.error.code.name else "DELIVERY_FAILURE"
                log.warn("warehouse_delivery_tenant_failed tenant={} code={}", tenant, code)
            }
        }
    }
}

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["ftth.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class WarehouseOutboxSchedule(private val dispatcher: WarehouseOutboxDispatcher) {
    @Scheduled(fixedDelayString = "\${ftth.warehouse.delivery-delay:PT5S}")
    fun drain() = dispatcher.drain()
}
