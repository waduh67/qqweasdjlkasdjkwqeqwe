package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.tenancy.TenantCreatedEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class WarehouseTenantCreatedListener(private val jdbc: TenantTransactionJdbc) {
    @EventListener
    @Order(10)
    @Transactional(propagation = Propagation.MANDATORY)
    fun on(event: TenantCreatedEvent) = jdbc.withinTenant(event.tenantId) { connection ->
        val existing = connection.prepareStatement("SELECT id FROM inventory_tenant_cutover WHERE tenant_id=? FOR SHARE").use {
            it.setObject(1, event.tenantId)
            it.executeQuery().use { rows -> rows.next() }
        }
        if (!existing) {
            connection.prepareStatement("""
                INSERT INTO inventory_tenant_cutover(id,tenant_id,state,initialization_kind,activation_at)
                VALUES (?,?,'ENFORCED','NEW_EMPTY',warehouse_activation_at()) ON CONFLICT (tenant_id) DO NOTHING
            """.trimIndent()).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, event.tenantId)
                it.executeUpdate()
            }
        }
    }
}
