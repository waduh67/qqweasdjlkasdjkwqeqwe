package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.tenancy.TenantCreatedEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class TenantAuthorizationCreatedListener(private val jdbc: TenantTransactionJdbc) {
    @EventListener
    @Order(0)
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: TenantCreatedEvent) = jdbc.withinTenant(event.tenantId) { connection ->
        connection.prepareStatement("INSERT INTO iam_authorization_epoch(id,tenant_id,epoch) VALUES (?,?,0) ON CONFLICT (tenant_id) DO NOTHING").use {
            it.setObject(1, UUID.randomUUID())
            it.setObject(2, event.tenantId)
            it.executeUpdate()
        }
    }
}
