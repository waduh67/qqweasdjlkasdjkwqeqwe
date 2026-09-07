package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.persistence.TenantTransactionJdbc
import com.duluin.ftth.tenancy.TenantCreatedEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import java.util.UUID

class WarehouseTenantCreationProbe(private val jdbc: TenantTransactionJdbc) {
    var mode: String? = null
    var tenantId: UUID? = null

    @EventListener
    @Order(5)
    fun beforeInventory(event: TenantCreatedEvent) {
        if (mode != "NONEMPTY") return
        mode = null
        tenantId = event.tenantId
        jdbc.withinTenant(event.tenantId) { connection ->
            connection.prepareStatement("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES (?,?,'not-empty','DEMAND',?,0,0)").use {
                it.setObject(1, UUID.randomUUID()); it.setObject(2,event.tenantId); it.setObject(3,UUID.randomUUID())
                it.executeUpdate()
            }
        }
    }

    @EventListener
    @Order(100)
    fun afterInventory(event: TenantCreatedEvent) {
        if (mode != "AFTER_INIT") return
        mode = null
        tenantId = event.tenantId
        throw IllegalStateException("injected after warehouse initialization")
    }
}
