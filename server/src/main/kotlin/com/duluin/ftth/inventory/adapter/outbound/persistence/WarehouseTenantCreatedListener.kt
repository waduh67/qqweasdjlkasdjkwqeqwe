package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.tenancy.TenantCreatedEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class WarehouseTenantCreatedListener(private val jdbc: TenantTransactionJdbc) {
    @EventListener
    @Order(10)
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: TenantCreatedEvent) = jdbc.withinTenant(event.tenantId) { connection ->
        connection.prepareStatement("SELECT warehouse_initialize_reference_tenant(?)").use {
            it.setObject(1, event.tenantId)
            it.execute()
        }
    }
}
